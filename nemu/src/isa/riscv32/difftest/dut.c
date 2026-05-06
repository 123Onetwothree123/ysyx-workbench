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

#include <isa.h>
#include <cpu/difftest.h>
#include "../local-include/reg.h"

/*
由于不同ISA的寄存器有所不同, 框架代码把寄存器对比抽象成一个ISA相关的API, 即isa_difftest_checkregs()函
数(在nemu/src/isa/$ISA/difftest/dut.c中定义). 你需要实现isa_difftest_checkregs()函数, 把通用寄存器
和PC与从DUT中读出的寄存器的值进行比较. 若对比结果一致, 函数返回true; 如果发现值不一样, 函数返回false, 框
架代码会自动停止客户程序的运行. 特别地, isa_difftest_checkregs()对比结果不一致时, 第二个参数pc应指向导
致对比结果不一致的指令, 可用于打印提示信息.
*/
bool isa_difftest_checkregs(CPU_state *ref_r, vaddr_t pc)
{
  // return false;
  for (size_t i = 0; i < MUXDEF(CONFIG_RVE, 17, 32); i++)
  {
    if (ref_r->gpr[i] != cpu.gpr[i])
    {
      Log("比对失败，pc = " FMT_WORD "，寄存器 %s的参考的值 = " FMT_WORD "，实际上的值 = " FMT_WORD, pc, reg_name(i), ref_r->gpr[i], cpu.gpr[i]);
      return false;
    }
  }
  if (ref_r->pc != cpu.pc)
  {
    Log("比对失败了，参考的pc = " FMT_WORD "，实际上的pc = " FMT_WORD, ref_r->pc, cpu.pc);
    return false;
  }
  return true;
}

void isa_difftest_attach()
{
}
