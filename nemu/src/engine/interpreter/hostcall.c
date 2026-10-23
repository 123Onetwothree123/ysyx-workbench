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

#include <utils.h>
#include <cpu/ifetch.h>
#include <isa.h>
#include <cpu/difftest.h>

#include <iringbuf.h>

void set_nemu_state(int state, vaddr_t pc, int halt_ret)
{
  difftest_skip_ref();// custom halt会结束参考模型，本拍不再参与后续difftest
  nemu_state.state = state;//状态机设置为END模式
  nemu_state.halt_pc = pc;
  // 0=GOOD TRAP, 非0=BAD TRAP，然后cpu_exec函数执行到状态机为END模式，就打印GOOD TRAP或HIT TRAP
  nemu_state.halt_ret = halt_ret;
}

__attribute__((noinline)) void invalid_inst(vaddr_t thispc)
{
  uint32_t temp[2] = {};
  vaddr_t pc = thispc;
#ifdef CONFIG_RVC
  // 先按真实宽度取指令：16 位非法编码不能为了打印现场而多读两个字节，
  // 否则指令恰好位于内存末尾时会拿到与根因无关的访存异常。
  int ilen = 2;
  temp[0] = inst_fetch(&pc, 2);
  if ((temp[0] & 3u) == 3u) {
    temp[0] |= inst_fetch(&pc, 2) << 16;
    ilen = 4;
  }
  temp[1] = inst_fetch(&pc, 2);
#else
  temp[0] = inst_fetch(&pc, 4);
  temp[1] = inst_fetch(&pc, 4);
#endif

  uint8_t *p = (uint8_t *)temp;
#ifdef CONFIG_RVC
  printf("invalid opcode(PC = " FMT_WORD "):\n\t", thispc);
  for (int i = 0; i < ilen; i++) {
    printf("%02x ", p[i]);
  }
  printf("...\n\t%0*x...\n", ilen * 2, temp[0]);
#else
  printf("invalid opcode(PC = " FMT_WORD "):\n"
         "\t%02x %02x %02x %02x %02x %02x %02x %02x ...\n"
         "\t%08x %08x...\n",
         thispc, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], temp[0], temp[1]);
#endif

  printf("There are two cases which will trigger this unexpected exception:\n"
         "1. The instruction at PC = " FMT_WORD " is not implemented.\n"
         "2. Something is implemented incorrectly.\n",
         thispc);
  printf("Find this PC(" FMT_WORD ") in the disassembling result to distinguish which case it is.\n\n", thispc);
  printf(ANSI_FMT("If it is the first case, see\n%s\nfor more details.\n\n"
                  "If it is the second case, remember:\n"
                  "* The machine is always right!\n"
                  "* Every line of untested code is always wrong!\n\n",
                  ANSI_FG_RED),
         isa_logo);

  PrintIringbuf(thispc);

  set_nemu_state(NEMU_ABORT, thispc, -1);
}
