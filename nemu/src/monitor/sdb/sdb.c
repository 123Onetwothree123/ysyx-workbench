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
#include <cpu/cpu.h>
#include <readline/readline.h>
#include <readline/history.h>
#include "sdb.h"
#include <memory/paddr.h>

static int is_batch_mode = false;

void init_regex();
void init_wp_pool();

static char *parse_args(char *input, bool preserve_spaces);

static char *rl_gets()
{
  static char *line_read = NULL;
  if (line_read)
  {
    free(line_read);
    line_read = NULL;
  }
  line_read = readline("(nemu) ");
  if (line_read && *line_read)
  {
    add_history(line_read);
  }
  return line_read;
}

static int cmd_c(char *args)
{
  cpu_exec(-1);
  return 0;
}

static int cmd_q(char *args)
{
  if (nemu_state.state == NEMU_STOP)
  {
    set_nemu_state(NEMU_QUIT, cpu.pc, 0);
  }
  printf("退出\n");
  return -1;
}

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

static int cmd_help(char *args);

static int cmd_si(char *args);

static int cmd_info(char *args);

static int cmd_x(char *args);

static int cmd_p(char *args);

static int cmd_w(char *args);

static int cmd_d(char *args);

// 自己添加的
// 表达式自动化测试
static int cmd_exprtest(char *args);
// 清屏
static int cmd_clear(char *args);
// 显示历史记录的
static int cmd_history(char *args);

static struct
{
  const char *name;
  const char *description;
  int (*handler)(char *);
} cmd_table[] = {
    {"help", "Display information about all supported commands", cmd_help},
    {"c", "Continue the execution of the program", cmd_c},
    {"q", "Exit NEMU", cmd_q},

    /* TODO: Add more commands */
    {"si", "Single step execution [N], default Single step execution", cmd_si},
    {"info", "Print program status", cmd_info},
    {"x", "Examine memory", cmd_x},
    {"p", "Evaluate expression", cmd_p},
    {"w", "Set watchpoint for an expression", cmd_w},
    {"d", "Delete watchpoint by ID", cmd_d},
    {"exprtest", "Run expression tests", cmd_exprtest},
    {"clear", "Clear the terminal screen", cmd_clear},
    {"history", "Show command history", cmd_history},
};

#define NR_CMD ARRLEN(cmd_table)

static int cmd_help(char *args)
{
  /* extract the first argument */
  char *arg = strtok(NULL, " ");
  int i;

  if (arg == NULL)
  {
    /* no argument given */
    for (i = 0; i < NR_CMD; i++)
    {
      printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
    }
  }
  else
  {
    for (i = 0; i < NR_CMD; i++)
    {
      if (strcmp(arg, cmd_table[i].name) == 0)
      {
        printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
        return 0;
      }
    }
    printf("Unknown command '%s'\n", arg);
  }
  return 0;
}

void sdb_set_batch_mode()
{
  is_batch_mode = true;
}

void sdb_mainloop()
{
  if (is_batch_mode)
  {
    cmd_c(NULL);
    return;
  }

  for (char *str; (str = rl_gets()) != NULL;)
  {
    char *str_end = str + strlen(str);

    /* extract the first token as the command */
    char *cmd = strtok(str, " ");
    if (cmd == NULL)
    {
      continue;
    }

    /* treat the remaining string as the arguments,
     * which may need further parsing
     */
    char *args = cmd + strlen(cmd) + 1;
    if (args >= str_end)
    {
      args = NULL;
    }

#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

    int i;
    for (i = 0; i < NR_CMD; i++)
    {
      if (strcmp(cmd, cmd_table[i].name) == 0)
      {
        if (cmd_table[i].handler(args) < 0)
        {
          return;
        }
        break;
      }
    }

    if (i == NR_CMD)
    {
      printf("Unknown command '%s'\n", cmd);
    }
  }
}

