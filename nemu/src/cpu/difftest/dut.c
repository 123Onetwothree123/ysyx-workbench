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

#include <dlfcn.h>
#include <stddef.h>

#include <isa.h>
#include <cpu/cpu.h>
#include <memory/paddr.h>
#include <utils.h>
#include <difftest-def.h>

void (*ref_difftest_memcpy)(paddr_t addr, void *buf, size_t n, bool direction) = NULL;
void (*ref_difftest_regcpy)(void *dut, bool direction) = NULL;
void (*ref_difftest_exec)(uint64_t n) = NULL;
void (*ref_difftest_raise_intr)(uint64_t NO) = NULL;

#if defined(CONFIG_DIFFTEST) && defined(CONFIG_RISCV_F)
/*
 * 可选浮点 DiffTest ABI。旧 REF 不导出 difftest_fp_regcpy，此时保持
 * 整数寄存器比对不变，只在启动时明确记录“freg/fcsr 未比对”。
 */
static void (*ref_difftest_fp_regcpy)(void *dut, bool direction) = NULL;
static bool ref_has_fp_state = false;
#endif

#ifdef CONFIG_DIFFTEST

static bool is_skip_ref = false;
static int skip_dut_nr_inst = 0;

// this is used to let ref skip instructions which
// can not produce consistent behavior with NEMU
void difftest_skip_ref() {
  is_skip_ref = true;
  // If such an instruction is one of the instruction packing in QEMU
  // (see below), we end the process of catching up with QEMU's pc to
  // keep the consistent behavior in our best.
  // Note that this is still not perfect: if the packed instructions
  // already write some memory, and the incoming instruction in NEMU
  // will load that memory, we will encounter false negative. But such
  // situation is infrequent.
  skip_dut_nr_inst = 0;
}

// this is used to deal with instruction packing in QEMU.
// Sometimes letting QEMU step once will execute multiple instructions.
// We should skip checking until NEMU's pc catches up with QEMU's pc.
// The semantic is
//   Let REF run `nr_ref` instructions first.
//   We expect that DUT will catch up with REF within `nr_dut` instructions.
void difftest_skip_dut(int nr_ref, int nr_dut) {
  skip_dut_nr_inst += nr_dut;

  while (nr_ref -- > 0) {
    ref_difftest_exec(1);
  }
}

static void pack_dut_registers(void *buffer) {
#ifdef CONFIG_ISA_riscv
  riscv_difftest_state_t *state = (riscv_difftest_state_t *)buffer;
  memset(state, 0, sizeof(*state));
  const size_t count = sizeof(cpu.gpr) / sizeof(cpu.gpr[0]);
  for (size_t i = 0; i < count; ++i) state->gpr[i] = cpu.gpr[i];
  state->pc = cpu.pc;
#else
  memcpy(buffer, &cpu, DIFFTEST_REG_SIZE);
#endif
}

#ifdef CONFIG_RISCV_F
static void pack_fp_registers(void *buffer) {
  riscv_difftest_fp_state_t *state = (riscv_difftest_fp_state_t *)buffer;
  memset(state, 0, sizeof(*state));
  for (int i = 0; i < 32; i++) {
    state->freg[i] = cpu.f[i];
  }
  state->fcsr = (uint64_t)(cpu.fcsr & 0xffu);
}
#endif

static vaddr_t unpack_ref_pc(const void *buffer) {
#ifdef CONFIG_ISA_riscv
  return ((const riscv_difftest_state_t *)buffer)->pc;
#else
  return ((const CPU_state *)buffer)->pc;
#endif
}

