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

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <assert.h>
#include <string.h>

// this should be enough
static char buf[65536] = {};
static char code_buf[65536 + 128] = {}; // a little larger than `buf`
static char *code_format =
    "#include <stdio.h>\n"
    "int main() { "
    "  unsigned result = %s; "
    "  printf(\"%%u\", result); "
    "  return 0; "
    "}";

static char *buf_end = buf; // Pointer to the end of the string in buf
uint32_t choose(uint32_t n)
{
  static int initialized = 0;
  if (!initialized)
  {
    srand((unsigned int)time(NULL));
    initialized = 1;
  }
  if (n == 0)
  {
    return 0;
  }
  uint32_t threshold = (RAND_MAX / n) * n;
  uint32_t RandomNumber;
  do
  {
    RandomNumber = rand();
  } while (RandomNumber >= threshold);
  return RandomNumber % n;
}
static void gen(const char *s)
{ // Safely append a string to buf, checking for overflow
  size_t len = strlen(s);
  if (buf_end + len < buf + sizeof(buf))
  {
    strcpy(buf_end, s);
    buf_end += len;
  }
}
static void gen_space()
{ // Randomly insert a space into the buffer
  if (choose(2) == 0)
  {
    gen(" ");
  }
}
static void gen_num()
{ // Generate a random unsigned number
  gen_space();
  char num_buf[32];
  // Generate a number, e.g., up to 100
  sprintf(num_buf, "%uU", (unsigned)choose(101));
  gen(num_buf);
  gen_space();
}
static void gen_positive_num()
{ // Generate a random positive number (>= 1) to avoid division by zero
  gen_space();
  char num_buf[32];
  // Generate a number from 1 to 100
  sprintf(num_buf, "%uU", (unsigned)choose(100) + 1);
  gen(num_buf);
  gen_space();
}
static char gen_rand_op()
{ // Generate a random operator and return it
  gen_space();
  char op;
  switch (choose(4))
  {
  case 0:
    op = '+';
    break;
  case 1:
    op = '-';
    break;
  case 2:
    op = '*';
    break;
  default:
    op = '/';
    break;
  }
  char op_str[2] = {op, '\0'};
  gen(op_str);
  gen_space();
  return op;
}
/*
static void gen_rand_expr()
{
  switch (choose(3))
  {
  case 0:
    gen_num();
    break;
  case 1:
    gen('(');
    gen_rand_expr();
    gen(')');
    break;
  default:
    gen_rand_expr();
    gen_rand_op();
    gen_rand_expr();
    break;
  }
  buf[0] = '\0';
}
  */
// Recursively generate a random expression
static void gen_rand_expr()
{
  // Prevent buffer overflow: if space is low, just generate a number.
  if (buf_end - buf > sizeof(buf) - 32)
  { // Reserve space for a number and potential spaces
    gen_num();
    return;
  }

  switch (choose(3))
  {
  case 0: // Number
    gen_num();
    break;

  case 1: // Parenthesized expression
    gen_space();
    gen("(");
    gen_rand_expr();
    gen(")");
    gen_space();
    break;

  default: // Binary operation
    gen_rand_expr();
    char op = gen_rand_op();
    // Filter division by zero: if op is '/', ensure the right operand is positive
    if (op == '/')
    {
      gen_positive_num();
    }
    else
    {
      gen_rand_expr();
    }
    break;
  }
}

int main(int argc, char *argv[])
{
  int seed = time(0);
  srand(seed);
  int loop = 1;
  if (argc > 1)
  {
    sscanf(argv[1], "%d", &loop);
  }
  int i;
  for (i = 0; i < loop; i++)
  {
    buf[0] = '\0'; // Reset buffer for each iteration
    buf_end = buf;
    gen_rand_expr();

    *buf_end = '\0'; // Ensure the buffer is null-terminated
    sprintf(code_buf, code_format, buf);

    FILE *fp = fopen("/tmp/.code.c", "w");
    assert(fp != NULL);
    fputs(code_buf, fp);
    fclose(fp);

    int ret = system("gcc /tmp/.code.c -o /tmp/.expr");
    if (ret != 0)
      continue;

    fp = popen("/tmp/.expr", "r");
    assert(fp != NULL);

    // int result;
    unsigned int result;
    /*
    ret = fscanf(fp, "%d", &result);
    */
    if (fscanf(fp, "%u", &result) != 1)
    {
      // If the expression crashed (e.g., still a div-by-zero from (x-x)), skip it
      pclose(fp);
      continue;
    }
    pclose(fp);
    printf("%u %s\n", result, buf);
  }
  return 0;
}