void init_sdb()
{
  /* Compile the regular expressions. */
  init_regex();

  /* Initialize the watchpoint pool. */
  init_wp_pool();
}
static int cmd_si(char *args)
{
  // default to 1 step if the user is lazy and doesn't type a number
  uint64_t steps = 1;
  // did the user actually type something? Let's parse it
  if (args != NULL)
  {
    char *endptr;
    // try to convert the string to a number
    long n = strtol(args, &endptr, 10);
    // check if strtol actually found any digits
    // if args equals endptr, it means no number was found at the start
    if (args == endptr)
    {
      printf("Error: Invalid argument '%s'. Usage: si [N]\n", args);
      return 0;
    }
    // make sure the step count makes sense
    // negative steps or zero don't really work here
    if (n <= 0)
    {
      printf("Error: Steps must be a positive integer, got %ld\n", n);
      return 0;
    }
    // users might type "si 10 " (with a space), and we shouldn't crash
    while (*endptr == ' ')
      endptr++;
    // if there's still junk left after the number and spaces, complain
    if (*endptr != '\0')
    {
      printf("Error: Trailing garbage '%s' in argument\n", endptr);
      return 0;
    }
    steps = (uint64_t)n;
  }
  cpu_exec(steps);
  return 0;
}
static int cmd_info(char *args)
{
  if (args == NULL)
  {
    printf("nemu monitor sdb cmd_info function check args, detect args==NULL, this args need parameter input\n");
    printf("  r - print register status\n");
    printf("  w - print watchpoint information\n");
    return 0;
  }
  else if (args != NULL)
  {
    while (*args == ' ')
      args++;
    if (strcmp(args, "r") == 0) // Subcommand 'r': Print register status
    {
      isa_reg_display();
    }
    else if (strcmp(args, "w") == 0) // Subcommand 'w': Print monitor point information
    {
      PrintWatchPoint();
    }
    else
    {
      printf("Unknown info subcommand: %s\n", args);
      printf("Supported subcommands: r, w\n");
      printf("cmd_info function args happend unknow something.\n");
    }
    return 0;
  }
  else
  {
    printf("I do not know monitor cmd_info executing happened something.if and else if do not run\n");
    return -1;
  }
  return 0;
}
static int cmd_x(char *args)
{
  if (args == NULL || *args == '\0')
  {
    printf("nemu monitor sdb cmd_x function check args, detect args==NULL, this args need parameter input\n");
    printf("Usage: x N[b|h|w] EXPR\n");
    printf("  N: Number of items to examine\n");
    printf("  [b|h|w]: Optional unit size (default w):\n");
    printf("    b - byte (1 byte)\n");
    printf("    h - half-word (2 bytes)\n");
    printf("    w - word (4 bytes)\n");
    printf("  EXPR: Expression evaluating to the start address\n");
    printf("Example: x 10h $esp  (Examine 10 half-words from stack pointer)\n");
    printf("Example: x 16b 0x80100000 (Examine 16 bytes from 0x80100000)\n");
    return 0;
  }
  char *StringEndPointer;
  long n = strtol(args, &StringEndPointer, 10);
  if (StringEndPointer == args)
  {
    printf("Error: Missing argument N\n");
    return 0;
  }
  if (n <= 0)
  {
    printf("Error: N must be a positive integer\n");
    return 0;
  }
  char *expr_str = StringEndPointer;
  size_t unit_size = 4;
  while (*expr_str == ' ')
  {
    expr_str++;
  }
  switch (*expr_str)
  {
  case 'b':
    unit_size = 1;
    expr_str++;
    break;
  case 'h':
    unit_size = 2;
    expr_str++;
    break;
  case 'w':
    unit_size = 4;
    expr_str++;
    break;
  default:
    break;
  }
  while (*expr_str == ' ')
  {
    expr_str++;
  }

  if (*expr_str == '\0')
  {
    printf("Error: Missing expression EXPR\n");
    return 0;
  }
  bool success = false;
  sword_t addr_signed = expr(expr_str, &success);
  if (!success)
  {
    printf("Error: Invalid expression: %s\n", expr_get_error_msg());
    return 0;
  }
  word_t addr = (word_t)addr_signed;
  if (n > UINT32_MAX / unit_size)
  {
    printf("Error: Request too large (would overflow address space)\n");
    return 0;
  }
  word_t total_size = (word_t)n * unit_size;
  if (addr > UINT32_MAX - total_size)
  {
    printf("Error: Address range would overflow (0x%08x + %u bytes)\n",
           addr, total_size);
    return 0;
  }

  word_t end_addr = addr + total_size;

  printf("Scanning %ld items (%zu bytes each) from 0x%08x to 0x%08x:\n",
         n, unit_size, addr, end_addr - 1);
  word_t data;
  for (int i = 0; i < n; i++)
  {
    word_t current_addr = addr + i * unit_size;
    if (!safe_paddr_read(current_addr, &data, unit_size))
    {
      printf("Error: Memory read failed at 0x%08x (scan stopped)\n", current_addr);
      printf("Hint: Address may be invalid or inaccessible\n");
      return 0;
    }
    printf("0x%08x: ", current_addr);
    switch (unit_size)
    {
    case 1:
      printf("0x%02x\n", (uint8_t)data);
      break;
    case 2:
      printf("0x%04x\n", (uint16_t)data);
      break;
    case 4:
      printf("0x%08x\n", (uint32_t)data);
      break;
    default:
      printf("0x%08x\n", data);
    }
  }
  return 0;
}
static int cmd_p(char *args)
{
  args = parse_args(args, true);
  if (args == NULL)
  {
    printf("cmd_p checked args==NULL, need input parameter.\n");
    printf("Usage: p EXPR\n");
    printf("Example: p 5 + 4 * 3 / 2 - 1\n");
    return 0;
  }
  else if (args != NULL)
  {
    bool success = true;
    sword_t result_signed = expr(args, &success);
    word_t result_unsigned = (word_t)result_signed;

    if (success)
    {
      printf("Signed (Dec):   %d\n", result_signed);
      printf("Unsigned (Dec): %u\n", result_unsigned);
      printf("Hex:            0x%08x\n", result_unsigned);
    }
    else
    {
      printf("Expression evaluation failed!\n");
    }
    return 0;
  }
  else
  {
    printf("cmd_p args unknow error.\n");
    return 0;
  }
}
static int cmd_w(char *args)
{
  // check NULL
  args = parse_args(args, true);
  if (args == NULL || *args == '\0')
  {
    printf("Error: Missing expression for watchpoint\n");
    printf("Usage: w <expression>\n");
    printf("Examples:\n");
    printf("  w $eax           - Watch register eax\n");
    printf("  w 0x80100000     - Watch memory address\n");
    printf("  w *0x80100000    - Watch dereferenced memory\n");
    return 0;
  }
  if (strlen(args) >= 32)
  {
    printf("The expression is too long.\n");
    return 0;
  }
  if (!validate_expression_syntax(args))
  {
    printf("Error: Expression syntax error\n");
    return 0;
  }
  // get exprssion default value
  bool success = false;
  word_t value = expr(args, &success);
  if (!success)
  {
    printf("Warning: Cannot evaluate expression at creation time: %s\n", expr_get_error_msg());
    return 0;
  }
  WP *wp = new_wp();
  if (wp == NULL)
  {
    printf("Error: No free watchpoint slots (maximum: %d)\n", get_max_watchpoints());
    return 0;
  }
  wp_set_expr(wp, args);
  wp_set_value(wp, value);
  printf("Watchpoint %d: %s = 0x%08x\n", wp_get_no(wp), wp_get_expr(wp), value);
  return 0;
}
static int cmd_d(char *args)
{
  // check, need safe function execute
  if (args == NULL || strlen(args) == 0)
  {
    printf("Error: Missing watchpoint ID\n");
    printf("Usage: d <watchpoint_id>\n");
    printf("Example: d 1\n");
    return 0;
  }
  char *endptr;
  long id = strtol(args, &endptr, 10);
  while (*endptr == ' ')
  {
    endptr++;
  }
  if (*endptr != '\0')
  {
    printf("Error: Invalid watchpoint ID '%s'. ID must be a number.\n", args);
    printf("Usage: d <watchpoint_id>\n");
    return 0;
  }
  if (id < 0 || id > INT32_MAX)
  {
    printf("Error: Watchpoint ID must be between 0 and %d, but got %ld.\n", INT32_MAX, id);
    return 0;
  }
  if (!check_array_bounds((int)id, get_max_watchpoints()))
  {
    printf("Error: Watchpoint ID %ld exceeds maximum allowed ID (%d).\n", id, get_max_watchpoints() - 1);
    return 0;
  }
  WP *WpToDelete = find_wp_by_id((int)id);
  if (WpToDelete == NULL)
  {
    printf("Error: Watchpoint %ld not found\n", id);
    return 0;
  }
  free_wp(WpToDelete);
  printf("Deleted watchpoint %ld\n", id);
  return 0;
}
static int cmd_exprtest(char *args)
{
  args = parse_args(args, true);
  const char *input_file = (args == NULL) ? "tools/gen-expr/build/input" : args;
  if (run_expr_test(input_file) != 0)
  {
    printf("Expression test failed\n");
    return 0;
  }
  printf("Expression test passed\n");
  return 0;
}
static int cmd_clear(char *args)
{
  args = parse_args(args, true);
  if (args != NULL)
  {
    printf("用法直接输入clear\n");
    return 0;
  }
  printf("\033[H\033[J");
  fflush(stdout);
  return 0;
}
static int cmd_history(char *args)
{
  // 先从GNU的history这里获得历史记录
  HIST_ENTRY **hist_list = history_list();
  if (hist_list == NULL || history_length == 0)
  {
    printf("history_list返回了空指针，history_length也是0，没有历史记录\n");
    return 0;
  }
  int n = history_length; // 默认显示全部命令
  // 如果使用的时候提供了参数，即history 5或者10
  if (args != NULL)
  {
    char *endptr;
    long num = strtol(args, &endptr, 10);
    while (*endptr == ' ') // 跳空格的
    {
      endptr++;
    }
    if (*endptr != '\0')
    {
      printf("无效参数 '%s'。用法是history [N]\n", args); // 因为如果跳过空格后还有字符，说明参数格式不对，比如history abc
      return 0;
    }
    if (num <= 0)
    {
      printf("N必须是正整数\n");
      return 0;
    }
    // 如果小于总记录数就按num来显示多少条
    if (num < history_length)
    {
      n = (int)num;
    } // 等于或大于等于总记录数，就只打印出总记录数的数量
    else
    {
      n = history_length;
    }
  }
  printf("命令历史（显示最近 %d 条，共 %d 条）：\n", n, history_length);
  // 计算起始位置
  int start = history_length - n; // 计算方式是总记录数-打印数量
  // 然后根据结果把位置移动到开始的位置，就比如总共10条，显示最近的3条，就直接计算出7，下标7开始，一直打印到最后一条
  for (int i = start; i < history_length; i++)
  {
    char *line = hist_list[i]->line; // 第i条历史记录的内容
    // 提取命令名，目前的设计逻辑是看第一个单词，比如si 10就提出si
    char cmd_name[64] = {0};        // 保险起见，先初始化为0
    sscanf(line, "%63s", cmd_name); // 最多63，防止溢出
    // 检查命令是否有效
    int is_valid = 0; // 0是无效，1是有效
    // 直接便利命令表，检测命令名是否存在，然后NR_CMD是命令行大小，是命令总数
    for (int j = 0; j < NR_CMD; j++)
    {
      if (strcmp(cmd_name, cmd_table[j].name) == 0) // cmd_table[j]是第j个命令
      {
        is_valid = 1;
        break;
      }
    }
    //5d的目的是为了右对齐
    if (is_valid)
    {
      printf("%5d  %s\n", i + history_base, line);
    }
    else
    {
      printf("%5d  %s [未知命令]\n", i + history_base, line);
    }
  }
  return 0;
}
bool check_array_bounds(int index, int array_size)
{
  return index >= 0 && index < array_size;
}

