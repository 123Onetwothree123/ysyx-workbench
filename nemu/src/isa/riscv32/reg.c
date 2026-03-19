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
#include "local-include/reg.h"
#include <cpu/cpu.h>

#include <ctype.h>
const char *regs[] = {
    "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
    "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
    "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};

void isa_reg_display()
{
  printf("pc:0x%08x\n", cpu.pc);
  // 计算寄存器数组的长度，这样即使将来寄存器数量发生变化，也不需要修改代码
  int num_regs = sizeof(regs) / sizeof(regs[0]);
  // 循环遍历并打印每个寄存器
  for (int i = 0; i < num_regs; i++)
  {
    printf("%-3s: 0x%08x\t", regs[i], cpu.gpr[i]);
    if ((i + 1) % 4 == 0)
    { // 每行打印4个寄存器，保持输出整洁
      printf("\n");
    }
  }
  printf("\n"); // 换行，使命令提示符显示在下一行
}

word_t isa_reg_str2val(const char *s, bool *success)
{
  // 如果是空，直接就转换失败
  if (s == NULL || success == NULL)
  {
    if (success)
    {
      *success = false;
    }
    return 0;
  }
  *success = false;
  // 检测到是$，就直接开始迭代s变量
  if (*s == '$')
  {
    s++;
  }
  // 末尾
  if (*s == '\0')
  {
    return 0;
  }
  // 如果检测到命令要求输出PC寄存器，就直接返回目前的PC值
  if (strcmp(s, "pc") == 0)
  {
    *success = true;
    return cpu.pc;
  }
  for (const char *p = s; *p != '\0'; p++)
  {
    if (!isalnum((unsigned char)*p))
    {
      return 0; // 检查到非法字符，直接返回0（失败）
    }
  }
  int num_regs = sizeof(regs) / sizeof(regs[0]);
  if (num_regs > sizeof(cpu.gpr) / sizeof(cpu.gpr[0]))
  {
    printf("错误：寄存器数组大小不匹配\n");
    if (success)
    {
      *success = false;
    }
    return 0;
  }
  // 直接遍历
  for (int i = 0; i < num_regs; i++)
  {
    // 从寄存器组里面取出名字
    const char *reg_name_in_array = regs[i];
    if (reg_name_in_array[0] == '$')
    {
      reg_name_in_array++; // 跳过$字符
    }
    // 检测输入的数据是否和寄存器组里面的名字匹配
    if (strcmp(s, reg_name_in_array) == 0)
    {
      if (i >= 0 && i < sizeof(cpu.gpr) / sizeof(cpu.gpr[0]))
      {
        *success = true;
        return cpu.gpr[i]; // 返回寄存器中存储的数据
      }
      else
      {
        printf("错误：寄存器索引 %d 越界\n", i);
        *success = false;
        return 0;
      }
    }
  }
  return 0;
}

isa_reg_set_result_t isa_reg_setval(const char *s, word_t val)
{
  if (s == NULL)
  {
    return ISA_REG_SET_INVALID;
  }
  if (*s == '$')
  {
    s++;
  }
  if (*s == '\0')
  {
    return ISA_REG_SET_INVALID;
  }
  if (strcmp(s, "pc") == 0)
  {
    // 本来打算直接设计的后来发现pc不在regs[]里面，所以得单独拉出来设计
    cpu.pc = val;
    return ISA_REG_SET_SUCCESS;
  }
  for (const char *p = s; *p != '\0'; p++)
  {
    // 拿来过滤非法字符
    if (!isalnum((unsigned char)*p))
    {
      return ISA_REG_SET_INVALID;
    }
  }
  int num_regs = sizeof(regs) / sizeof(regs[0]); // 算数组长度
  for (int i = 0; i < num_regs; i++)             // 实在是实现不出来高级的设计了，只能用遍历全部的方式来实现功能了
  {
    const char *reg_name_in_array = regs[i]; // 通过i获取对应的寄存器的索引的名称的字符串
    if (reg_name_in_array[0] == '$')
    {
      reg_name_in_array++; // 这是为了迭代指针，后移一个字符
    }
    if (strcmp(s, reg_name_in_array) == 0)
    {
      if (i == 0)
      {
        cpu.gpr[0] = 0; // 根据手册，0号寄存器不让动，不得写入
        return ISA_REG_SET_READONLY;
      }
      cpu.gpr[i] = val; // 直接写入值
      return ISA_REG_SET_SUCCESS;
    }
  }
  return ISA_REG_SET_INVALID; // 其他情况直接返回invalid无效
}