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
  // mepc/sepc 的低位对齐取决于 IALIGN：RVC 下 bit1 可保存 2 字节对齐的
  // 返回地址，bit0 始终为 0；未启用 RVC 时低两位都必须清零。
  return value & ~(word_t)RISCV_EPC_ALIGN_MASK;
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
    // misa.MXL：RV32 为 1，RV64 为 2。扩展位为 I/M/A/C/S/U。
    // bit 0 = A，bit 2 = C；只有对应 Kconfig 打开时才向软件宣告。
    return MUXDEF(CONFIG_RV64,
                  ((word_t)2 << 62) | MUXDEF(CONFIG_RISCV_A, 1u << 0, 0u) |
                      MUXDEF(CONFIG_RVC, 1u << 2, 0u) |
                      (1u << 8) | (1u << 12) | (1u << 18) | (1u << 20),
                  (1u << 30) | MUXDEF(CONFIG_RISCV_A, 1u << 0, 0u) |
                      MUXDEF(CONFIG_RVC, 1u << 2, 0u) |
                      (1u << 8) | (1u << 12) | (1u << 18) | (1u << 20));
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

#ifdef CONFIG_RISCV_A
/*
 * RISC-V A 扩展（单 hart 参考实现）。
 *
 * NEMU 只模拟一个 hart，因此 reservation 只需要记录“最近一次 LR 的
 * 地址”。LR/SC 与 AMO 都要求自然对齐；非对齐访问按 store/AMO
 * address-misaligned（cause 6）处理。AMO 的读改写是解释器中的原子
 * 操作，天然不存在中间可见状态。
 *
 * AMO 的 funct5 编码直接作为下面的 op 值使用。
 */
enum {
  AMO_ADD   = 0x00,
  AMO_SWAP  = 0x01,
  AMO_XOR   = 0x04,
  AMO_OR    = 0x08,
  AMO_AND   = 0x0c,
  AMO_MIN   = 0x10,
  AMO_MAX   = 0x14,
  AMO_MINU  = 0x18,
  AMO_MAXU  = 0x1c,
};

static bool reservation_valid = false;
static vaddr_t reservation_addr = 0;

static inline bool amo_aligned(Decode *s, vaddr_t addr, int size)
{
  if ((addr & (vaddr_t)(size - 1)) == 0) {
    return true;
  }
  s->dnpc = isa_raise_intr(6, s->pc); // store/AMO address misaligned
  return false;
}

static void amo_exec(Decode *s, vaddr_t addr, word_t rhs, int rd, int size, int op)
{
  if (!amo_aligned(s, addr, size)) {
    return;
  }

  const word_t old = Mr(addr, size);
  const word_t a = (size == 4) ? (uint32_t)old : old;
  const word_t b = (size == 4) ? (uint32_t)rhs : rhs;
  word_t result;

  switch (op) {
    case AMO_ADD:  result = a + b; break;
    case AMO_SWAP: result = b; break;
    case AMO_XOR:  result = a ^ b; break;
    case AMO_OR:   result = a | b; break;
    case AMO_AND:  result = a & b; break;
    case AMO_MIN:
      result = (size == 4) ? ((int32_t)a < (int32_t)b ? a : b)
                           : ((sword_t)a < (sword_t)b ? a : b);
      break;
    case AMO_MAX:
      result = (size == 4) ? ((int32_t)a > (int32_t)b ? a : b)
                           : ((sword_t)a > (sword_t)b ? a : b);
      break;
    case AMO_MINU: result = (a < b) ? a : b; break;
    case AMO_MAXU: result = (a > b) ? a : b; break;
    default: panic("未知 AMO funct5 = 0x%x", op); result = 0;
  }

  Mw(addr, size, (size == 4) ? (uint32_t)result : result);
  // AMO 返回旧值；.W 结果按 bit31 符号扩展到 XLEN。
  R(rd) = (size == 4) ? sext32(old) : old;
  reservation_valid = false;
}

static void lr_exec(Decode *s, vaddr_t addr, int rd, int size)
{
  if (!amo_aligned(s, addr, size)) {
    return;
  }
  const word_t old = Mr(addr, size);
  R(rd) = (size == 4) ? sext32(old) : old;
  reservation_addr = addr;
  reservation_valid = true;
}