bool check_null_pointer(const void *ptr, const char *name)
{
  if (ptr == NULL)
  {
    printf("Error: NULL pointer detected for %s\n", name);
    return false;
  }
  return true;
}
bool check_memory_address(word_t addr)
{
  if (addr == 0)
  {
    printf("Error: NULL pointer access attempt\n");
    return false;
  }
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    printf("Error: Invalid memory address " FMT_PADDR " (valid range: [" FMT_PADDR ", " FMT_PADDR "))\n",
           addr, CONFIG_MBASE, CONFIG_MBASE + CONFIG_MSIZE);
    return false;
  }
  return true;
}
bool check_string_length(const char *str, size_t max_len, const char *name)
{
  if (str == NULL)
  {
    return false;
  }
  size_t len = strlen(str);
  if (len >= max_len)
  {
    printf("Error: %s too long (%zu >= %zu)\n", name, len, max_len);
    return false;
  }
  return true;
}
bool is_valid_memory_region(word_t addr, size_t size)
{
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    return false;
  }
  if (addr % size != 0)
  {
    return false;
  }
  if (addr + size > CONFIG_MBASE + CONFIG_MSIZE)
  {
    return false;
  }
  return true;
}
bool validate_expression_syntax(const char *expression)
{
  if (expression == NULL || strlen(expression) == 0)
  {
    return false;
  }
  int paren_count = 0;
  for (const char *p = expression; *p; p++)
  {
    if (*p == '(')
    {
      paren_count++;
    }
    if (*p == ')')
    {
      paren_count--;
    }
    if (paren_count < 0)
    {
      return false;
    }
  }
  return paren_count == 0;
}
static char *parse_args(char *input, bool preserve_spaces)
{
  if (!input)
  {
    return NULL;
  }
  while (*input == ' ')
  {
    input++;
  }
  if (*input == '\0')
  {
    return NULL;
  }
  if (preserve_spaces)
  {
    char *end = input + strlen(input) - 1;
    while (end > input && *end == ' ')
    {
      *end = '\0';
      end--;
    }
    if (*input == '\0')
    {
      return NULL;
    };
  }
  return input;
}
