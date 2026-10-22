/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include "local-include/reg.h"
#include <cpu/cpu.h>
#include <cpu/ifetch.h>
#include <cpu/decode.h>
#include <iringbuf.h>
#include <ftrace.h>
#include <cpu/btrace.h>
#include <utils.h>

/*
 * 原生 NEMU 使用宿主 libc 的 setjmp/longjmp；TARGET_AM 是 freestanding，
 * 没有 setjmp 实现。这里用 GCC 内建版本完成“访存异常跳出取指循环”，
 * 避免 AM 构建依赖 newlib 的 setjmp.S。
 */
#ifdef CONFIG_TARGET_AM
typedef void *nemu_jmp_buf[5];
#define nemu_setjmp(buf) __builtin_setjmp(buf)
#define nemu_longjmp(buf, val) __builtin_longjmp(buf, val)
#else
#include <setjmp.h>
typedef jmp_buf nemu_jmp_buf;
#define nemu_setjmp(buf) setjmp(buf)
#define nemu_longjmp(buf, val) longjmp(buf, val)
#endif

#define R(i) gpr(i)
#define Mr vaddr_read
#define Mw vaddr_write

extern uint64_t g_nr_guest_inst;

word_t isa_trap_tval = 0;

static nemu_jmp_buf *exception_jmp = NULL;
static word_t exception_cause = 0;
static vaddr_t exception_epc = 0;

void isa_raise_mem_exception(word_t cause, vaddr_t tval) {
  if (exception_jmp != NULL) {
    exception_cause = cause;
    isa_trap_tval = tval;
    nemu_longjmp(*exception_jmp, 1);
  }
  panic("memory exception cause=%" PRIu64 ", tval=" FMT_WORD, (uint64_t)cause, tval);
}

static inline word_t mepc_warl(word_t value)
{
  return value & ~(word_t)3u;
}

static inline word_t mtvec_warl(word_t value)
{
  const word_t mode = (value & 3u) == 1u ? 1u : 0u;
  return (value & ~(word_t)3u) | mode;
}

static inline word_t mstatus_warl(word_t value)
{
  /* UXL/SXL 是 WARL：NEMU 只支持 64 位，所以两位固定写成 2。 */
  value = (value & ~(word_t)MSTATUS_XLEN_MASK) | MSTATUS_XLEN_64;
  return value & (word_t)MSTATUS_WRITABLE_MASK;
}

/* RV64 只有 pmpcfg0 和 pmpcfg2；RV32 四个寄存器都存在。 */
static inline bool pmpcfg_address_valid(word_t address)
{
#ifdef CONFIG_RV64
  return address == 0x3a0 || address == 0x3a2;
#else
  return address >= 0x3a0 && address <= 0x3a3;
#endif
}

static inline word_t pmpcfg_entry(int index)
{
  return (cpu.pmpcfg[PMP_CFG_REG_INDEX(index)] >>
          PMP_CFG_ENTRY_SHIFT(index)) & 0xffu;
}

static inline word_t csr_address(const Decode *s)
{
  return BITS(s->isa.inst, 31, 20);
}

static inline word_t csr_zimm(const Decode *s)
{
  return BITS(s->isa.inst, 19, 15);
}

static inline word_t csr_rs1(const Decode *s)
{
  return R(BITS(s->isa.inst, 19, 15));
}

static inline bool csr_is_read_only(word_t address)
{
  return BITS(address, 11, 10) == 3u;
}

static inline bool csr_priv_ok(word_t address)
{
  if (cpu.privilege < BITS(address, 9, 8)) {
    return false;
  }

  // U 模式读取 cycle/time/instret 还受 mcounteren/scounteren 控制。
  if (cpu.privilege == RISCV_PRIV_U) {
    word_t bit = 0;
    if (address >= 0xc00 && address <= 0xc02) {
      bit = 1u << (address - 0xc00);
    } else if (address >= 0xc80 && address <= 0xc82) {
      bit = 1u << (address - 0xc80);
    }
    if (bit != 0) {
      return (cpu.mcounteren & bit) != 0 && (cpu.scounteren & bit) != 0;
    }
  }
  return true;
}