static void sc_exec(Decode *s, vaddr_t addr, word_t value, int rd, int size)
{
  if (!amo_aligned(s, addr, size)) {
    return;
  }
  if (reservation_valid && reservation_addr == addr) {
    Mw(addr, size, value);
    R(rd) = 0; // 成功
  } else {
    R(rd) = 1; // 失败，不写内存
  }
  reservation_valid = false;
}
#endif

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

/*
 * 取指/跳转目标对齐检查。
 *
 * IALIGN 由 C 扩展决定：未启用 RVC 时 PC 必须是 4 的倍数；启用后
 * 可以是 2 的倍数，32 位指令也可以从 2 mod 4 开始（低两位为 11 即
 * 表示 32 位）。分支/JAL 的立即数本身低位为 0，所以这里主要拦截
 * “无 C 扩展时跳到 2 mod 4”这种非法目标；JALR 则必须只清 bit0，
 * 不能像旧代码那样把 bit1 也清掉。
 */
static inline bool riscv_target_misaligned(vaddr_t target)
{
  return (target & RISCV_EPC_ALIGN_MASK) != 0;
}

static inline void riscv_raise_inst_misaligned(Decode *s, vaddr_t target)
{
  isa_trap_tval = target;
  s->dnpc = isa_raise_intr(0, s->pc);
}

/* 条件分支：只有真正跳转时才检查目标对齐；不跳转时目标非法无影响。 */
#define RV_BRANCH(s, offset, cond)                                         \
  do                                                                       \
  {                                                                        \
    vaddr_t _target = (s)->pc + (offset);                                  \
    if (cond)                                                              \
    {                                                                      \
      if (riscv_target_misaligned(_target))                                \
      {                                                                    \
        riscv_raise_inst_misaligned((s), _target);                         \
      }                                                                    \
      else                                                                 \
      {                                                                    \
        (s)->dnpc = _target;                                               \
        BTRACE_BRANCH((s), (offset), true);                                \
      }                                                                    \
    }                                                                      \
    else                                                                   \
    {                                                                      \
      BTRACE_BRANCH((s), (offset), false);                                 \
    }                                                                      \
  } while (0)

// ---------------------------------------------------------------------------
// RVC（压缩指令）解码
// ---------------------------------------------------------------------------

#ifdef CONFIG_RVC

static inline word_t rvc_imm_cj(uint16_t i)
{
  // C.J/C.JAL: imm[11|4|9:8|10|6|7|3:1|5]
  word_t imm = (BITS(i, 12, 12) << 11) | (BITS(i, 8, 8) << 10) |
               (BITS(i, 10, 9) << 8) | (BITS(i, 6, 6) << 7) |
               (BITS(i, 7, 7) << 6) | (BITS(i, 2, 2) << 5) |
               (BITS(i, 11, 11) << 4) | (BITS(i, 5, 3) << 1);
  return SEXT(imm, 12);
}

static inline word_t rvc_imm_cb(uint16_t i)
{
  // C.BEQZ/C.BNEZ: imm[8|4:3|7:6|2:1|5]
  word_t imm = (BITS(i, 12, 12) << 8) | (BITS(i, 11, 10) << 3) |
               (BITS(i, 6, 5) << 6) | (BITS(i, 4, 3) << 1) |
               (BITS(i, 2, 2) << 5);
  return SEXT(imm, 9);
}

static inline word_t rvc_imm_ci(uint16_t i)
{
  return SEXT((BITS(i, 12, 12) << 5) | BITS(i, 6, 2), 6);
}

static inline word_t rvc_imm_addi16sp(uint16_t i)
{
  // nzimm[9|4|6|8:7|5]
  word_t imm = (BITS(i, 12, 12) << 9) | (BITS(i, 6, 6) << 4) |
               (BITS(i, 5, 5) << 6) | (BITS(i, 4, 3) << 7) |
               (BITS(i, 2, 2) << 5);
  return SEXT(imm, 10);
}

static inline word_t rvc_imm_addi4spn(uint16_t i)
{
  // nzuimm[5:4|9:6|2|3]
  return (BITS(i, 12, 11) << 4) | (BITS(i, 10, 7) << 6) |
         (BITS(i, 6, 6) << 2) | (BITS(i, 5, 5) << 3);
}

