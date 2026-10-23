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

/*
 * RISC-V F/D 扩展的参考实现。
 *
 * 浮点算术、舍入、NaN 传播和异常标志全部交给 tools/softfloat 的
 * Berkeley SoftFloat Release 3e（RISCV 特殊化），绝不直接使用宿主
 * float/double 做运算。本文件只负责：
 *   - RISC-V 指令解码、f 寄存器 NaN boxing/unboxing；
 *   - fcsr.frm -> softfloat_roundingMode，softfloat flags -> fflags；
 *   - mstatus.FS 状态维护、浮点 load/store 对齐与异常陷入；
 *   - FMIN/FMAX、FCLASS、FMV/FSGNJ 等不直接对应单个 SoftFloat 函数的语义。
 */

#include <isa.h>
#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <memory/vaddr.h>
#include <utils.h>
#include "local-include/reg.h"
#include "local-include/fpu.h"

#ifdef CONFIG_RISCV_F

#include <softfloat.h>

/* RISC-V 规定 FLEN=64 时单精度值必须 NaN-boxed。 */
#define FP32_SIGN 0x80000000u
#define FP64_SIGN UINT64_C(0x8000000000000000)
#define FP32_QNAN 0x7fc00000u
#define FP64_QNAN UINT64_C(0x7ff8000000000000)

static inline uint32_t fp32_bits(float32_t f)
{
  union { uint32_t ui; float32_t f; } u;
  u.f = f;
  return u.ui;
}

static inline float32_t fp32_value(uint32_t ui)
{
  union { uint32_t ui; float32_t f; } u;
  u.ui = ui;
  return u.f;
}

static inline uint64_t fp64_bits(float64_t f)
{
  union { uint64_t ui; float64_t f; } u;
  u.f = f;
  return u.ui;
}

static inline float64_t fp64_value(uint64_t ui)
{
  union { uint64_t ui; float64_t f; } u;
  u.ui = ui;
  return u.f;
}

/* ---------- NaN boxing ---------- */

static inline uint64_t fp32_box(uint32_t value)
{
  return UINT64_C(0xffffffff00000000) | value;
}

static inline uint32_t fp32_unbox(uint64_t value)
{
  // 高 32 位不是全 1 时按架构规定视为 canonical NaN。
  return ((uint32_t)(value >> 32) == 0xffffffffu)
             ? (uint32_t)value
             : FP32_QNAN;
}

static inline bool fp32_is_nan(uint32_t value)
{
  return (value & 0x7f800000u) == 0x7f800000u &&
         (value & 0x007fffffu) != 0;
}

static inline bool fp32_is_snan(uint32_t value)
{
  return fp32_is_nan(value) && (value & 0x00400000u) == 0;
}

static inline bool fp64_is_nan(uint64_t value)
{
  return (value & UINT64_C(0x7ff0000000000000)) ==
             UINT64_C(0x7ff0000000000000) &&
         (value & UINT64_C(0x000fffffffffffff)) != 0;
}

static inline bool fp64_is_snan(uint64_t value)
{
  return fp64_is_nan(value) &&
         (value & UINT64_C(0x0008000000000000)) == 0;
}

/* ---------- mstatus.FS / fflags ---------- */

static inline void fp_mark_dirty(void)
{
  cpu.mstatus = riscv_mstatus_warl(
      (cpu.mstatus & ~MSTATUS_FS_MASK) | MSTATUS_FS_DIRTY);
}

static void fp_illegal(Decode *s)
{
  isa_trap_tval = s->isa.inst;
  if (cpu.mtvec == 0 && cpu.stvec == 0) {
    Log("非法或未实现的浮点指令 " FMT_WORD "（inst = 0x%08x）",
        s->pc, s->isa.inst);
    invalid_inst(s->pc);
  } else {
    s->dnpc = isa_raise_intr(2, s->pc);
  }
}

/* 检查 mstatus.FS，并在指令成功完成时由各调用点置 Dirty。 */
static bool fp_require(Decode *s)
{
  if ((cpu.mstatus & MSTATUS_FS_MASK) == MSTATUS_FS_OFF) {
    fp_illegal(s);
    return false;
  }
  return true;
}

