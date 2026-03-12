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
  // Calculate the length of the register array, so that even if the number of registers changes in the future, the code does not need to be modified.
  int num_regs = sizeof(regs) / sizeof(regs[0]);
  // Iterate through the loop and print each register.
  for (int i = 0; i < num_regs; i++)
  {
    printf("%-3s: 0x%08x\t", regs[i], cpu.gpr[i]);
    if ((i + 1) % 4 == 0)
    { // print 4 to line, because we need clean.
      printf("\n");
    }
  }
  printf("\n"); // hope command can appear to next line.
}

word_t isa_reg_str2val(const char *s, bool *success)
{
  if (s == NULL || success == NULL)
  {
    if (success)
      *success = false;
    return 0;
  }
  *success = false;
  if (*s == '$')
  {
    s++;
  }
  if (*s == '\0')
  {
    return 0;
  }
  if (strcmp(s, "pc") == 0)
  {
    *success = true;
    return cpu.pc;
  }
  for (const char *p = s; *p != '\0'; p++)
  {
    if (!isalnum((unsigned char)*p))
    {
      return 0; // check wrongful char direct return 0(false)
    }
  }
  int num_regs = sizeof(regs) / sizeof(regs[0]);
  if (num_regs > sizeof(cpu.gpr) / sizeof(cpu.gpr[0]))
  {
    printf("Error: Register array size mismatch\n");
    if (success)
      *success = false;
    return 0;
  }
  // direct traverse
  for (int i = 0; i < num_regs; i++)
  {
    const char *reg_name_in_array = regs[i];
    if (reg_name_in_array[0] == '$')
    {
      reg_name_in_array++;
    }

    if (strcmp(s, reg_name_in_array) == 0)
    {
      if (i >= 0 && i < sizeof(cpu.gpr) / sizeof(cpu.gpr[0]))
      {
        *success = true;
        return cpu.gpr[i];
      }
      else
      {
        printf("Error: Register index %d out of bounds\n", i);
        *success = false;
        return 0;
      }
    }
  }
  return 0;
}