static inline word_t rvc_uimm_cl(uint16_t i)
{
  // C.LW/C.SW: uimm[5:3|2|6]
  return (BITS(i, 12, 10) << 3) | (BITS(i, 6, 6) << 2) |
         (BITS(i, 5, 5) << 6);
}

static inline word_t rvc_uimm_cl_d(uint16_t i)
{
  // C.LD/C.SD: uimm[5:3|7:6]
  return (BITS(i, 12, 10) << 3) | (BITS(i, 6, 5) << 6);
}

static inline word_t rvc_uimm_lwsp(uint16_t i)
{
  // C.LWSP: uimm[5|4:2|7:6]
  return (BITS(i, 12, 12) << 5) | (BITS(i, 6, 4) << 2) |
         (BITS(i, 3, 2) << 6);
}

static inline word_t rvc_uimm_ldsp(uint16_t i)
{
  // C.LDSP: uimm[5|4:3|8:6]
  return (BITS(i, 12, 12) << 5) | (BITS(i, 6, 5) << 3) |
         (BITS(i, 4, 2) << 6);
}

static inline word_t rvc_uimm_swsp(uint16_t i)
{
  // C.SWSP: uimm[5:2|7:6]
  return (BITS(i, 12, 9) << 2) | (BITS(i, 8, 7) << 6);
}

static inline word_t rvc_uimm_sdsp(uint16_t i)
{
  // C.SDSP: uimm[5:3|8:6]
  return (BITS(i, 12, 10) << 3) | (BITS(i, 9, 7) << 6);
}

static inline word_t rv_load_w(Decode *s, vaddr_t addr)
{
  (void)s;
#ifdef CONFIG_RV64
  return sext32(Mr(addr, 4));
#else
  return Mr(addr, 4);
#endif
}

/*
 * 非法/保留的 C 编码必须按 16 位指令整体报告，不能把它和后面的字节
 * 拼成一条 32 位指令后静默执行。mtvec/stvec 都未安装时保持 NEMU 原有
 * 的“明确停机并打印现场”行为；否则按非法指令异常（cause 2）陷入，
 * mtval 写入真实的 16 位编码。
 */
static void rvc_illegal(Decode *s)
{
  isa_trap_tval = s->isa.inst;
  if (cpu.mtvec == 0 && cpu.stvec == 0)
  {
    Log("非法或未实现的 C 指令 " FMT_WORD "（inst = 0x%04x）",
        s->pc, (unsigned)(s->isa.inst & 0xffffu));
    invalid_inst(s->pc);
  }
  else
  {
    s->dnpc = isa_raise_intr(2, s->pc);
  }
}

#define RVC_ILLEGAL()        \
  do                         \
  {                          \
    rvc_illegal(s);          \
    R(0) = 0;                \
    return 0;                \
  } while (0)