void init_difftest(char *ref_so_file, long img_size, int port,
                   paddr_t image_addr, bool ysyxsoc_mode) {
  assert(ref_so_file != NULL);

  void *handle;
  handle = dlopen(ref_so_file, RTLD_LAZY);
  assert(handle);

  ref_difftest_memcpy = dlsym(handle, "difftest_memcpy");
  assert(ref_difftest_memcpy);

  ref_difftest_regcpy = dlsym(handle, "difftest_regcpy");
  assert(ref_difftest_regcpy);

  ref_difftest_exec = dlsym(handle, "difftest_exec");
  assert(ref_difftest_exec);

  ref_difftest_raise_intr = dlsym(handle, "difftest_raise_intr");
  assert(ref_difftest_raise_intr);

  void (*ref_difftest_init)(int) = dlsym(handle, "difftest_init");
  assert(ref_difftest_init);

  /*
   * 外部 REF 可能仍是旧的 RV32 参考模型。RV64 NEMU 的寄存器状态是
   * 32*8+8 字节，绝不能把它当作 32 位的 132 字节缓冲区来拷贝。
   * 新 REF 通过 difftest_state_size() 声明 ABI；RV64 下缺少该符号时
   * 直接拒绝加载，避免静默截断高 32 位后跑出一堆伪差异。
   */
  size_t (*ref_state_size)(void) = dlsym(handle, "difftest_state_size");
  if (ref_state_size != NULL) {
    const size_t size = ref_state_size();
    if (size != DIFFTEST_REG_SIZE) {
      panic("DiffTest REF ABI 不匹配：REF 状态 %zu 字节，本 NEMU 需要 %zu 字节",
            size, (size_t)DIFFTEST_REG_SIZE);
    }
  } else {
#ifdef CONFIG_RV64
    panic("DiffTest REF 不支持 RV64 ABI（缺少 difftest_state_size）；"
          "请使用 RV64 版 NEMU/spike-diff，或改用 RV32 构建");
#endif
  }

#ifdef CONFIG_RISCV_F
  /*
   * 浮点状态是可选扩展：三个符号都存在且版本/大小匹配才启用。
   * 旧 REF 没有这些符号时继续比对整数状态，不因缺少浮点 ABI 而失败。
   */
  uint32_t (*ref_fp_version)(void) = dlsym(handle, "difftest_fp_abi_version");
  size_t (*ref_fp_size)(void) = dlsym(handle, "difftest_fp_state_size");
  void (*ref_fp_regcpy)(void *, bool) = dlsym(handle, "difftest_fp_regcpy");
  if (ref_fp_version != NULL && ref_fp_size != NULL && ref_fp_regcpy != NULL) {
    if (ref_fp_version() != DIFFTEST_FP_ABI_VERSION) {
      panic("DiffTest REF 浮点 ABI 版本不匹配：REF=%u，需要=%u",
            ref_fp_version(), DIFFTEST_FP_ABI_VERSION);
    }
    if (ref_fp_size() != sizeof(riscv_difftest_fp_state_t)) {
      panic("DiffTest REF 浮点状态大小不匹配：REF=%zu，需要=%zu",
            ref_fp_size(), sizeof(riscv_difftest_fp_state_t));
    }
    ref_difftest_fp_regcpy = ref_fp_regcpy;
    ref_has_fp_state = true;
    Log("DiffTest 浮点状态 ABI: " ANSI_FMT("ON", ANSI_FG_GREEN)
        "（freg[32] + fcsr）");
  } else if (ref_fp_version != NULL || ref_fp_size != NULL ||
             ref_fp_regcpy != NULL) {
    panic("DiffTest REF 只导出了部分浮点 ABI 符号，拒绝使用不一致的接口");
  } else {
    Log("DiffTest REF 未导出浮点状态 ABI，仅比对整数寄存器");
  }
#endif

  /* New NEMU references expose an explicit platform selector.  Keep this
   * optional so NEMU can still be used with external reference models. */
  void (*ref_difftest_set_platform)(int) =
      dlsym(handle, "difftest_set_platform");

  Log("Differential testing: %s", ANSI_FMT("ON", ANSI_FG_GREEN));
  Log("The result of every instruction will be compared with %s. "
      "This will help you a lot for debugging, but also significantly reduce the performance. "
      "If it is not necessary, you can turn it off in menuconfig.", ref_so_file);

  if (ref_difftest_set_platform != NULL)
    ref_difftest_set_platform(ysyxsoc_mode ? 1 : 0);
  ref_difftest_init(port);
  ref_difftest_memcpy(image_addr, guest_to_host(image_addr), img_size, DIFFTEST_TO_REF);
  _Alignas(max_align_t) uint8_t dut_state[DIFFTEST_REG_SIZE];
  pack_dut_registers(dut_state);
  ref_difftest_regcpy(dut_state, DIFFTEST_TO_REF);
#ifdef CONFIG_RISCV_F
  if (ref_has_fp_state) {
    _Alignas(max_align_t) uint8_t dut_fp[DIFFTEST_FP_REG_SIZE];
    pack_fp_registers(dut_fp);
    ref_difftest_fp_regcpy(dut_fp, DIFFTEST_TO_REF);
  }
#endif
}