static word_t csr_read(word_t address)
{
  if (address >= 0x3a0 && address <= 0x3a3) {
    if (!pmpcfg_address_valid(address)) {
      panic("RV64 不存在 CSR " FMT_WORD "（pmpcfg1/pmpcfg3 仅为 RV32）", address);
    }
    return cpu.pmpcfg[address - 0x3a0];
  }
  if (address >= 0x3b0 && address <= 0x3bf) {
    return cpu.pmpaddr[address - 0x3b0];
  }

  switch (address)
  {
  case 0xf11: return 0x79737978u;
  case 0xf12: return 0x018d3017u;
  case 0xf13: return 0;
  case 0xf14: return 0;
  case 0xf15: return 0;
  case 0x100: return cpu.mstatus & SSTATUS_MASK;
  case 0x106: return cpu.scounteren;
  case 0x104: return cpu.mie & (MSTATUS_SIE | (1u << 5) | (1u << 9));
  case 0x105: return cpu.stvec;
  case 0x140: return cpu.sscratch;
  case 0x141: return cpu.sepc;
  case 0x142: return cpu.scause;
  case 0x143: return cpu.stval;
  case 0x144: return cpu.mip & (MSTATUS_SIE | (1u << 5) | (1u << 9));
  case 0x180: return cpu.satp;
  case 0x300: return cpu.mstatus;
  case 0x301:
    // misa.MXL：RV32 为 1，RV64 为 2。扩展位保持 I/M/S/U。
    return MUXDEF(CONFIG_RV64,
                  ((word_t)2 << 62) | (1u << 8) | (1u << 12) |
                      (1u << 18) | (1u << 20),
                  (1u << 30) | (1u << 8) | (1u << 12) |
                      (1u << 18) | (1u << 20));
  case 0x302: return cpu.medeleg;
  case 0x303: return cpu.mideleg;
  case 0x304: return cpu.mie;
  case 0x305: return cpu.mtvec;
  case 0x306: return cpu.mcounteren;
  case 0x310: return 0; // mstatush
  case 0x320: return cpu.mcountinhibit;
  case 0x340: return cpu.mscratch;
  case 0x341: return cpu.mepc;
  case 0x342: return cpu.mcause;
  case 0x343: return cpu.mtval;
  case 0x344: return cpu.mip;
  case 0xb00: return (word_t)g_nr_guest_inst;
  case 0xb02: return (word_t)g_nr_guest_inst;
  case 0xb80: return (word_t)(g_nr_guest_inst >> 32);
  case 0xb82: return (word_t)(g_nr_guest_inst >> 32);
  case 0xc00: return (word_t)g_nr_guest_inst;
  case 0xc01: return (word_t)get_time();
  case 0xc02: return (word_t)g_nr_guest_inst;
  case 0xc80: return (word_t)(g_nr_guest_inst >> 32);
  case 0xc81: return (word_t)(get_time() >> 32);
  case 0xc82: return (word_t)(g_nr_guest_inst >> 32);
  default: panic("Unknown CSR " FMT_WORD, address); return 0;
  }
}

static void csr_write(word_t address, word_t value)
{
  if (address >= 0x3a0 && address <= 0x3a3) {
    if (!pmpcfg_address_valid(address)) {
      panic("RV64 不存在 CSR " FMT_WORD "（pmpcfg1/pmpcfg3 仅为 RV32）", address);
    }
    const int idx = address - 0x3a0;
    const word_t old = cpu.pmpcfg[idx];
    word_t locked = 0;
    for (int i = 0; i < PMP_CFG_ENTRIES_PER_REG; i++) {
      if (old & ((word_t)0x80 << (i * 8))) {
        locked |= (word_t)0xff << (i * 8);
      }
    }
    cpu.pmpcfg[idx] = (old & locked) | (value & ~locked);
    return;
  }
  if (address >= 0x3b0 && address <= 0x3bf) {
    const int idx = address - 0x3b0;
    if ((pmpcfg_entry(idx) & 0x80u) == 0) {
      cpu.pmpaddr[idx] = value;
    }
    return;
  }

  switch (address)
  {
  /* The decoder rejects write-intent accesses to these read-only CSRs. */
  case 0xf11:
  case 0xf12:
    return;
  case 0x100:
    cpu.mstatus = mstatus_warl((cpu.mstatus & ~SSTATUS_WRITABLE_MASK) |
                               (value & SSTATUS_WRITABLE_MASK));
    return;
  case 0x106: cpu.scounteren = value; return;
  case 0x104:
    cpu.mie = (cpu.mie & ~(MSTATUS_SIE | (1u << 5) | (1u << 9))) |
              (value & (MSTATUS_SIE | (1u << 5) | (1u << 9)));
    return;
  case 0x105: cpu.stvec = mtvec_warl(value); return;
  case 0x140: cpu.sscratch = value; return;
  case 0x141: cpu.sepc = mepc_warl(value); return;
  case 0x142: cpu.scause = value; return;
  case 0x143: cpu.stval = value; return;
  case 0x144:
    cpu.mip = (cpu.mip & ~(MSTATUS_SIE | (1u << 5) | (1u << 9))) |
              (value & (MSTATUS_SIE | (1u << 5) | (1u << 9)));
    return;
  case 0x180: cpu.satp = value; return;
  case 0x300: cpu.mstatus = mstatus_warl(value); return;
  case 0x301: return; // misa 固定为 RV32IMSU
  case 0x302: cpu.medeleg = value; return;
  case 0x303: cpu.mideleg = value; return;
  case 0x304: cpu.mie = value; return;
  case 0x305: cpu.mtvec = mtvec_warl(value); return;
  case 0x306: cpu.mcounteren = value; return;
  case 0x310: return; // mstatush 全 0
  case 0x320: cpu.mcountinhibit = value; return;
  case 0x340: cpu.mscratch = value; return;
  case 0x341: cpu.mepc = mepc_warl(value); return;
  case 0x342: cpu.mcause = value; return;
  case 0x343: cpu.mtval = value; return;
  case 0x344: cpu.mip = value; return;
  /* mcycle is nondeterministic relative to RTL wall-clock cycles.  NPC's
   * online DiffTest result-injects these instructions, but retaining writes
   * here makes standalone NEMU's supported CSR set match the decoder. */
  case 0xb00:
  case 0xb02:
    g_nr_guest_inst = (g_nr_guest_inst & 0xffffffff00000000ull) | value;
    return;
  case 0xb80:
  case 0xb82:
    g_nr_guest_inst = (g_nr_guest_inst & 0x00000000ffffffffull) |
                      ((uint64_t)value << 32);
    return;
  default: panic("Unknown CSR " FMT_WORD, address); return;
  }
}