static int decode_exec_c(Decode *s)
{
  const uint16_t i = (uint16_t)s->isa.inst;
  const int op = BITS(i, 1, 0);
  s->dnpc = s->snpc;

  const int rd = BITS(i, 11, 7);
  const int rs2 = BITS(i, 6, 2);
  const int rdp = 8 + (int)BITS(i, 4, 2);
  const int rs1p = 8 + (int)BITS(i, 9, 7);
  const int rs2p = 8 + (int)BITS(i, 4, 2);

  switch (op)
  {
  case 0: // Quadrant 0：寄存器压栈形式（rd'/rs1'/rs2' 为 x8~x15）
    switch (BITS(i, 15, 13))
    {
    case 0: {
      const word_t uimm = rvc_imm_addi4spn(i);
      if (uimm == 0)
      {
        RVC_ILLEGAL(); // 全零编码保留
      }
      R(rdp) = R(2) + uimm;
      break;
    }
    case 1:
      RVC_ILLEGAL(); // C.FLD：未实现 D 扩展
    case 2: {
      const vaddr_t addr = R(rs1p) + rvc_uimm_cl(i);
      R(rdp) = rv_load_w(s, addr);
      break;
    }
    case 3:
#ifdef CONFIG_RV64
      R(rdp) = Mr(R(rs1p) + rvc_uimm_cl_d(i), 8); // C.LD
      break;
#else
      RVC_ILLEGAL(); // RV32 的 C.FLW 需要 F 扩展
#endif
    case 4:
      RVC_ILLEGAL(); // 保留（Zcb 等扩展未实现）
    case 5:
      RVC_ILLEGAL(); // C.FSD：未实现 D 扩展
    case 6:
      Mw(R(rs1p) + rvc_uimm_cl(i), 4, R(rs2p)); // C.SW
      break;
    case 7:
#ifdef CONFIG_RV64
      Mw(R(rs1p) + rvc_uimm_cl_d(i), 8, R(rs2p)); // C.SD
      break;
#else
      RVC_ILLEGAL(); // RV32 的 C.FSW 需要 F 扩展
#endif
    }
    break;

  case 1: // Quadrant 1
    switch (BITS(i, 15, 13))
    {
    case 0: // C.NOP / C.ADDI（rd=0 为 HINT）
      R(rd) = R(rd) + rvc_imm_ci(i);
      break;
    case 1:
#ifdef CONFIG_RV64
      // RV64：该编码是 C.ADDIW，rd=0 保留
      if (rd == 0)
      {
        RVC_ILLEGAL();
      }
      R(rd) = sext32((uint32_t)R(rd) + (uint32_t)rvc_imm_ci(i));
#else
    {
      // RV32：C.JAL
      const word_t imm = rvc_imm_cj(i);
      const vaddr_t target = s->pc + imm;
      if (riscv_target_misaligned(target))
      {
        riscv_raise_inst_misaligned(s, target);
      }
      else
      {
        R(1) = s->pc + 2;
        s->dnpc = target;
        BTRACE_JAL(s, imm, 1);
#ifdef CONFIG_FTRACE
        FtraceOnCall(&GlobalFtraceState, s->pc, s->dnpc, s->pc + 2);
#endif
      }
    }
#endif
    break;
    case 2: // C.LI（rd=0 为 HINT）
      R(rd) = rvc_imm_ci(i);
      break;
    case 3:
      if (rd == 2)
      {
        // C.ADDI16SP：nzimm=0 保留
        const word_t imm = rvc_imm_addi16sp(i);
        if (imm == 0)
        {
          RVC_ILLEGAL();
        }
        R(2) = R(2) + imm;
      }
      else
      {
        // C.LUI：imm=0 保留；rd=0 为 HINT
        const word_t imm = (word_t)SEXT((BITS(i, 12, 12) << 5) | BITS(i, 6, 2), 6) << 12;
        if (imm == 0)
        {
          RVC_ILLEGAL();
        }
        R(rd) = imm;
      }
      break;
    case 4: {
      const int funct2 = BITS(i, 11, 10);
      if (funct2 == 0 || funct2 == 1)
      {
        // C.SRLI / C.SRAI。RV32 的 shamt[5]（bit12）必须为 0；
        // RV64 允许 0~63。shamt=0 属于 HINT，按无操作处理。
        int shamt = BITS(i, 6, 2);
#ifndef CONFIG_RV64
        if (BITS(i, 12, 12) != 0)
        {
          RVC_ILLEGAL();
        }
#else
        shamt |= (int)BITS(i, 12, 12) << 5;
#endif
        if (shamt != 0)
        {
          if (funct2 == 0)
          {
            R(rs1p) = R(rs1p) >> shamt;
          }
          else
          {
            R(rs1p) = (word_t)((sword_t)R(rs1p) >> shamt);
          }
        }
      }
      else if (funct2 == 2)
      {
        R(rs1p) = R(rs1p) & rvc_imm_ci(i); // C.ANDI
      }
      else
      {
        // funct2=11 是寄存器-寄存器组：bits[6:5] 选操作，bits[4:2]
        // 就是 rs2'，不是保留位。bit12=1 在 RV32 上没有定义；
        // RV64 只定义了 C.SUBW/C.ADDW 两种。
        const int funct = BITS(i, 6, 5);
        if (BITS(i, 12, 12) == 0)
        {
          switch (funct)
          {
          case 0: R(rs1p) = R(rs1p) - R(rs2p); break; // C.SUB
          case 1: R(rs1p) = R(rs1p) ^ R(rs2p); break; // C.XOR
          case 2: R(rs1p) = R(rs1p) | R(rs2p); break; // C.OR
          case 3: R(rs1p) = R(rs1p) & R(rs2p); break; // C.AND
          }
        }
        else
        {
#ifdef CONFIG_RV64
          if (funct == 0)
          {
            R(rs1p) = sext32((uint32_t)R(rs1p) - (uint32_t)R(rs2p)); // C.SUBW
          }
          else if (funct == 1)
          {
            R(rs1p) = sext32((uint32_t)R(rs1p) + (uint32_t)R(rs2p)); // C.ADDW
          }
          else
          {
            RVC_ILLEGAL();
          }
#else
          RVC_ILLEGAL(); // RV32 没有 C.SUBW/C.ADDW
#endif
        }
      }
      break;
    }
    case 5: {
      // C.J
      const word_t imm = rvc_imm_cj(i);
      const vaddr_t target = s->pc + imm;
      if (riscv_target_misaligned(target))
      {
        riscv_raise_inst_misaligned(s, target);
      }
      else
      {
        s->dnpc = target;
        BTRACE_JAL(s, imm, 0);
      }
      break;
    }
    case 6: // C.BEQZ
      RV_BRANCH(s, rvc_imm_cb(i), R(rs1p) == 0);
      break;
    case 7: // C.BNEZ
      RV_BRANCH(s, rvc_imm_cb(i), R(rs1p) != 0);
      break;
    }
    break;

  case 2: // Quadrant 2
    switch (BITS(i, 15, 13))
    {
    case 0: { // C.SLLI（rd=0 为 HINT）
      int shamt = BITS(i, 6, 2);
#ifndef CONFIG_RV64
      if (BITS(i, 12, 12) != 0)
      {
        RVC_ILLEGAL();
      }
#else
      shamt |= (int)BITS(i, 12, 12) << 5;
#endif
      if (shamt != 0)
      {
        R(rd) = R(rd) << shamt;
      }
      break;
    }
    case 1:
      RVC_ILLEGAL(); // C.FLDSP：未实现 D 扩展
    case 2: { // C.LWSP：rd=0 保留
      if (rd == 0)
      {
        RVC_ILLEGAL();
      }
      const vaddr_t addr = R(2) + rvc_uimm_lwsp(i);
      R(rd) = rv_load_w(s, addr);
      break;
    }
    case 3:
#ifdef CONFIG_RV64
      // C.LDSP：rd=0 保留
      if (rd == 0)
      {
        RVC_ILLEGAL();
      }
      R(rd) = Mr(R(2) + rvc_uimm_ldsp(i), 8);
      break;
#else
      RVC_ILLEGAL(); // RV32 的 C.FLWSP 需要 F 扩展
#endif
    case 4:
      if (BITS(i, 12, 12) == 0)
      {
        if (rs2 == 0)
        {
          // C.JR：rs1=0 保留
          if (rd == 0)
          {
            RVC_ILLEGAL();
          }
          const vaddr_t target = R(rd) & ~(vaddr_t)1;
          if (riscv_target_misaligned(target))
          {
            riscv_raise_inst_misaligned(s, target);
          }
          else
          {
            s->dnpc = target;
            BTRACE_JALR(s, rd, 0, 0);
#ifdef CONFIG_FTRACE
            if (rd == 1)
            {
              FtraceOnReturn(&GlobalFtraceState, s->pc, s->dnpc);
            }
#endif
          }
        }
        else
        {
          // C.MV（rd=0 为 HINT）
          R(rd) = R(rs2);
        }
      }
      else
      {
        if (rs2 == 0)
        {
          if (rd == 0)
          {
            // C.EBREAK
            s->dnpc = isa_raise_intr(3, s->pc);
          }
          else
          {
            // C.JALR：rs1=rd，rs1=0 保留
            const vaddr_t target = R(rd) & ~(vaddr_t)1;
            if (riscv_target_misaligned(target))
            {
              riscv_raise_inst_misaligned(s, target);
            }
            else
            {
              R(1) = s->pc + 2;
              s->dnpc = target;
              BTRACE_JALR(s, rd, 1, 0);
#ifdef CONFIG_FTRACE
              FtraceOnCall(&GlobalFtraceState, s->pc, s->dnpc, s->pc + 2);
#endif
            }
          }
        }
        else
        {
          // C.ADD（rd=0 为 HINT）
          R(rd) = R(rd) + R(rs2);
        }
      }
      break;
    case 6: // C.SWSP
      Mw(R(2) + rvc_uimm_swsp(i), 4, R(rs2));
      break;
    case 7:
#ifdef CONFIG_RV64
      Mw(R(2) + rvc_uimm_sdsp(i), 8, R(rs2)); // C.SDSP
      break;
#else
      RVC_ILLEGAL(); // RV32 的 C.FSWSP 需要 F 扩展
#endif
    }
    break;

  default:
    // 低两位为 11 的不是 C 指令，正常取指流程不会走到这里。
    RVC_ILLEGAL();
  }

  R(0) = 0;
  return 0;
}