static void checkregs(const void *ref, const void *ref_fp, vaddr_t pc) {
  bool ok = isa_difftest_checkregs(ref, pc);
#ifdef CONFIG_RISCV_F
  if (ok && ref_fp != NULL) {
    ok = isa_difftest_checkfpregs(ref_fp, pc);
  }
#else
  (void)ref_fp;
#endif
  if (!ok) {
    nemu_state.state = NEMU_ABORT;
    nemu_state.halt_pc = pc;
    isa_reg_display();
  }
}

void difftest_step(vaddr_t pc, vaddr_t npc) {
  _Alignas(max_align_t) uint8_t ref_r[DIFFTEST_REG_SIZE];
#ifdef CONFIG_RISCV_F
  _Alignas(max_align_t) uint8_t ref_fp[DIFFTEST_FP_REG_SIZE];
  const bool have_fp = ref_has_fp_state;
#else
  const bool have_fp = false;
  (void)have_fp;
#endif
  const void *ref_fp_ptr = NULL;

  if (skip_dut_nr_inst > 0) {
    ref_difftest_regcpy(ref_r, DIFFTEST_TO_DUT);
#ifdef CONFIG_RISCV_F
    if (have_fp) {
      ref_difftest_fp_regcpy(ref_fp, DIFFTEST_TO_DUT);
      ref_fp_ptr = ref_fp;
    }
#endif
    if (unpack_ref_pc(ref_r) == npc) {
      skip_dut_nr_inst = 0;
      checkregs(ref_r, ref_fp_ptr, npc);
      return;
    }
    skip_dut_nr_inst --;
    if (skip_dut_nr_inst == 0)
      panic("can not catch up with ref.pc = " FMT_WORD " at pc = " FMT_WORD,
            unpack_ref_pc(ref_r), pc);
    return;
  }

  if (is_skip_ref) {
    // to skip the checking of an instruction, just copy the reg state to reference design
    _Alignas(max_align_t) uint8_t dut_state[DIFFTEST_REG_SIZE];
    pack_dut_registers(dut_state);
    ref_difftest_regcpy(dut_state, DIFFTEST_TO_REF);
#ifdef CONFIG_RISCV_F
    if (have_fp) {
      _Alignas(max_align_t) uint8_t dut_fp[DIFFTEST_FP_REG_SIZE];
      pack_fp_registers(dut_fp);
      ref_difftest_fp_regcpy(dut_fp, DIFFTEST_TO_REF);
    }
#endif
    is_skip_ref = false;
    return;
  }

  ref_difftest_exec(1);
  ref_difftest_regcpy(ref_r, DIFFTEST_TO_DUT);
#ifdef CONFIG_RISCV_F
  if (have_fp) {
    ref_difftest_fp_regcpy(ref_fp, DIFFTEST_TO_DUT);
    ref_fp_ptr = ref_fp;
  }
#endif

  checkregs(ref_r, ref_fp_ptr, pc);
}
#else
void init_difftest(char *ref_so_file, long img_size, int port,
                   paddr_t image_addr, bool ysyxsoc_mode) { }
#endif
