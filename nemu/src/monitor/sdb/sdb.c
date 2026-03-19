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

static char *parse_args(char *input, bool trim_trailing_spaces);

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
    printf("无法打开 %s\n", input_file);
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
      printf("失败\n");
      printf("期望值: %u\n", expected);
      printf("实际值: %u\n", result);
      printf("表达式: %s\n", expr_buf);
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
// 设置寄存器或者内存
static int cmd_set(char *args);

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
    {"si", "单步执行 [N]，默认单步执行", cmd_si},
    {"info", "打印程序状态", cmd_info},
    {"x", "检查内存", cmd_x},
    {"p", "求解表达式", cmd_p},
    {"w", "为表达式设置监视点", cmd_w},
    {"d", "根据ID删除监视点", cmd_d},
    {"exprtest", "运行表达式测试", cmd_exprtest},
    {"clear", "清除终端屏幕", cmd_clear},
    {"history", "显示命令历史", cmd_history},
    {"set", "设置寄存器/内存，当前只支持：set reg <name> <expr>", cmd_set},
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
  // 默认执行1步
  uint64_t steps = 1;
  // 检测是否输入内容
  if (args != NULL)
  {
    char *endptr;
    // 尝试将字符串转换为数字
    long n = strtol(args, &endptr, 10);
    // 检查strtol是否真的找到了数字
    // 如果args等于endptr，说明开头没有找到数字
    if (args == endptr)
    {
      printf("错误：无效的参数 '%s'。用法：si [N]\n", args);
      return 0;
    }
    // 确保步数是合理的
    // 负数或零在这里不太行
    if (n <= 0)
    {
      printf("错误：步数必须是正整数，得到的是 %ld\n", n);
      return 0;
    }
    // 可能会输入 "si 10 "（带空格），这个时候不应该崩溃
    while (*endptr == ' ')
      endptr++;
    // 如果数字和空格后面还有垃圾内容，就报错
    if (*endptr != '\0')
    {
      printf("错误：参数中有尾部垃圾 '%s'\n", endptr);
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
    printf("nemu监视器sdb cmd_info函数检查参数，检测到args==NULL，此参数需要输入\n");
    printf("  r - 打印寄存器状态\n");
    printf("  w - 打印监视点信息\n");
    return 0;
  }
  else if (args != NULL)
  {
    while (*args == ' ')
      args++;
    if (strcmp(args, "r") == 0) // 子命令 'r'：打印寄存器状态
    {
      isa_reg_display();
    }
    else if (strcmp(args, "w") == 0) // 子命令 'w'：打印监视点信息
    {
      PrintWatchPoint();
    }
    else
    {
      printf("未知的info子命令：%s\n", args);
      printf("支持的子命令：r, w\n");
      printf("cmd_info函数参数发生了未知情况。\n");
    }
    return 0;
  }
  else
  {
    printf("我不知道监视器cmd_info执行时发生了什么。if和else if都没有运行\n");
    return -1;
  }
  return 0;
}
static int cmd_x(char *args)
{
  if (args == NULL || *args == '\0')
  {
    printf("nemu监视器sdb cmd_x函数检查参数，检测到args==NULL，此参数需要输入\n");
    printf("用法：x N[b|h|w] 表达式\n");
    printf("  N：要检查的项目数量\n");
    printf("  [b|h|w]：可选的单位大小（默认为w）：\n");
    printf("    b - 字节（1字节）\n");
    printf("    h - 半字（2字节）\n");
    printf("    w - 字（4字节）\n");
    printf("  表达式：计算起始地址的表达式\n");
    printf("示例：x 10h $esp  （从栈指针检查10个半字）\n");
    printf("示例：x 16b 0x80100000 （从0x80100000检查16个字节）\n");
    return 0;
  }
  char *StringEndPointer;
  long n = strtol(args, &StringEndPointer, 10);
  if (StringEndPointer == args)
  {
    printf("错误：缺少参数N\n");
    return 0;
  }
  if (n <= 0)
  {
    printf("错误：N必须是正整数\n");
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
    printf("错误：缺少表达式\n");
    return 0;
  }
  bool success = false;
  sword_t addr_signed = expr(expr_str, &success);
  if (!success)
  {
    printf("错误：无效的表达式：%s\n", expr_get_error_msg());
    return 0;
  }
  word_t addr = (word_t)addr_signed;
  if (n > UINT32_MAX / unit_size)
  {
    printf("错误：请求过大（会导致地址空间溢出）\n");
    return 0;
  }
  word_t total_size = (word_t)n * unit_size;
  if (addr > UINT32_MAX - total_size)
  {
    printf("错误：地址范围会溢出（0x%08x + %u 字节）\n",
           addr, total_size);
    return 0;
  }

  word_t end_addr = addr + total_size;

  printf("正在扫描 %ld 个项目（每个%zu字节），从 0x%08x 到 0x%08x：\n", n, unit_size, addr, end_addr - 1);
  word_t data;
  for (int i = 0; i < n; i++)
  {
    word_t current_addr = addr + i * unit_size;
    if (!safe_paddr_read(current_addr, &data, unit_size))
    {
      printf("错误：在 0x%08x 处读取内存失败（扫描已停止）\n", current_addr);
      printf("提示：地址可能无效或不可访问\n");
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
  args = parse_args(args, true); // 开始去除尾部的空格
  if (args == NULL)
  {
    printf("cmd_p检查到args==NULL，需要输入参数。\n");
    printf("用法：p 表达式\n");
    printf("示例：p 5 + 4 * 3 / 2 - 1\n");
    return 0;
  }
  else if (args != NULL)
  {
    bool success = true;
    sword_t result_signed = expr(args, &success);   // 开始计算，编译器自动转换，会从无符号变为有符号模式
    word_t result_unsigned = (word_t)result_signed; // 获得无符号的结果

    if (success)
    {
      printf("有符号（十进制）：   %d\n", result_signed);
      printf("无符号（十进制）： %u\n", result_unsigned);
      printf("十六进制：            0x%08x\n", result_unsigned);
    }
    else
    {
      printf("表达式计算失败！\n");
    }
    return 0;
  }
  else
  {
    printf("cmd_p参数未知错误。\n");
    return 0;
  }
}
static int cmd_w(char *args)
{
  // 检查空指针
  args = parse_args(args, true);
  if (args == NULL || *args == '\0')
  {
    printf("错误：缺少监视点表达式\n");
    printf("用法：w <表达式>\n");
    printf("示例：\n");
    printf("  w $eax           - 监视寄存器eax\n");
    printf("  w 0x80100000     - 监视内存地址\n");
    printf("  w *0x80100000    - 监视解引用的内存\n");
    return 0;
  }
  if (strlen(args) >= 32)
  {
    printf("表达式过长。\n");
    return 0;
  }
  if (!validate_expression_syntax(args))
  {
    printf("错误：表达式语法错误\n");
    return 0;
  }
  // 获取表达式的默认值
  bool success = false;
  word_t value = expr(args, &success);
  if (!success)
  {
    printf("警告：创建时无法计算表达式：%s\n", expr_get_error_msg());
    return 0;
  }
  WP *wp = new_wp(); // 获取监视点
  if (wp == NULL)
  {
    printf("错误：没有空闲的监视点槽位（最大：%d）\n", get_max_watchpoints());
    return 0;
  }
  wp_set_expr(wp, args);   // 存储表达式
  wp_set_value(wp, value); // 存储初始值
  printf("监视点 %d：%s = " FMT_WORD "\n", wp_get_no(wp), wp_get_expr(wp), value);
  return 0;
}
static int cmd_d(char *args)
{
  // 检查，需要安全函数执行
  if (args == NULL || strlen(args) == 0)
  {
    printf("错误：缺少监视点ID\n");
    printf("用法：d <监视点ID>\n");
    printf("示例：d 1\n");
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
    printf("错误：无效的监视点ID '%s'。ID必须是数字。\n", args);
    printf("用法：d <监视点ID>\n");
    return 0;
  }
  if (id < 0 || id > INT32_MAX)
  {
    printf("错误：监视点ID必须在0到%d之间，但得到的是%ld。\n", INT32_MAX, id);
    return 0;
  }
  if (!check_array_bounds((int)id, get_max_watchpoints()))
  {
    printf("错误：监视点ID %ld 超出最大允许ID（%d）。\n", id, get_max_watchpoints() - 1);
    return 0;
  }
  WP *WpToDelete = find_wp_by_id((int)id); // 找到要删掉的监视点
  if (WpToDelete == NULL)
  {
    printf("错误：未找到监视点 %ld\n", id);
    return 0;
  }
  free_wp(WpToDelete); // 删掉找到的点
  printf("已删除监视点 %ld\n", id);
  return 0;
}
static int cmd_exprtest(char *args)
{
  args = parse_args(args, true);
  const char *input_file = (args == NULL) ? "tools/gen-expr/build/input" : args;
  if (run_expr_test(input_file) != 0)
  {
    printf("表达式测试失败\n");
    return 0;
  }
  printf("表达式测试通过\n");
  return 0;
}
static int cmd_clear(char *args)
{
  args = parse_args(args, true);
  if (args != NULL)
  {
    printf("用法是直接输入clear\n");
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
    // 5d的目的是为了右对齐
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
static int cmd_set(char *args)
{
  args = parse_args(args, true);
  if (args == NULL)
  {
    printf("cmd_set检测到参数指针为空指针，即无参数\n");
    printf("用法：set reg <name> <expr>\n");
    return 0;
  }
  char *subcmd = args;                // 指向第一个单词，目标是解析出reg或者memory
  char *space1 = strchr(subcmd, ' '); // 找到第一个空格，用来切出子命令和后续参数
  if (space1 == NULL)                 // 如果没有第一个空格，说明只写了一个单词，比如说set reg
  {
    printf("错误：参数不完整\n");
    printf("用法：set reg <name> <expr>\n");
    return 0;
  }
  *space1 = '\0';                 // 先把第一个空格改成字符串结束符，然后就能够实现将subcmd变成独立字符串了的操作
  if (strcmp(subcmd, "reg") == 0) // reg部分
  {
    char *reg_name = parse_args(space1 + 1, false); // 跳过第一个空格，取第二段内容，理论上这里应该是寄存器名
    if (reg_name == NULL)
    {
      printf("缺少寄存器名\n");
      printf("用法：set reg <name> <expr>\n");
      return 0;
    }
    char *space2 = strchr(reg_name, ' '); // 在reg_name这段里继续找下一个空格，用来切出寄存器名和表达式
    if (space2 == NULL)                   // 如果没有第二个空格，就说明没写表达式
    {
      printf("错误：缺少表达式\n");
      printf("用法：set reg <name> <expr>\n");
      return 0;
    }
    *space2 = '\0';                                // 把第二个空格也切断
    char *expr_str = parse_args(space2 + 1, true); // 取剩下整段作为表达式，并清理前后空格
    if (expr_str == NULL)
    {
      printf("错误：缺少表达式\n");
      return 0;
    }
    bool success = false;
    word_t val = expr(expr_str, &success);
    if (!success)
    {
      printf("表达式求值失败\n");
    }
    if (!isa_reg_setval(reg_name, val))
    {
      printf("错误：无效的寄存器名 '%s'\n", reg_name);
      return 0;
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
    printf("错误：检测到 %s 为空指针\n", name);
    return false;
  }
  return true;
}
bool check_memory_address(word_t addr)
{
  if (addr == 0)
  {
    printf("错误：尝试访问空指针地址\n");
    return false;
  }
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    printf("错误：无效的内存地址 " FMT_PADDR "（有效范围：[" FMT_PADDR ", " FMT_PADDR "）\n",
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
    printf("错误：%s 过长（%zu >= %zu）\n", name, len, max_len);
    return false;
  }
  return true;
}
bool is_valid_memory_region(word_t addr, size_t size)
{
  // 检查开始的时候地址边界的
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    return false;
  }
  if (addr % size != 0) // 如果内存地址没有对齐就直接返回错误
  {
    return false;
  }
  // 检查访问有没有越过内存边界
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
  int paren_count = 0;                      // 初始值为0代表没有未闭合的括号
  for (const char *p = expression; *p; p++) // 当遇到字符串结束符\0时停止，因为\0的ASCII值为0，在布尔上下文中为假
  {
    // 左+1，右-1
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
static char *parse_args(char *input, bool trim_trailing_spaces) // trim_trailing_spaces为true时去除尾部空格
{
  if (!input)
  {
    printf("parse_args检测到字符串指针是空指针");
    return NULL;
  }
  // 跳过前导空格
  while (*input == ' ')
  {
    input++;
  }
  // 检测是不是纯空格字符串
  if (*input == '\0')
  {
    return NULL;
  }
  if (trim_trailing_spaces)
  {
    char *end = input + strlen(input) - 1; // 指针直接指向最后一个字符
    while (end > input && *end == ' ')     // 从后向前遍历，然后遇到空格就直接截断
    {
      *end = '\0'; // 将空格替换为字符串结束符
      end--;       // 因为是从后向前遍历，所以是必须设计成向前移动一个位置
    }
    if (*input == '\0') // 防止字符串只剩下空格
    {
      return NULL;
    };
  }
  return input;
}