#endif // CONFIG_RVC

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
    const vaddr_t target = s->pc + imm;
    if (riscv_target_misaligned(target)) {
      riscv_raise_inst_misaligned(s, target);
    } else {
      R(rd) = s->pc + 4;
      BTRACE_JAL(s, imm, rd);
#ifdef CONFIG_FTRACE
      // 标准 RISC-V ABI: 只有 jal ra, offset (rd == x1) 才是函数调用
      if (rd == 1)
      {
        FtraceOnCall(&GlobalFtraceState, s->pc, s->dnpc, s->pc + 4);
      }
#endif
    }
  });
  INSTPAT("??????? ????? ????? 010 ????? 01000 11", sw, S, Mw(src1 + imm, 4, src2));
  INSTPAT("??????? ????? ????? 000 ????? 11001 11", jalr, I, {
    const vaddr_t target = (src1 + imm) & ~(vaddr_t)1;
    if (riscv_target_misaligned(target)) {
      // 目标非法时指令不退休，目标寄存器不应被写入。
      riscv_raise_inst_misaligned(s, target);
    } else {
      R(rd) = s->pc + 4;
      s->dnpc = target;
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
        FtraceOnCall(&GlobalFtraceState, s->pc, s->dnpc, s->pc + 4);
      }
#endif
    }
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
  INSTPAT("??????? ????? ????? 000 ????? 11000 11", beq, B, RV_BRANCH(s, imm, src1 == src2));
  INSTPAT("??????? ????? ????? 001 ????? 11000 11", bne, B, RV_BRANCH(s, imm, src1 != src2));
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
  INSTPAT("??????? ????? ????? 101 ????? 11000 11", bge, B, RV_BRANCH(s, imm, (sword_t)src1 >= (sword_t)src2));
  INSTPAT("??????? ????? ????? 100 ????? 11000 11", blt, B, RV_BRANCH(s, imm, (sword_t)src1 < (sword_t)src2));
  INSTPAT("??????? ????? ????? ??? ????? 01101 11", lui, U, R(rd) = imm);
  INSTPAT("??????? ????? ????? 111 ????? 11000 11", bgeu, B, RV_BRANCH(s, imm, src1 >= src2));
  INSTPAT("??????? ????? ????? 110 ????? 11000 11", bltu, B, RV_BRANCH(s, imm, src1 < src2));
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
#ifdef CONFIG_RISCV_A
  /*
   * A 扩展：opcode 0101111，funct3=010(.W)/011(.D)。
   * aq/rl 在单 hart 解释器中按完整顺序执行，无需额外动作。
   */
  INSTPAT("00010 ?? 00000 ????? 010 ????? 01011 11", lr_w, R,
          lr_exec(s, src1, rd, 4));
  INSTPAT("00011 ?? ????? ????? 010 ????? 01011 11", sc_w, R,
          sc_exec(s, src1, src2, rd, 4));
  INSTPAT("00000 ?? ????? ????? 010 ????? 01011 11", amoadd_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_ADD));
  INSTPAT("00001 ?? ????? ????? 010 ????? 01011 11", amoswap_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_SWAP));
  INSTPAT("00100 ?? ????? ????? 010 ????? 01011 11", amoxor_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_XOR));
  INSTPAT("01000 ?? ????? ????? 010 ????? 01011 11", amoor_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_OR));
  INSTPAT("01100 ?? ????? ????? 010 ????? 01011 11", amoand_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_AND));
  INSTPAT("10000 ?? ????? ????? 010 ????? 01011 11", amomin_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_MIN));
  INSTPAT("10100 ?? ????? ????? 010 ????? 01011 11", amomax_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_MAX));
  INSTPAT("11000 ?? ????? ????? 010 ????? 01011 11", amominu_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_MINU));
  INSTPAT("11100 ?? ????? ????? 010 ????? 01011 11", amomaxu_w, R,
          amo_exec(s, src1, src2, rd, 4, AMO_MAXU));
