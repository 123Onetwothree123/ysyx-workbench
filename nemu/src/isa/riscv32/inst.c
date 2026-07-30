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

#define R(i) gpr(i)
#define Mr vaddr_read
#define Mw vaddr_write

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

  INSTPAT("0000000 00001 00000 000 00000 11100 11", ebreak, N, NEMUTRAP(s->pc, R(10))); // R(10) is $a0
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
  INSTPAT("??????? ????? ????? 010 ????? 00000 11", lw, I, R(rd) = Mr(src1 + imm, 4));
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
  INSTPAT("0000000 ????? ????? 001 ????? 01100 11", sll, R, R(rd) = src1 << src2);
  INSTPAT("0000000 ????? ????? 101 ????? 01100 11", srl, R, R(rd) = src1 >> src2);
  INSTPAT("0100000 ????? ????? 101 ????? 01100 11", sra, R, R(rd) = (sword_t)src1 >> src2);
  INSTPAT("0000000 ????? ????? 101 ????? 00100 11", srli, I, R(rd) = src1 >> (imm & 0x1F));
  INSTPAT("0100000 ????? ????? 101 ????? 00100 11", srai, I, R(rd) = (sword_t)src1 >> (imm & 0x1F));
  INSTPAT("0000000 ????? ????? 001 ????? 00100 11", slli, I, R(rd) = src1 << (imm & 0x1F));
  INSTPAT("??????? ????? ????? 101 ????? 11000 11", bge, B, BTRACE_BRANCH(s, imm, (sword_t)src1 >= (sword_t)src2));
  INSTPAT("??????? ????? ????? 100 ????? 11000 11", blt, B, BTRACE_BRANCH(s, imm, (sword_t)src1 < (sword_t)src2));
  INSTPAT("??????? ????? ????? ??? ????? 01101 11", lui, U, R(rd) = imm);
  INSTPAT("??????? ????? ????? 111 ????? 11000 11", bgeu, B, BTRACE_BRANCH(s, imm, src1 >= src2));
  INSTPAT("??????? ????? ????? 110 ????? 11000 11", bltu, B, BTRACE_BRANCH(s, imm, src1 < src2));
  INSTPAT("0000001 ????? ????? 000 ????? 01100 11", mul, R, R(rd) = src1 * src2);
  INSTPAT("0000001 ????? ????? 100 ????? 01100 11", div, R, sword_t s1 = (sword_t)src1; sword_t s2 = (sword_t)src2; if (src2 == 0) { R(rd) = ~(word_t)0; } else if (s1 == INT32_MIN && s2 == -1) { R(rd) = (word_t)s1; } else { R(rd) = (word_t)(s1 / s2); });
  INSTPAT("0000001 ????? ????? 110 ????? 01100 11", rem, R, sword_t s1 = (sword_t)src1; sword_t s2 = (sword_t)src2; if (src2 == 0) { R(rd) = src1; } else if (s1 == INT32_MIN && s2 == -1) { R(rd) = 0; } else { R(rd) = (word_t)(s1 % s2); });
  INSTPAT("0000001 ????? ????? 111 ????? 01100 11", remu, R, if (src2 == 0) { R(rd) = src1; } else { R(rd) = src1 % src2; });
  INSTPAT("??????? ????? ????? 001 ????? 00000 11", lh, I, R(rd) = SEXT(Mr(src1 + imm, 2), 16));
  INSTPAT("??????? ????? ????? 101 ????? 00000 11", lhu, I, R(rd) = Mr(src1 + imm, 2));
  INSTPAT("0000001 ????? ????? 001 ????? 01100 11", mulh, R, R(rd) = ((int64_t)(sword_t)src1 * (int64_t)(sword_t)src2) >> 32);
  INSTPAT("0000001 ????? ????? 101 ????? 01100 11", divu, R, if (src2 == 0) { R(rd) = ~(word_t)0; } else { R(rd) = src1 / src2; });
  INSTPAT("0000001 ????? ????? 011 ????? 01100 11", mulhu, R, R(rd) = ((uint64_t)src1 * (uint64_t)src2) >> 32);
  // 到现在还是看不懂fence的设计，目前简单起见，先保持为空
  INSTPAT("??????? ????? ????? 000 00000 0001111", fence, N, );
  INSTPAT("??????? ????? ????? 001 00000 0001111", fence_i, N, );
  INSTPAT("??????? ????? ????? 000 ????? 00000 11", lb, I, R(rd) = SEXT(Mr(src1 + imm, 1), 8));
  INSTPAT("0000000 ????? ????? 010 ????? 01100 11", slt, R, R(rd) = ((sword_t)src1 < (sword_t)src2) ? 1 : 0);
  INSTPAT("??????? ????? ????? 010 ????? 00100 11", slti, I, R(rd) = ((sword_t)src1 < (sword_t)imm) ? 1 : 0);
  INSTPAT("0000001 ????? ????? 010 ????? 01100 11", mulhsu, R, R(rd) = ((int64_t)(sword_t)src1 * (uint64_t)src2) >> 32);
  INSTPAT("0000000 00000 00000 000 00000 11100 11", ecall, N, s->dnpc = isa_raise_intr(11, s->pc));
  INSTPAT("??????? ????? ????? 001 ????? 11100 11", csrrw, I, {
    word_t t = 0; // 临时保存的
    switch (imm)
    {
    case 0x341:
      t = cpu.mepc;
      cpu.mepc = src1;
      break;
    case 0x342:
      t = cpu.mcause;
      cpu.mcause = src1;
      break;
    case 0x300:
      t = cpu.mstatus;
      cpu.mstatus = src1;
      break;
    case 0x305:
      t = cpu.mtvec;
      cpu.mtvec = src1;
      break;
    default:
      panic("Unknown CSR 0x%x", imm);
      break;
    }
    R(rd) = t;
  });
  INSTPAT("??????? ????? ????? 010 ????? 11100 11", csrrs, I, {
    word_t t = 0;
    switch (imm)
    {
    case 0x341:
      t = cpu.mepc;
      cpu.mepc = t | src1;
      break;
    case 0x342:
      t = cpu.mcause;
      cpu.mcause = t | src1;
      break;
    case 0x300:
      t = cpu.mstatus;
      cpu.mstatus = t | src1;
      break;
    case 0x305:
      t = cpu.mtvec;
      cpu.mtvec = t | src1;
      break;
    default:
      panic("Unknown CSR 0x%x", imm);
      break;
    }
    R(rd) = t;
  });
  INSTPAT("0011000 00010 00000 000 00000 11100 11", mret, N, s->dnpc = cpu.mepc);
  INSTPAT("??????? ????? ????? ??? ????? ????? ??", inv, N, INV(s->pc));
  INSTPAT_END();

  R(0) = 0; // reset $zero to 0

  return 0;
}

int isa_exec_once(Decode *s)
{
  s->isa.inst = inst_fetch(&s->snpc, 4);
  // 自己加的，snpc-pc就是指令长度
  RecordAInstruction(s->pc, s->isa.inst, s->snpc - s->pc);
  return decode_exec(s);
}