static inline void mret_update_mstatus(void)
{
  const word_t mpp = BITS(cpu.mstatus, 12, 11);
  const word_t mpie = BITS(cpu.mstatus, 7, 7);

  /* MPP 保存要返回的特权级。 */
  cpu.privilege = mpp;

  /* MIE <- MPIE，MPIE <- 1，MPP <- U（支持的最低特权级）。 */
  cpu.mstatus = mstatus_warl(
      (cpu.mstatus & ~(word_t)(MSTATUS_MPP_MASK | MSTATUS_MPIE | MSTATUS_MIE)) |
      (mpie ? MSTATUS_MIE : 0u) | MSTATUS_MPIE);
}

static inline void sret_update_mstatus(void)
{
  const word_t spp = BITS(cpu.mstatus, 8, 8);
  const word_t spie = BITS(cpu.mstatus, 5, 5);

  /* SPP 保存要返回的特权级：1 返回 S，0 返回 U。 */
  cpu.privilege = spp ? RISCV_PRIV_S : RISCV_PRIV_U;

  /* SIE <- SPIE，SPIE <- 1，SPP <- U。 */
  cpu.mstatus = mstatus_warl(
      (cpu.mstatus & ~(word_t)(MSTATUS_SPP | MSTATUS_SPIE | MSTATUS_SIE)) |
      (spie ? MSTATUS_SIE : 0u) | MSTATUS_SPIE);
}

enum
{
  TYPE_I,
  TYPE_U,
  TYPE_S,
  TYPE_J,
  TYPE_R,
  TYPE_B,
  TYPE_N, // none
};

#define src1R()     \
  do                \
  {                 \
    *src1 = R(rs1); \
  } while (0)
#define src2R()     \
  do                \
  {                 \
    *src2 = R(rs2); \
  } while (0)
#define immI()                        \
  do                                  \
  {                                   \
    *imm = SEXT(BITS(i, 31, 20), 12); \
  } while (0)
#define immU()                              \
  do                                        \
  {                                         \
    *imm = SEXT(BITS(i, 31, 12), 20) << 12; \
  } while (0)
#define immS()                                               \
  do                                                         \
  {                                                          \
    *imm = (SEXT(BITS(i, 31, 25), 7) << 5) | BITS(i, 11, 7); \
  } while (0)
#define immJ()                                                                         \
  do                                                                                   \
  {                                                                                    \
    word_t imm20 = BITS(i, 31, 31);                                                    \
    word_t imm10_1 = BITS(i, 30, 21);                                                  \
    word_t imm11 = BITS(i, 20, 20);                                                    \
    word_t imm19_12 = BITS(i, 19, 12);                                                 \
    *imm = (SEXT(imm20, 1) << 20) | (imm19_12 << 12) | (imm11 << 11) | (imm10_1 << 1); \
  } while (0)
#define immB()                                                                      \
  do                                                                                \
  {                                                                                 \
    word_t imm12 = BITS(i, 31, 31);                                                 \
    word_t imm10_5 = BITS(i, 30, 25);                                               \
    word_t imm4_1 = BITS(i, 11, 8);                                                 \
    word_t imm11 = BITS(i, 7, 7);                                                   \
    *imm = (SEXT(imm12, 1) << 12) | (imm11 << 11) | (imm10_5 << 5) | (imm4_1 << 1); \
  } while (0)