#ifdef CONFIG_RV64
  INSTPAT("00010 ?? 00000 ????? 011 ????? 01011 11", lr_d, R,
          lr_exec(s, src1, rd, 8));
  INSTPAT("00011 ?? ????? ????? 011 ????? 01011 11", sc_d, R,
          sc_exec(s, src1, src2, rd, 8));
  INSTPAT("00000 ?? ????? ????? 011 ????? 01011 11", amoadd_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_ADD));
  INSTPAT("00001 ?? ????? ????? 011 ????? 01011 11", amoswap_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_SWAP));
  INSTPAT("00100 ?? ????? ????? 011 ????? 01011 11", amoxor_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_XOR));
  INSTPAT("01000 ?? ????? ????? 011 ????? 01011 11", amoor_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_OR));
  INSTPAT("01100 ?? ????? ????? 011 ????? 01011 11", amoand_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_AND));
  INSTPAT("10000 ?? ????? ????? 011 ????? 01011 11", amomin_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_MIN));
  INSTPAT("10100 ?? ????? ????? 011 ????? 01011 11", amomax_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_MAX));
  INSTPAT("11000 ?? ????? ????? 011 ????? 01011 11", amominu_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_MINU));
  INSTPAT("11100 ?? ????? ????? 011 ????? 01011 11", amomaxu_d, R,
          amo_exec(s, src1, src2, rd, 8, AMO_MAXU));