static inline void fp_flags_begin(void)
{
  /* RISC-V 要求 tininess 在舍入后检测，与 RISCV specializations 一致。 */
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  softfloat_exceptionFlags = 0;
}

static inline void fp_flags_commit(void)
{
  const uint_fast8_t flags = softfloat_exceptionFlags & 0x1fu;
  softfloat_exceptionFlags = 0;
  if (flags != 0) {
    cpu.fcsr = (cpu.fcsr & ~(word_t)0x1fu) |
               ((cpu.fcsr | flags) & 0x1fu);
    // fflags 改变也属于浮点状态变化。
    fp_mark_dirty();
  }
}

/* 读取并校验指令的 rm 字段；rm=7 使用 fcsr.frm，5/6/7 均非法。 */
static int fp_rounding(Decode *s, uint32_t inst)
{
  int rm = (int)BITS(inst, 14, 12);
  if (rm == 7) {
    rm = (int)BITS(cpu.fcsr, 7, 5);
  }
  if (rm > 4) {
    fp_illegal(s);
    return -1;
  }
  softfloat_roundingMode = (uint_fast8_t)rm;
  return rm;
}

/* ---------- 访存 ---------- */

static uint64_t fp_mem_read(vaddr_t addr, int size)
{
#ifdef CONFIG_RV64
  return (uint64_t)vaddr_read(addr, size);
#else
  if (size == 8) {
    // RV32 的 word_t 只有 32 位，D 扩展的 8 字节访问拆成两个对齐字。
    const uint64_t lo = (uint32_t)vaddr_read(addr, 4);
    const uint64_t hi = (uint32_t)vaddr_read(addr + 4, 4);
    return lo | (hi << 32);
  }
  return (uint64_t)vaddr_read(addr, size);
#endif
}

static void fp_mem_write(vaddr_t addr, int size, uint64_t value)
{
#ifdef CONFIG_RV64
  vaddr_write(addr, size, (word_t)value);
#else
  if (size == 8) {
    vaddr_write(addr, 4, (uint32_t)value);
    vaddr_write(addr + 4, 4, (uint32_t)(value >> 32));
    return;
  }
  vaddr_write(addr, size, (word_t)value);
#endif
}

void riscv_fp_load(Decode *s, vaddr_t addr, int size, int rd)
{
  if (!fp_require(s)) {
    return;
  }
  if (size == 8) {
#ifndef CONFIG_RISCV_D
    fp_illegal(s);
    return;
#endif
  }
  if ((addr & (vaddr_t)(size - 1)) != 0) {
    isa_trap_tval = addr;
    s->dnpc = isa_raise_intr(4, s->pc); // load address misaligned
    return;
  }

  const uint64_t value = fp_mem_read(addr, size);
  cpu.f[rd] = (size == 4) ? fp32_box((uint32_t)value) : value;
  fp_flags_begin();
  fp_flags_commit();
  fp_mark_dirty();
}

void riscv_fp_store(Decode *s, vaddr_t addr, int size, int rs2)
{
  if (!fp_require(s)) {
    return;
  }
  if (size == 8) {
#ifndef CONFIG_RISCV_D
    fp_illegal(s);
    return;
#endif
  }
  if ((addr & (vaddr_t)(size - 1)) != 0) {
    isa_trap_tval = addr;
    s->dnpc = isa_raise_intr(6, s->pc); // store/AMO address misaligned
    return;
  }

  // FSW/FSD 直接写寄存器低位原始位型，不经过 unboxing/NaN 规范化。
  fp_mem_write(addr, size, cpu.f[rs2]);
  fp_flags_begin();
  fp_flags_commit();
}

/* ---------- FMIN/FMAX/FCLASS ---------- */