/* 按当前 XLEN 截断移位量，避免 C 移位量越界产生未定义行为。 */
static inline word_t shift_amount(word_t value)
{
  return value & MUXDEF(CONFIG_RV64, 0x3fu, 0x1fu);
}

/* RV64 的 *W 指令只运算低 32 位，结果符号扩展到 64 位。 */
static inline word_t sext32(word_t value)
{
  return (word_t)(sword_t)(int32_t)value;
}

static void decode_operand(Decode *s, int *rd, word_t *src1, word_t *src2, word_t *imm, int type)
{
  uint32_t i = s->isa.inst;
  int rs1 = BITS(i, 19, 15);
  int rs2 = BITS(i, 24, 20);
  *rd = BITS(i, 11, 7);
  switch (type)
  {
  case TYPE_I:
    src1R();
    immI();
    break;
  case TYPE_U:
    immU();
    break;
  case TYPE_S:
    src1R();
    src2R();
    immS();
    break;
  case TYPE_J:
    immJ();
    break;
  case TYPE_R:
    src1R();
    src2R();
    break;
  case TYPE_B:
    src1R();
    src2R();
    immB();
    break;
  case TYPE_N:
    break;
  default:
    panic("unsupported type = %d", type);
  }
}

static int decode_exec(Decode *s)
{
  s->dnpc = s->snpc;

#define INSTPAT_INST(s) ((s)->isa.inst)
#define INSTPAT_MATCH(s, name, type, ... /* execute body */)         \
  {                                                                  \
    int rd = 0;                                                      \
    word_t src1 = 0, src2 = 0, imm = 0;                              \
    decode_operand(s, &rd, &src1, &src2, &imm, concat(TYPE_, type)); \
    __VA_ARGS__;                                                     \
  }

  INSTPAT_START();
  INSTPAT("??????? ????? ????? ??? ????? 00101 11", auipc, U, R(rd) = s->pc + imm);
  INSTPAT("??????? ????? ????? 100 ????? 00000 11", lbu, I, R(rd) = Mr(src1 + imm, 1));
  INSTPAT("??????? ????? ????? 000 ????? 01000 11", sb, S, Mw(src1 + imm, 1, src2));

  // custom-0 is the NPC/ysyxSoC simulation-exit ABI.  Architectural EBREAK
  // must remain a breakpoint exception so NEMU can still serve as a reference
  // for guests that install a real mtvec handler.
  INSTPAT("0000000 00000 00000 000 00000 00010 11", sim_halt, N, NEMUTRAP(s->pc, R(10)));
  INSTPAT("0000000 00001 00000 000 00000 11100 11", ebreak, N, s->dnpc = isa_raise_intr(3, s->pc));
  INSTPAT("0001000 00101 00000 000 00000 11100 11", wfi, N, {
    if (cpu.privilege == RISCV_PRIV_U ||
        (cpu.privilege == RISCV_PRIV_S && (cpu.mstatus & MSTATUS_TW))) {
      s->dnpc = isa_raise_intr(2, s->pc);
    }
  });
  // 自己写的
  INSTPAT("??????? ????? ????? 000 ????? 00100 11", addi, I, R(rd) = src1 + imm);
  INSTPAT("??????? ????? ????? ??? ????? 11011 11", jal, J, {
    R(rd) = s->pc + 4;
    BTRACE_JAL(s, imm, rd);
#ifdef CONFIG_FTRACE
    // 标准 RISC-V ABI: 只有 jal ra, offset (rd == x1) 才是函数调用
    if (rd == 1)
    {
      FtraceOnCall(&GlobalFtraceState, s->pc, s->dnpc);
    }
#endif
  });
  INSTPAT("??????? ????? ????? 010 ????? 01000 11", sw, S, Mw(src1 + imm, 4, src2));
  INSTPAT("??????? ????? ????? 000 ????? 11001 11", jalr, I, {
    R(rd) = s->pc + 4;
    s->dnpc = (src1 + imm) & ~1;
    // 因为 jalr 既可能 call 也可能 ret
    int rs1 = BITS(s->isa.inst, 19, 15);
    (void)rs1; // CONFIG_BTRACE/CONFIG_FTRACE 都关闭时也不能触发 -Wunused
    BTRACE_JALR(s, rs1, rd, imm);
#ifdef CONFIG_FTRACE
    // 标准 RISC-V ABI: ret 为 jalr x0, x1, 0
    if (rd == 0 && rs1 == 1 && imm == 0)
    {
      FtraceOnReturn(&GlobalFtraceState, s->pc, s->dnpc);
    }
    else if (rd == 1)
    {
      // 标准 RISC-V ABI: 只有 jalr ra, ... (rd == x1) 才是函数调用
      FtraceOnCall(&GlobalFtraceState, s->pc, s->dnpc);
    }
#endif
  });
  INSTPAT("??????? ????? ????? 010 ????? 00000 11", lw, I,
          R(rd) = MUXDEF(CONFIG_RV64, sext32(Mr(src1 + imm, 4)), Mr(src1 + imm, 4)));
#ifdef CONFIG_RV64
  // RV64I：64 位 load/store 与零扩展字 load。
  INSTPAT("??????? ????? ????? 011 ????? 00000 11", ld, I, R(rd) = Mr(src1 + imm, 8));
  INSTPAT("??????? ????? ????? 110 ????? 00000 11", lwu, I, R(rd) = Mr(src1 + imm, 4));
  INSTPAT("??????? ????? ????? 011 ????? 01000 11", sd, S, Mw(src1 + imm, 8, src2));
#endif
  INSTPAT("0000000 ????? ????? 000 ????? 01100 11", add, R, R(rd) = src1 + src2);
  INSTPAT("0100000 ????? ????? 000 ????? 01100 11", sub, R, R(rd) = src1 - src2);
  INSTPAT("??????? ????? ????? 011 ????? 00100 11", sltiu, I, R(rd) = (src1 < imm) ? 1 : 0);
  INSTPAT("??????? ????? ????? 000 ????? 11000 11", beq, B, BTRACE_BRANCH(s, imm, src1 == src2));
  INSTPAT("??????? ????? ????? 001 ????? 11000 11", bne, B, BTRACE_BRANCH(s, imm, src1 != src2));
  INSTPAT("0000000 ????? ????? 011 ????? 01100 11", sltu, R, R(rd) = (src1 < src2) ? 1 : 0);
  INSTPAT("??????? ????? ????? 100 ????? 00100 11", xori, I, R(rd) = src1 ^ imm);
  INSTPAT("0000000 ????? ????? 100 ????? 01100 11", xor, R, R(rd) = src1 ^ src2);
  INSTPAT("??????? ????? ????? 110 ????? 00100 11", ori, I, R(rd) = src1 | imm);
  INSTPAT("0000000 ????? ????? 110 ????? 01100 11", or, R, R(rd) = src1 | src2);
  INSTPAT("??????? ????? ????? 001 ????? 01000 11", sh, S, Mw(src1 + imm, 2, src2));
  INSTPAT("0000000 ????? ????? 111 ????? 01100 11", and, R, R(rd) = src1 & src2);
  INSTPAT("??????? ????? ????? 111 ????? 00100 11", andi, I, R(rd) = src1 & imm);
  INSTPAT("0000000 ????? ????? 001 ????? 01100 11", sll, R, R(rd) = src1 << shift_amount(src2));
  INSTPAT("0000000 ????? ????? 101 ????? 01100 11", srl, R, R(rd) = src1 >> shift_amount(src2));
  INSTPAT("0100000 ????? ????? 101 ????? 01100 11", sra, R, R(rd) = (word_t)((sword_t)src1 >> shift_amount(src2)));
  INSTPAT("0000000 ????? ????? 101 ????? 00100 11", srli, I, R(rd) = src1 >> shift_amount(imm));
  INSTPAT("0100000 ????? ????? 101 ????? 00100 11", srai, I, R(rd) = (word_t)((sword_t)src1 >> shift_amount(imm)));
  INSTPAT("0000000 ????? ????? 001 ????? 00100 11", slli, I, R(rd) = src1 << shift_amount(imm));
#ifdef CONFIG_RV64
  // RV64I 的 6 位移位量（shamt[5] 位于原 funct7 的最低位）。
  INSTPAT("010000 ? ????? ????? 101 ????? 00100 11", srai, I,
          R(rd) = (word_t)((sword_t)src1 >> (imm & 0x3f)));
  INSTPAT("000000 ? ????? ????? 001 ????? 00100 11", slli, I,
          R(rd) = src1 << (imm & 0x3f));
  INSTPAT("000000 ? ????? ????? 101 ????? 00100 11", srli, I,
          R(rd) = src1 >> (imm & 0x3f));

  // RV64I 的 OP-IMM-32 与 OP-32 指令。
  INSTPAT("??????? ????? ????? 000 ????? 00110 11", addiw, I, R(rd) = sext32(src1 + imm));
  INSTPAT("0000000 ????? ????? 001 ????? 00110 11", slliw, I,
          R(rd) = sext32((uint32_t)src1 << (imm & 0x1f)));
  INSTPAT("0000000 ????? ????? 101 ????? 00110 11", srliw, I,
          R(rd) = sext32((uint32_t)src1 >> (imm & 0x1f)));
  INSTPAT("0100000 ????? ????? 101 ????? 00110 11", sraiw, I,
          R(rd) = sext32((uint32_t)((int32_t)src1 >> (imm & 0x1f))));
  INSTPAT("0000000 ????? ????? 000 ????? 01110 11", addw, R, R(rd) = sext32(src1 + src2));
  INSTPAT("0100000 ????? ????? 000 ????? 01110 11", subw, R, R(rd) = sext32(src1 - src2));
  INSTPAT("0000000 ????? ????? 001 ????? 01110 11", sllw, R,
          R(rd) = sext32((uint32_t)src1 << (src2 & 0x1f)));
  INSTPAT("0000000 ????? ????? 101 ????? 01110 11", srlw, R,
          R(rd) = sext32((uint32_t)src1 >> (src2 & 0x1f)));
  INSTPAT("0100000 ????? ????? 101 ????? 01110 11", sraw, R,
          R(rd) = sext32((uint32_t)((int32_t)src1 >> (src2 & 0x1f))));
#endif
  INSTPAT("??????? ????? ????? 101 ????? 11000 11", bge, B, BTRACE_BRANCH(s, imm, (sword_t)src1 >= (sword_t)src2));
  INSTPAT("??????? ????? ????? 100 ????? 11000 11", blt, B, BTRACE_BRANCH(s, imm, (sword_t)src1 < (sword_t)src2));
  INSTPAT("??????? ????? ????? ??? ????? 01101 11", lui, U, R(rd) = imm);
  INSTPAT("??????? ????? ????? 111 ????? 11000 11", bgeu, B, BTRACE_BRANCH(s, imm, src1 >= src2));
  INSTPAT("??????? ????? ????? 110 ????? 11000 11", bltu, B, BTRACE_BRANCH(s, imm, src1 < src2));
  INSTPAT("0000001 ????? ????? 000 ????? 01100 11", mul, R, R(rd) = src1 * src2);
#ifdef CONFIG_RV64
  // RV64M：64 位乘除余。乘高位使用 128 位中间结果，绝不按 32 位截断。
  INSTPAT("0000001 ????? ????? 100 ????? 01100 11", div, R, {
    sword_t s1 = (sword_t)src1, s2 = (sword_t)src2;
    if (s2 == 0) R(rd) = ~(word_t)0;
    else if (s1 == INT64_MIN && s2 == -1) R(rd) = (word_t)s1;
    else R(rd) = (word_t)(s1 / s2);
  });
  INSTPAT("0000001 ????? ????? 110 ????? 01100 11", rem, R, {
    sword_t s1 = (sword_t)src1, s2 = (sword_t)src2;
    if (s2 == 0) R(rd) = src1;
    else if (s1 == INT64_MIN && s2 == -1) R(rd) = 0;
    else R(rd) = (word_t)(s1 % s2);
  });
  INSTPAT("0000001 ????? ????? 111 ????? 01100 11", remu, R,
          R(rd) = (src2 == 0) ? src1 : src1 % src2);
  INSTPAT("0000001 ????? ????? 001 ????? 01100 11", mulh, R,
          R(rd) = (word_t)(((__int128)(sword_t)src1 * (__int128)(sword_t)src2) >> 64));
  INSTPAT("0000001 ????? ????? 101 ????? 01100 11", divu, R,
          R(rd) = (src2 == 0) ? ~(word_t)0 : src1 / src2);
  INSTPAT("0000001 ????? ????? 011 ????? 01100 11", mulhu, R,
          R(rd) = (word_t)(((unsigned __int128)src1 * (unsigned __int128)src2) >> 64));
  INSTPAT("0000001 ????? ????? 010 ????? 01100 11", mulhsu, R,
          R(rd) = (word_t)(((__int128)(sword_t)src1 * (unsigned __int128)src2) >> 64));

  // RV64M 的 *W 形式：32 位运算后符号扩展到 64 位。
  INSTPAT("0000001 ????? ????? 000 ????? 01110 11", mulw, R,
          R(rd) = sext32((uint32_t)src1 * (uint32_t)src2));
  INSTPAT("0000001 ????? ????? 100 ????? 01110 11", divw, R, {
    int32_t s1 = (int32_t)src1, s2 = (int32_t)src2;
    int32_t result;
    if (s2 == 0) result = -1;
    else if (s1 == INT32_MIN && s2 == -1) result = INT32_MIN;
    else result = s1 / s2;
    R(rd) = sext32((uint32_t)result);
  });
  INSTPAT("0000001 ????? ????? 101 ????? 01110 11", divuw, R, {
    uint32_t u1 = (uint32_t)src1, u2 = (uint32_t)src2;
    uint32_t result = (u2 == 0) ? UINT32_MAX : u1 / u2;
    R(rd) = sext32(result);
  });
  INSTPAT("0000001 ????? ????? 110 ????? 01110 11", remw, R, {
    int32_t s1 = (int32_t)src1, s2 = (int32_t)src2;
    int32_t result;
    if (s2 == 0) result = s1;
    else if (s1 == INT32_MIN && s2 == -1) result = 0;
    else result = s1 % s2;
    R(rd) = sext32((uint32_t)result);
  });
  INSTPAT("0000001 ????? ????? 111 ????? 01110 11", remuw, R, {
    uint32_t u1 = (uint32_t)src1, u2 = (uint32_t)src2;
    uint32_t result = (u2 == 0) ? u1 : u1 % u2;
    R(rd) = sext32(result);
  });
#else
  INSTPAT("0000001 ????? ????? 100 ????? 01100 11", div, R, sword_t s1 = (sword_t)src1; sword_t s2 = (sword_t)src2; if (src2 == 0) { R(rd) = ~(word_t)0; } else if (s1 == INT32_MIN && s2 == -1) { R(rd) = (word_t)s1; } else { R(rd) = (word_t)(s1 / s2); });
  INSTPAT("0000001 ????? ????? 110 ????? 01100 11", rem, R, sword_t s1 = (sword_t)src1; sword_t s2 = (sword_t)src2; if (src2 == 0) { R(rd) = src1; } else if (s1 == INT32_MIN && s2 == -1) { R(rd) = 0; } else { R(rd) = (word_t)(s1 % s2); });
  INSTPAT("0000001 ????? ????? 111 ????? 01100 11", remu, R, if (src2 == 0) { R(rd) = src1; } else { R(rd) = src1 % src2; });
  INSTPAT("0000001 ????? ????? 001 ????? 01100 11", mulh, R, R(rd) = ((int64_t)(sword_t)src1 * (int64_t)(sword_t)src2) >> 32);
  INSTPAT("0000001 ????? ????? 101 ????? 01100 11", divu, R, if (src2 == 0) { R(rd) = ~(word_t)0; } else { R(rd) = src1 / src2; });
  INSTPAT("0000001 ????? ????? 011 ????? 01100 11", mulhu, R, R(rd) = ((uint64_t)src1 * (uint64_t)src2) >> 32);
  INSTPAT("0000001 ????? ????? 010 ????? 01100 11", mulhsu, R, R(rd) = ((int64_t)(sword_t)src1 * (uint64_t)src2) >> 32);
#endif
  INSTPAT("??????? ????? ????? 001 ????? 00000 11", lh, I, R(rd) = SEXT(Mr(src1 + imm, 2), 16));
  INSTPAT("??????? ????? ????? 101 ????? 00000 11", lhu, I, R(rd) = Mr(src1 + imm, 2));
  // 到现在还是看不懂fence的设计，目前简单起见，先保持为空
  // Base implementations ignore the reserved rd/rs1 fields of FENCE and
  // FENCE.I.  Keep the reference model aligned with the DUT for those legal
  // encodings instead of requiring rd=x0.
  /* A base implementation may conservatively execute every fm/pred/succ
   * encoding as a full fence.  This also implements FENCE.TSO with stronger
   * ordering and preserves forward compatibility with reserved fm values. */
  INSTPAT("??????? ????? ????? 000 ????? 0001111", fence, N, );
  INSTPAT("??????? ????? ????? 001 ????? 0001111", fence_i, N, );
  INSTPAT("??????? ????? ????? 000 ????? 00000 11", lb, I, R(rd) = SEXT(Mr(src1 + imm, 1), 8));
  INSTPAT("0000000 ????? ????? 010 ????? 01100 11", slt, R, R(rd) = ((sword_t)src1 < (sword_t)src2) ? 1 : 0);
  INSTPAT("??????? ????? ????? 010 ????? 00100 11", slti, I, R(rd) = ((sword_t)src1 < (sword_t)imm) ? 1 : 0);
  INSTPAT("0000000 00000 00000 000 00000 11100 11", ecall, N,
          {
            word_t cause = cpu.privilege == RISCV_PRIV_U ? 8 :
                           (cpu.privilege == RISCV_PRIV_S ? 9 : 11);
            s->dnpc = isa_raise_intr(cause, s->pc);
          });
  INSTPAT("??????? ????? ????? 001 ????? 11100 11", csrrw, N, {
    const word_t address = csr_address(s);
    if (!csr_priv_ok(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else if (csr_is_read_only(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      const word_t old = csr_read(address);
      csr_write(address, csr_rs1(s));
      R(rd) = old;
    }
  });
  INSTPAT("??????? ????? ????? 010 ????? 11100 11", csrrs, N, {
    const word_t address = csr_address(s);
    const bool write = BITS(s->isa.inst, 19, 15) != 0;
    if (!csr_priv_ok(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else if (write && csr_is_read_only(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      const word_t old = csr_read(address);
      if (write) csr_write(address, old | csr_rs1(s));
      R(rd) = old;
    }
  });
  INSTPAT("??????? ????? ????? 011 ????? 11100 11", csrrc, N, {
    const word_t address = csr_address(s);
    const bool write = BITS(s->isa.inst, 19, 15) != 0;
    if (!csr_priv_ok(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else if (write && csr_is_read_only(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      const word_t old = csr_read(address);
      if (write) csr_write(address, old & ~csr_rs1(s));
      R(rd) = old;
    }
  });
  INSTPAT("??????? ????? ????? 101 ????? 11100 11", csrrwi, N, {
    const word_t address = csr_address(s);
    if (!csr_priv_ok(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else if (csr_is_read_only(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      const word_t old = csr_read(address);
      csr_write(address, csr_zimm(s));
      R(rd) = old;
    }
  });
  INSTPAT("??????? ????? ????? 110 ????? 11100 11", csrrsi, N, {
    const word_t address = csr_address(s);
    const word_t source = csr_zimm(s);
    if (!csr_priv_ok(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else if (source != 0 && csr_is_read_only(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      const word_t old = csr_read(address);
      if (source != 0) csr_write(address, old | source);
      R(rd) = old;
    }
  });
  INSTPAT("??????? ????? ????? 111 ????? 11100 11", csrrci, N, {
    const word_t address = csr_address(s);
    const word_t source = csr_zimm(s);
    if (!csr_priv_ok(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else if (source != 0 && csr_is_read_only(address)) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      const word_t old = csr_read(address);
      if (source != 0) csr_write(address, old & ~source);
      R(rd) = old;
    }
  });
  INSTPAT("0011000 00010 00000 000 00000 11100 11", mret, N, {
    if (cpu.privilege != RISCV_PRIV_M) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      s->dnpc = cpu.mepc;
      mret_update_mstatus();
    }
  });
  INSTPAT("0001000 00010 00000 000 00000 11100 11", sret, N, {
    if (cpu.privilege == RISCV_PRIV_U ||
        (cpu.privilege == RISCV_PRIV_S && (cpu.mstatus & MSTATUS_TSR))) {
      s->dnpc = isa_raise_intr(2, s->pc);
    } else {
      s->dnpc = cpu.sepc;
      sret_update_mstatus();
    }
  });
  INSTPAT("0001001 ????? ????? 000 00000 11100 11", sfence_vma, N, {
    // S 模式在 mstatus.TVM=1 时执行 SFENCE.VMA 也是非法指令。
    if (cpu.privilege == RISCV_PRIV_U ||
        (cpu.privilege == RISCV_PRIV_S && (cpu.mstatus & MSTATUS_TVM))) {
      s->dnpc = isa_raise_intr(2, s->pc);
    }
  });
  INSTPAT("??????? ????? ????? ??? ????? ????? ??", inv, N, {
    if (cpu.mtvec == 0 && cpu.stvec == 0) {
      // 没有安装异常向量时，未实现指令必须让 NEMU 明确停机，
      // 而不是跳到 0 地址后产生与根因无关的访存错误。
      Log("非法或未实现的指令 " FMT_WORD "（inst = 0x%08x）",
          s->pc, s->isa.inst);
      invalid_inst(s->pc);
    } else {
      s->dnpc = isa_raise_intr(2, s->pc);
    }
  });
  INSTPAT_END();

  R(0) = 0; // reset $zero to 0

  return 0;
}

int isa_exec_once(Decode *s)
{
  nemu_jmp_buf jb;
  exception_jmp = &jb;
  exception_epc = s->pc;
  isa_trap_tval = 0;

  if (nemu_setjmp(jb) != 0) {
    exception_jmp = NULL;
    R(0) = 0;
    s->dnpc = isa_raise_intr(exception_cause, exception_epc);
    return 0;
  }

  s->isa.inst = inst_fetch(&s->snpc, 4);
  // 自己加的，snpc-pc就是指令长度
  RecordAInstruction(s->pc, s->isa.inst, s->snpc - s->pc);
  int ret = decode_exec(s);
  exception_jmp = NULL;
  return ret;
}