#endif
#endif
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
    isa_trap_tval = s->isa.inst;
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

  /*
   * 可变长度取指：
   * - 先取低 16 位。低两位不是 11 时就是一条 RVC 指令，PC 前进 2。
   * - 低两位为 11 表示 32 位指令，再单独取高 16 位并拼装。
   *
   * 两半分别走一次 vaddr_ifetch，好处是：
   * 1. 每一半都单独做 MMU 转换/PMP 检查，不会把跨页访问误当成一次
   *    连续物理读；
   * 2. 16 位指令正好位于页尾/内存边界时也只访问合法的 2 个字节；
   * 3. RVC 允许 32 位指令从任意 16 位边界开始，因此它可能跨越页边界；
   *    分别取两半后，每一半都能落在自己的页里，页错误也能报告正确的
   *    半字地址。
   */
  s->snpc = s->pc;
#ifdef CONFIG_RVC
  if ((s->pc & 1) != 0) {
    // IALIGN=16 仍然要求 PC 至少 2 字节对齐。
    exception_jmp = NULL;
    isa_trap_tval = s->pc;
    s->dnpc = isa_raise_intr(0, s->pc);
    return 0;
  }

  uint32_t lo = inst_fetch(&s->snpc, 2);
  if ((lo & 3u) != 3u) {
    s->isa.inst = lo;
    s->isa.ilen = 2;
  } else {
    // C 扩展下 32 位指令只要求 16 位对齐（低两位为 11 即表明是 32 位），
    // 允许出现在 2 mod 4 的地址，也允许跨页。
    uint32_t hi = inst_fetch(&s->snpc, 2);
    s->isa.inst = lo | (hi << 16);
    s->isa.ilen = 4;
  }
#else
  // 未启用 C 扩展时 IALIGN=32，保持原有“一次取 4 字节”的参考行为。
  if ((s->pc & 3u) != 0) {
    exception_jmp = NULL;
    isa_trap_tval = s->pc;
    s->dnpc = isa_raise_intr(0, s->pc);
    return 0;
  }
  s->isa.inst = inst_fetch(&s->snpc, 4);
  s->isa.ilen = 4;
#endif

  // 自己加的，snpc-pc 就是真实指令长度（16 位或 32 位）。
  RecordAInstruction(s->pc, s->isa.inst, s->isa.ilen);
#ifdef CONFIG_RVC
  int ret = (s->isa.ilen == 2) ? decode_exec_c(s) : decode_exec(s);
#else
  int ret = decode_exec(s);
#endif
  exception_jmp = NULL;
  return ret;
}