static uint32_t fp32_minmax(uint32_t a, uint32_t b, bool is_max)
{
  const bool a_nan = fp32_is_nan(a);
  const bool b_nan = fp32_is_nan(b);

  if (a_nan && b_nan) {
    // 两个 NaN 时返回 canonical NaN；任一为 sNaN 仍需置 NV。
    if (fp32_is_snan(a) || fp32_is_snan(b)) {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return FP32_QNAN;
  }
  if (a_nan || b_nan) {
    if ((a_nan && fp32_is_snan(a)) || (b_nan && fp32_is_snan(b))) {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return a_nan ? b : a;
  }

  /*
   * 复用 SoftFloat 的比较以复用其对 -0/+0 的处理：
   * min 选择较小者；相等且 a 为 -0 时选 a；max 对称。
   */
  const bool take_a = is_max
      ? (f32_lt_quiet(fp32_value(b), fp32_value(a)) ||
         (f32_eq(fp32_value(b), fp32_value(a)) && (b & FP32_SIGN) != 0))
      : (f32_lt_quiet(fp32_value(a), fp32_value(b)) ||
         (f32_eq(fp32_value(a), fp32_value(b)) && (a & FP32_SIGN) != 0));
  return take_a ? a : b;
}

static uint64_t fp64_minmax(uint64_t a, uint64_t b, bool is_max)
{
  const bool a_nan = fp64_is_nan(a);
  const bool b_nan = fp64_is_nan(b);

  if (a_nan && b_nan) {
    if (fp64_is_snan(a) || fp64_is_snan(b)) {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return FP64_QNAN;
  }
  if (a_nan || b_nan) {
    if ((a_nan && fp64_is_snan(a)) || (b_nan && fp64_is_snan(b))) {
      softfloat_raiseFlags(softfloat_flag_invalid);
    }
    return a_nan ? b : a;
  }

  const bool take_a = is_max
      ? (f64_lt_quiet(fp64_value(b), fp64_value(a)) ||
         (f64_eq(fp64_value(b), fp64_value(a)) && (b & FP64_SIGN) != 0))
      : (f64_lt_quiet(fp64_value(a), fp64_value(b)) ||
         (f64_eq(fp64_value(a), fp64_value(b)) && (a & FP64_SIGN) != 0));
  return take_a ? a : b;
}

static uint32_t fp32_classify(uint32_t v)
{
  const bool sign = (v & FP32_SIGN) != 0;
  const bool exp_all = (v & 0x7f800000u) == 0x7f800000u;
  const bool exp_zero = (v & 0x7f800000u) == 0;
  const bool frac_zero = (v & 0x007fffffu) == 0;
  const bool nan = fp32_is_nan(v);
  const bool snan = fp32_is_snan(v);

  return (sign && exp_all && frac_zero) << 0 |
         (sign && !exp_all && !exp_zero) << 1 |
         (sign && exp_zero && !frac_zero) << 2 |
         (sign && exp_zero && frac_zero) << 3 |
         (!sign && exp_zero && frac_zero) << 4 |
         (!sign && exp_zero && !frac_zero) << 5 |
         (!sign && !exp_all && !exp_zero) << 6 |
         (!sign && exp_all && frac_zero) << 7 |
         (nan && snan) << 8 |
         (nan && !snan) << 9;
}

#ifdef CONFIG_RV64
static uint32_t fp64_classify(uint64_t v)
{
  const bool sign = (v & FP64_SIGN) != 0;
  const bool exp_all =
      (v & UINT64_C(0x7ff0000000000000)) == UINT64_C(0x7ff0000000000000);
  const bool exp_zero = (v & UINT64_C(0x7ff0000000000000)) == 0;
  const bool frac_zero = (v & UINT64_C(0x000fffffffffffff)) == 0;
  const bool nan = fp64_is_nan(v);
  const bool snan = fp64_is_snan(v);

  return (sign && exp_all && frac_zero) << 0 |
         (sign && !exp_all && !exp_zero) << 1 |
         (sign && exp_zero && !frac_zero) << 2 |
         (sign && exp_zero && frac_zero) << 3 |
         (!sign && exp_zero && frac_zero) << 4 |
         (!sign && exp_zero && !frac_zero) << 5 |
         (!sign && !exp_all && !exp_zero) << 6 |
         (!sign && exp_all && frac_zero) << 7 |
         (nan && snan) << 8 |
         (nan && !snan) << 9;
}
#endif

/* ---------- 指令实现 ---------- */

static inline word_t fp_sext32(word_t value)
{
  return (word_t)(sword_t)(int32_t)(uint32_t)value;
}

static inline bool fp_d_enabled(void)
{
#ifdef CONFIG_RISCV_D
  return true;
#else
  return false;
#endif
}

static bool fp_decode_load(Decode *s, uint32_t inst)
{
  const int funct3 = (int)BITS(inst, 14, 12);
  if (funct3 != 2 && funct3 != 3) {
    fp_illegal(s);
    return true;
  }
  if (funct3 == 3 && !fp_d_enabled()) {
    fp_illegal(s);
    return true;
  }
  const int rd = (int)BITS(inst, 11, 7);
  const int rs1 = (int)BITS(inst, 19, 15);
  const sword_t imm = SEXT(BITS(inst, 31, 20), 12);
  riscv_fp_load(s, cpu.gpr[rs1] + (word_t)imm, funct3 == 3 ? 8 : 4, rd);
  return true;
}

static bool fp_decode_store(Decode *s, uint32_t inst)
{
  const int funct3 = (int)BITS(inst, 14, 12);
  if (funct3 != 2 && funct3 != 3) {
    fp_illegal(s);
    return true;
  }
  if (funct3 == 3 && !fp_d_enabled()) {
    fp_illegal(s);
    return true;
  }
  const int rs1 = (int)BITS(inst, 19, 15);
  const int rs2 = (int)BITS(inst, 24, 20);
  const sword_t imm = (SEXT(BITS(inst, 31, 25), 7) << 5) |
                      BITS(inst, 11, 7);
  riscv_fp_store(s, cpu.gpr[rs1] + (word_t)imm,
                 funct3 == 3 ? 8 : 4, rs2);
  return true;
}

static bool fp_decode_fma(Decode *s, uint32_t inst, int opcode)
{
  const int fmt = (int)BITS(inst, 26, 25);
  const int rd = (int)BITS(inst, 11, 7);
  const int rs1 = (int)BITS(inst, 19, 15);
  const int rs2 = (int)BITS(inst, 24, 20);
  const int rs3 = (int)BITS(inst, 31, 27);

  if (fmt != 0 && fmt != 1) {
    fp_illegal(s);
    return true;
  }
  if (fmt == 1 && !fp_d_enabled()) {
    fp_illegal(s);
    return true;
  }
  if (!fp_require(s)) {
    return true;
  }
  if (fp_rounding(s, inst) < 0) {
    return true;
  }

  if (fmt == 0) {
    uint32_t a = fp32_unbox(cpu.f[rs1]);
    uint32_t b = fp32_unbox(cpu.f[rs2]);
    uint32_t c = fp32_unbox(cpu.f[rs3]);
    // FNMSUB = -(a*b)+c；FNMADD = -(a*b)-c。对输入位翻转符号是精确的。
    if (opcode == 0x4b || opcode == 0x4f) a ^= FP32_SIGN;
    if (opcode == 0x47 || opcode == 0x4f) c ^= FP32_SIGN;
    fp_flags_begin();
    const uint32_t z = fp32_bits(
        f32_mulAdd(fp32_value(a), fp32_value(b), fp32_value(c)));
    cpu.f[rd] = fp32_box(z);
    fp_flags_commit();
    fp_mark_dirty();
  } else {
    uint64_t a = cpu.f[rs1];
    uint64_t b = cpu.f[rs2];
    uint64_t c = cpu.f[rs3];
    if (opcode == 0x4b || opcode == 0x4f) a ^= FP64_SIGN;
    if (opcode == 0x47 || opcode == 0x4f) c ^= FP64_SIGN;
    fp_flags_begin();
    const uint64_t z = fp64_bits(
        f64_mulAdd(fp64_value(a), fp64_value(b), fp64_value(c)));
    cpu.f[rd] = z;
    fp_flags_commit();
    fp_mark_dirty();
  }
  return true;
}

/* OP-FP：funct7 决定操作，rm/funct3 的含义按具体指令解释。 */
static bool fp_decode_op(Decode *s, uint32_t inst)
{
  const int funct7 = (int)BITS(inst, 31, 25);
  const int funct3 = (int)BITS(inst, 14, 12);
  const int rd = (int)BITS(inst, 11, 7);
  const int rs1 = (int)BITS(inst, 19, 15);
  const int rs2 = (int)BITS(inst, 24, 20);

  /* 先排除需要 D 但 D 未开启的编码，保证会陷入而不是误当 F 执行。 */
  switch (funct7) {
  case 0x01: case 0x05: case 0x09: case 0x0d: // FADD/FSUB/FMUL/FDIV.D
  case 0x11: case 0x15: case 0x1d:             // FSGNJ/FMIN/FMAX.D
  case 0x20:                                   // FCVT.S.D
  case 0x21:                                   // FCVT.D.S
  case 0x2d:                                   // FSQRT.D
  case 0x51: case 0x61:                        // 比较/转整数 .D
  case 0x69:                                   // 整数转 .D
  case 0x71: case 0x79:                        // FMV.X.D/FCLASS.D、FMV.D.X
    if (!fp_d_enabled()) {
      fp_illegal(s);
      return true;
    }
    break;
  default:
    break;
  }

  if (!fp_require(s)) {
    return true;
  }

  switch (funct7) {
  /* ---------- 单精度算术 ---------- */
  case 0x00: case 0x04: case 0x08: case 0x0c: // FADD/FSUB/FMUL/FDIV.S
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    {
      const uint32_t a = fp32_unbox(cpu.f[rs1]);
      const uint32_t b = fp32_unbox(cpu.f[rs2]);
      float32_t z;
      if (funct7 == 0x00) z = f32_add(fp32_value(a), fp32_value(b));
      else if (funct7 == 0x04) z = f32_sub(fp32_value(a), fp32_value(b));
      else if (funct7 == 0x08) z = f32_mul(fp32_value(a), fp32_value(b));
      else z = f32_div(fp32_value(a), fp32_value(b));
      cpu.f[rd] = fp32_box(fp32_bits(z));
    }
    fp_flags_commit();
    fp_mark_dirty();
    return true;

  /* ---------- 双精度算术 ---------- */
  case 0x01: case 0x05: case 0x09: case 0x0d:
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    {
      const uint64_t a = cpu.f[rs1];
      const uint64_t b = cpu.f[rs2];
      float64_t z;
      if (funct7 == 0x01) z = f64_add(fp64_value(a), fp64_value(b));
      else if (funct7 == 0x05) z = f64_sub(fp64_value(a), fp64_value(b));
      else if (funct7 == 0x09) z = f64_mul(fp64_value(a), fp64_value(b));
      else z = f64_div(fp64_value(a), fp64_value(b));
      cpu.f[rd] = fp64_bits(z);
    }
    fp_flags_commit();
    fp_mark_dirty();
    return true;

  /* ---------- FSQRT ---------- */
  case 0x2c: case 0x2d:
    if (rs2 != 0) { fp_illegal(s); return true; }
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    if (funct7 == 0x2c) {
      const uint32_t a = fp32_unbox(cpu.f[rs1]);
      cpu.f[rd] = fp32_box(fp32_bits(f32_sqrt(fp32_value(a))));
    } else {
      cpu.f[rd] = fp64_bits(f64_sqrt(fp64_value(cpu.f[rs1])));
    }
    fp_flags_commit();
    fp_mark_dirty();
    return true;

  /* ---------- FSGNJ ---------- */
  case 0x10: case 0x11:
    if (funct3 > 2) { fp_illegal(s); return true; }
    fp_flags_begin();
    if (funct7 == 0x10) {
      const uint32_t a = fp32_unbox(cpu.f[rs1]);
      const uint32_t b = fp32_unbox(cpu.f[rs2]);
      uint32_t sign;
      if (funct3 == 0) sign = b & FP32_SIGN;
      else if (funct3 == 1) sign = (~b) & FP32_SIGN;
      else sign = (a ^ b) & FP32_SIGN;
      cpu.f[rd] = fp32_box((a & ~FP32_SIGN) | sign);
    } else {
      const uint64_t a = cpu.f[rs1];
      const uint64_t b = cpu.f[rs2];
      uint64_t sign;
      if (funct3 == 0) sign = b & FP64_SIGN;
      else if (funct3 == 1) sign = (~b) & FP64_SIGN;
      else sign = (a ^ b) & FP64_SIGN;
      cpu.f[rd] = (a & ~FP64_SIGN) | sign;
    }
    fp_flags_commit();
    fp_mark_dirty();
    return true;

  /* ---------- FMIN/FMAX ---------- */
  case 0x14: case 0x15:
    if (funct3 > 1) { fp_illegal(s); return true; }
    fp_flags_begin();
    if (funct7 == 0x14) {
      const uint32_t a = fp32_unbox(cpu.f[rs1]);
      const uint32_t b = fp32_unbox(cpu.f[rs2]);
      cpu.f[rd] = fp32_box(fp32_minmax(a, b, funct3 == 1));
    } else {
      cpu.f[rd] = fp64_minmax(cpu.f[rs1], cpu.f[rs2], funct3 == 1);
    }
    fp_flags_commit();
    fp_mark_dirty();
    return true;

  /* ---------- FEQ/FLT/FLE ---------- */
  case 0x50: case 0x51:
    if (funct3 > 2) { fp_illegal(s); return true; }
    fp_flags_begin();
    if (funct7 == 0x50) {
      const uint32_t a = fp32_unbox(cpu.f[rs1]);
      const uint32_t b = fp32_unbox(cpu.f[rs2]);
      if (funct3 == 2) gpr(rd) = f32_eq(fp32_value(a), fp32_value(b));
      else if (funct3 == 1) gpr(rd) = f32_lt(fp32_value(a), fp32_value(b));
      else gpr(rd) = f32_le(fp32_value(a), fp32_value(b));
    } else {
      const uint64_t a = cpu.f[rs1];
      const uint64_t b = cpu.f[rs2];
      if (funct3 == 2) gpr(rd) = f64_eq(fp64_value(a), fp64_value(b));
      else if (funct3 == 1) gpr(rd) = f64_lt(fp64_value(a), fp64_value(b));
      else gpr(rd) = f64_le(fp64_value(a), fp64_value(b));
    }
    fp_flags_commit();
    return true;

  /* ---------- FCVT.S.D / FCVT.D.S ---------- */
  case 0x20: // FCVT.S.D
    if (rs2 != 1) { fp_illegal(s); return true; }
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    cpu.f[rd] = fp32_box(fp32_bits(f64_to_f32(fp64_value(cpu.f[rs1]))));
    fp_flags_commit();
    fp_mark_dirty();
    return true;
  case 0x21: // FCVT.D.S
    if (rs2 != 0) { fp_illegal(s); return true; }
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    cpu.f[rd] = fp64_bits(f32_to_f64(
        fp32_value(fp32_unbox(cpu.f[rs1]))));
    fp_flags_commit();
    fp_mark_dirty();
    return true;

  /* ---------- FCVT.W[U]/L[U].S/D ---------- */
  case 0x60: case 0x61: { // .S / .D -> integer
    if (funct7 == 0x61 && !fp_d_enabled()) { fp_illegal(s); return true; }
    if (rs2 > MUXDEF(CONFIG_RV64, 3, 1)) { fp_illegal(s); return true; }
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    word_t result;
    if (funct7 == 0x60) {
      const uint32_t a = fp32_unbox(cpu.f[rs1]);
      switch (rs2) {
      case 0: result = fp_sext32(f32_to_i32(fp32_value(a), (uint_fast8_t)softfloat_roundingMode, true)); break;
      case 1: result = fp_sext32(f32_to_ui32(fp32_value(a), (uint_fast8_t)softfloat_roundingMode, true)); break;
      case 2: result = (word_t)f32_to_i64(fp32_value(a), (uint_fast8_t)softfloat_roundingMode, true); break;
      default: result = (word_t)f32_to_ui64(fp32_value(a), (uint_fast8_t)softfloat_roundingMode, true); break;
      }
    } else {
      const uint64_t a = cpu.f[rs1];
      switch (rs2) {
      case 0: result = fp_sext32(f64_to_i32(fp64_value(a), (uint_fast8_t)softfloat_roundingMode, true)); break;
      case 1: result = fp_sext32(f64_to_ui32(fp64_value(a), (uint_fast8_t)softfloat_roundingMode, true)); break;
      case 2: result = (word_t)f64_to_i64(fp64_value(a), (uint_fast8_t)softfloat_roundingMode, true); break;
      default: result = (word_t)f64_to_ui64(fp64_value(a), (uint_fast8_t)softfloat_roundingMode, true); break;
      }
    }
    gpr(rd) = result;
    fp_flags_commit();
    return true;
  }

  /* ---------- FCVT.S/D.W[U]/L[U] ---------- */
  case 0x68: case 0x69: { // integer -> .S / .D
    if (funct7 == 0x69 && !fp_d_enabled()) { fp_illegal(s); return true; }
    if (rs2 > MUXDEF(CONFIG_RV64, 3, 1)) { fp_illegal(s); return true; }
    if (fp_rounding(s, inst) < 0) return true;
    fp_flags_begin();
    if (funct7 == 0x68) {
      float32_t z;
      switch (rs2) {
      case 0: z = i32_to_f32((int32_t)gpr(rs1)); break;
      case 1: z = ui32_to_f32((uint32_t)gpr(rs1)); break;
      case 2: z = i64_to_f32((int64_t)gpr(rs1)); break;
      default: z = ui64_to_f32((uint64_t)gpr(rs1)); break;
      }
      cpu.f[rd] = fp32_box(fp32_bits(z));
    } else {
      float64_t z;
      switch (rs2) {
      case 0: z = i32_to_f64((int32_t)gpr(rs1)); break;
      case 1: z = ui32_to_f64((uint32_t)gpr(rs1)); break;
      case 2: z = i64_to_f64((int64_t)gpr(rs1)); break;
      default: z = ui64_to_f64((uint64_t)gpr(rs1)); break;
      }
      cpu.f[rd] = fp64_bits(z);
    }
    fp_flags_commit();
    fp_mark_dirty();
    return true;
  }

  /* ---------- FMV.X.W / FCLASS.S ---------- */
  case 0x70:
    if (rs2 != 0 || funct3 > 1) { fp_illegal(s); return true; }
    fp_flags_begin();
    if (funct3 == 0) {
      // FMV.X.W 原样搬运低 32 位，不做 NaN unboxing。
      gpr(rd) = (word_t)(uint32_t)cpu.f[rs1];
      gpr(rd) = fp_sext32(gpr(rd));
    } else {
      gpr(rd) = fp32_classify(fp32_unbox(cpu.f[rs1]));
    }
    fp_flags_commit();
    return true;

  /* ---------- FMV.X.D / FCLASS.D ---------- */
  case 0x71:
#ifdef CONFIG_RV64
    if (rs2 != 0 || funct3 > 1) { fp_illegal(s); return true; }
    fp_flags_begin();
    if (funct3 == 0) {
      gpr(rd) = (word_t)cpu.f[rs1];
    } else {
      gpr(rd) = fp64_classify(cpu.f[rs1]);
    }
    fp_flags_commit();
    return true;
#else
    fp_illegal(s);
    return true;
#endif

  /* ---------- FMV.W.X ---------- */
  case 0x78:
    if (rs2 != 0 || funct3 != 0) { fp_illegal(s); return true; }
    cpu.f[rd] = fp32_box((uint32_t)gpr(rs1));
    fp_mark_dirty();
    return true;

  /* ---------- FMV.D.X (RV64) ---------- */
  case 0x79:
#ifdef CONFIG_RV64
    if (rs2 != 0 || funct3 != 0) { fp_illegal(s); return true; }
    cpu.f[rd] = (uint64_t)gpr(rs1);
    fp_mark_dirty();
    return true;
#else
    fp_illegal(s);
    return true;
#endif

  default:
    fp_illegal(s);
    return true;
  }
}

bool riscv_fp_decode_exec(Decode *s)
{
  const uint32_t inst = s->isa.inst;
  switch (BITS(inst, 6, 0)) {
  case 0x07: return fp_decode_load(s, inst);   // LOAD-FP
  case 0x27: return fp_decode_store(s, inst);  // STORE-FP
  case 0x43: case 0x47: case 0x4b: case 0x4f:  // FMADD/FMSUB/FNMSUB/FNMADD
    return fp_decode_fma(s, inst, (int)BITS(inst, 6, 0));
  case 0x53: return fp_decode_op(s, inst);     // OP-FP
  default: return false;
  }
}

#endif // CONFIG_RISCV_F
