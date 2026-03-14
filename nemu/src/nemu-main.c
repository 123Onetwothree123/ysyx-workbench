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

#include <common.h>
#include "monitor/sdb/sdb.h"

void init_monitor(int, char *[]);
void am_init_monitor();
void engine_start();
int is_exit_status_bad();
static int run_expr_test(const char *input_file)
{
  // 先打开输入文件
  FILE *fp = fopen(input_file, "r");
  if (fp == NULL)
  {
    printf("cannot open %s\n", input_file);
    return 1;
  }
  char line[65536];      // 行缓冲区大小
  char expr_buf[65536];  // 表达式缓冲区大小
  uint32_t expected = 0; // 存储从文件读取的期望值结果
  // 循环读文件的内容
  while (fgets(line, sizeof(line), fp) != NULL)
  {
    bool success = false; // 拿来看结果的
    uint32_t result = 0;  // 实际计算结果
    // 先读取一个无符号整数，再读取其他字符，失败了，也就是说sscanf函数返回的结果不等于2，就直接跳到下一行继续跑
    if (sscanf(line, "%u %[^\n]", &expected, expr_buf) != 2)
    {
      continue;
    }
    // 用expr函数返回计算结果
    result = expr(expr_buf, &success);
    if (!success || result != expected)
    {
      printf("failed\n");
      printf("expected: %u\n", expected);
      printf("result  : %u\n", result);
      printf("expr    : %s\n", expr_buf);
      fclose(fp);
      return 1;
    }
  }
  // 关文件
  fclose(fp);
  printf("通过了\n");
  return 0;
}
int main(int argc, char *argv[])
{

  /* Initialize the monitor. */
#ifdef CONFIG_TARGET_AM
  am_init_monitor();
#else
  init_monitor(argc, argv);
  if (run_expr_test("tools/gen-expr/build/input") != 0)
  {
    return 1;
  }
#endif

  /* Start engine. */
  engine_start();

  return is_exit_status_bad();
}
