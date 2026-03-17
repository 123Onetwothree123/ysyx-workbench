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

#include "sdb.h"
#include <isa.h>
#include <memory/paddr.h>
#include <stdlib.h>

#define NR_WP 32

typedef struct watchpoint
{
  int NO;
  struct watchpoint *next;

  /* TODO: Add more members if necessary */
  char expr[32]; // The expression for monitoring
  word_t old_val;
  bool enabled;
  vaddr_t last_trigger_pc;
  bool has_last_trigger;
} WP;

static WP wp_pool[NR_WP] = {};
static WP *head = NULL, *free_ = NULL;

WP *new_wp();
void free_wp(WP *wp);
extern const char *expr_get_error_msg();

static int wp_compare_by_no(const void *a, const void *b);
static bool scan_watchpoints(bool show_all, bool update_val);

void init_wp_pool()
{
  int i;
  for (i = 0; i < NR_WP; i++)
  {
    wp_pool[i].NO = i;
    wp_pool[i].next = (i == NR_WP - 1 ? NULL : &wp_pool[i + 1]);
  }

  head = NULL;
  free_ = wp_pool;
}

/* TODO: Implement the functionality of watchpoint */

void PrintWatchPoint()
{
  // Mode: Show all, do not update values
  scan_watchpoints(true, false);
}
WP *new_wp()
{
  if (free_ == NULL)
  {
    printf("free_ == NULL，需要使用 assert(free_ != NULL) 结束此程序\n");
    fprintf(stderr, "错误：没有空闲的监视点槽位（最大 = %d）\n", NR_WP);
    fflush(stderr);
    assert(free_ != NULL);
  }
  WP *wp = free_;        // 保存要取出的节点
  free_ = free_->next;   // 移动空闲的链表的头指针
  wp->NO = wp - wp_pool; // 算监视点的编号
  wp->next = head;       // 将新的节点指向目前的头节点
  wp->expr[0] = '\0';
  wp->old_val = 0;
  wp->enabled = true;
  wp->last_trigger_pc = 0;
  wp->has_last_trigger = false;
  head = wp;             // 更新头节点
  return wp;             // 返回监视点指针
}
void free_wp(WP *wp)
{
  // check null args
  if (wp == NULL)
  {
    printf("警告：尝试释放空监视点\n");
    return;
  }
  if (head == NULL)
  {
    printf("警告：没有正在使用的监视点，无法释放监视点 #%d\n", wp->NO);
    return;
  }
  if (wp->NO < 0 || wp->NO >= NR_WP)
  {
    printf("警告：无效的监视点ID %d\n", wp->NO);
    return;
  }
  WP *previous = NULL;
  WP *current = head;
  // 搜索目标节点和前一个节点

  while (current != NULL && current != wp)
  {
    previous = current;
    current = current->next;
  }
  // 如果未找到此节点
  if (current == NULL)
  {
    printf("警告：监视点 #%d 不在活动列表中\n", wp->NO);
    return;
  }
  if (previous == NULL)
  {
    head = wp->next; // 头指针指向下一个节点
  }
  else if (previous->next == wp)
  {
    previous->next = wp->next;
  }
  else
  {
    printf("不知道watchpoint.c链表发生了什么\n");
    printf("可能监视点 #%d 无法从前一个节点到达\n", wp->NO);
    assert(0); // direct exit
  }
  // clear data
  memset(wp->expr, 0, sizeof(wp->expr));
  wp->old_val = 0;
  wp->enabled = false;
  wp->last_trigger_pc = 0;
  wp->has_last_trigger = false;
  wp->NO = -1;
  wp->next = free_;
  free_ = wp;
}
void wp_set_expr(WP *wp, const char *expr)
{
  if (wp == NULL || expr == NULL)
  {
    printf("watchpoint.c wp_set_expr函数的wp或expr参数为NULL\n");
    return;
  }
  strncpy(wp->expr, expr, sizeof(wp->expr) - 1);
  wp->expr[sizeof(wp->expr) - 1] = '\0';
  if (strlen(expr) >= sizeof(wp->expr))
  {
    printf("表达式已被截断为%zu个字符\n", sizeof(wp->expr) - 1);
  }
}
const char *wp_get_expr(const WP *wp)
{
  return wp ? wp->expr : NULL;
}
void wp_set_value(WP *wp, word_t value)
{
  if (wp)
  {
    wp->old_val = value;
  }
}
word_t wp_get_value(const WP *wp)
{
  return wp ? wp->old_val : 0;
}
void wp_set_no(WP *wp, int no)
{
  if (wp)
  {
    wp->NO = no;
  }
}
int wp_get_no(const WP *wp)
{
  return wp ? wp->NO : -1;
}

void wp_set_next(WP *wp, WP *next)
{
  if (wp)
  {
    wp->next = next;
  }
}
WP *wp_get_next(const WP *wp)
{
  return wp ? wp->next : NULL;
}
int get_max_watchpoints(void)
{
  return NR_WP;
}
WP *wp_get_head(void)
{
  return head;
}

WP *find_wp_by_id(int id)
{
  if (!check_array_bounds(id, NR_WP))
  {
    return NULL;
  }
  WP *current = head;
  while (current != NULL)
  {
    if (wp_get_no(current) == id)
    {
      return current;
    }
    current = wp_get_next(current);
  }
  return NULL;
}
bool check_watchpoints(void)
{
  // Mode: Only show changes, update values
  bool triggered = scan_watchpoints(false, true);
  return triggered;
}
void print_watchpoint_stop_msg(void)
{
  printf("程序因监视点变化而停止。\n");
}
bool safe_paddr_read(word_t addr, word_t *result, size_t size)
{
  if (result == NULL)
  {
    Log("safe_paddr_read: 结果指针为NULL");
    return false;
  }
  if (size == 0 || size > sizeof(word_t))
  {
    Log("safe_paddr_read: 无效的大小 %zu", size);
    return false;
  }
  if (!in_pmem_range(addr, size))
  {
    return false;
  }
#ifdef CONFIG_MBASE
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    return false;
  }
#endif
  *result = paddr_read(addr, size);
  return true;
}

/* Comparison function: sort by NO in ascending order */
static int wp_compare_by_no(const void *a, const void *b)
{
  const WP *const *pa = a;
  const WP *const *pb = b;
  int na = (*pa)->NO;
  int nb = (*pb)->NO;
  if (na < nb)
    return -1;
  if (na > nb)
    return 1;
  return 0;
}

static void print_table_border(const int *widths, int nr_cols)
{
  fputs(ANSI_FG_BLUE, stdout);
  putchar('+');
  for (int col = 0; col < nr_cols; col++)
  {
    for (int i = 0; i < widths[col] + 2; i++)
      putchar('-');
    putchar('+');
  }
  fputs(ANSI_NONE, stdout);
  putchar('\n');
}

static void print_spaces(int count)
{
  for (int i = 0; i < count; i++)
  {
    putchar(' ');
  }
}

static const unsigned char *skip_ansi_escape(const unsigned char *s)
{
  if (s[0] != '\033' || s[1] != '[')
  {
    return s;
  }

  s += 2;
  while (*s != '\0' && !(*s >= 0x40 && *s <= 0x7e))
  {
    s++;
  }
  if (*s != '\0')
  {
    s++;
  }
  return s;
}

static uint32_t decode_utf8_codepoint(const unsigned char *s, int *bytes)
{
  if ((s[0] & 0x80) == 0)
  {
    *bytes = 1;
    return s[0];
  }

  if ((s[0] & 0xe0) == 0xc0 && s[1] != '\0' && (s[1] & 0xc0) == 0x80)
  {
    *bytes = 2;
    return ((uint32_t)(s[0] & 0x1f) << 6) | (uint32_t)(s[1] & 0x3f);
  }

  if ((s[0] & 0xf0) == 0xe0 &&
      s[1] != '\0' && s[2] != '\0' &&
      (s[1] & 0xc0) == 0x80 && (s[2] & 0xc0) == 0x80)
  {
    *bytes = 3;
    return ((uint32_t)(s[0] & 0x0f) << 12) |
           ((uint32_t)(s[1] & 0x3f) << 6) |
           (uint32_t)(s[2] & 0x3f);
  }

  if ((s[0] & 0xf8) == 0xf0 &&
      s[1] != '\0' && s[2] != '\0' && s[3] != '\0' &&
      (s[1] & 0xc0) == 0x80 && (s[2] & 0xc0) == 0x80 && (s[3] & 0xc0) == 0x80)
  {
    *bytes = 4;
    return ((uint32_t)(s[0] & 0x07) << 18) |
           ((uint32_t)(s[1] & 0x3f) << 12) |
           ((uint32_t)(s[2] & 0x3f) << 6) |
           (uint32_t)(s[3] & 0x3f);
  }

  *bytes = 1;
  return s[0];
}

static int codepoint_display_width(uint32_t codepoint)
{
  if (codepoint == 0)
  {
    return 0;
  }

  if (codepoint < 0x20 || (codepoint >= 0x7f && codepoint < 0xa0))
  {
    return 0;
  }

  if ((codepoint >= 0x0300 && codepoint <= 0x036f) ||
      (codepoint >= 0x1ab0 && codepoint <= 0x1aff) ||
      (codepoint >= 0x1dc0 && codepoint <= 0x1dff) ||
      (codepoint >= 0x20d0 && codepoint <= 0x20ff) ||
      (codepoint >= 0xfe20 && codepoint <= 0xfe2f))
  {
    return 0;
  }

  if (codepoint >= 0x1100 &&
      (codepoint <= 0x115f ||
       codepoint == 0x2329 || codepoint == 0x232a ||
       (codepoint >= 0x2e80 && codepoint <= 0xa4cf && codepoint != 0x303f) ||
       (codepoint >= 0xac00 && codepoint <= 0xd7a3) ||
       (codepoint >= 0xf900 && codepoint <= 0xfaff) ||
       (codepoint >= 0xfe10 && codepoint <= 0xfe19) ||
       (codepoint >= 0xfe30 && codepoint <= 0xfe6f) ||
       (codepoint >= 0xff00 && codepoint <= 0xff60) ||
       (codepoint >= 0xffe0 && codepoint <= 0xffe6) ||
       (codepoint >= 0x20000 && codepoint <= 0x2fffd) ||
       (codepoint >= 0x30000 && codepoint <= 0x3fffd)))
  {
    return 2;
  }

  return 1;
}

static int display_width(const char *s)
{
  int width = 0;
  const unsigned char *p = (const unsigned char *)s;

  while (*p != '\0')
  {
    if (*p == '\033' && p[1] == '[')
    {
      p = skip_ansi_escape(p);
      continue;
    }

    int bytes = 1;
    uint32_t codepoint = decode_utf8_codepoint(p, &bytes);
    width += codepoint_display_width(codepoint);
    p += bytes;
  }

  return width;
}

static void print_table_cell(const char *text, int width, bool right_align)
{
  int padding = width - display_width(text);
  if (padding < 0)
  {
    padding = 0;
  }

  putchar(' ');
  if (right_align)
  {
    print_spaces(padding);
  }

  fputs(text, stdout);

  if (!right_align)
  {
    print_spaces(padding);
  }

  printf(" |");
}

static const char *skip_leading_spaces(const char *s)
{
  while (*s != '\0' && isspace((unsigned char)*s))
  {
    s++;
  }
  return s;
}

static const char *get_watchpoint_type_name(const char *expr)
{
  const char *p = skip_leading_spaces(expr);

  if (*p == '*')
  {
    return "解引用";
  }

  if (*p == '$')
  {
    p++;
    while (*p != '\0' && (isalnum((unsigned char)*p) || *p == '_'))
    {
      p++;
    }
    p = skip_leading_spaces(p);
    return (*p == '\0') ? "寄存器" : "表达式";
  }

  for (; *p != '\0'; p++)
  {
    if (strchr("+-*/()=!&|%^<>", *p) != NULL)
    {
      return "表达式";
    }
  }

  return "常量";
}

static const char *get_watchpoint_type_color(const char *type_name)
{
  if (strcmp(type_name, "寄存器") == 0)
    return ANSI_FG_CYAN;
  if (strcmp(type_name, "解引用") == 0)
    return ANSI_FG_YELLOW;
  if (strcmp(type_name, "表达式") == 0)
    return ANSI_FG_MAGENTA;
  return ANSI_FG_GREEN;
}

static const char *get_watchpoint_header_color(const char *title)
{
  if (strcmp(title, "编号") == 0)
    return ANSI_FG_CYAN;
  if (strcmp(title, "类型") == 0)
    return ANSI_FG_BLUE;
  if (strcmp(title, "旧值") == 0)
    return ANSI_FG_WHITE;
  if (strcmp(title, "新值") == 0)
    return ANSI_FG_GREEN;
  if (strcmp(title, "变化量") == 0)
    return ANSI_FG_YELLOW;
  if (strcmp(title, "启用状态") == 0)
    return ANSI_FG_CYAN;
  if (strcmp(title, "状态") == 0)
    return ANSI_FG_MAGENTA;
  if (strcmp(title, "触发位置") == 0)
    return ANSI_FG_YELLOW;
  if (strcmp(title, "表达式") == 0)
    return ANSI_FG_MAGENTA;
  return ANSI_FG_WHITE;
}

static const char *get_watchpoint_no_color(bool enabled, bool success, bool changed)
{
  if (!enabled)
    return ANSI_FG_WHITE;
  if (!success)
    return ANSI_FG_RED;
  if (changed)
    return ANSI_FG_YELLOW;
  return ANSI_FG_CYAN;
}

static const char *get_watchpoint_expr_color(const char *type_name, bool enabled, bool success, bool changed)
{
  if (!enabled)
    return ANSI_FG_WHITE;
  if (!success)
    return ANSI_FG_RED;
  if (changed)
    return ANSI_FG_YELLOW;
  return get_watchpoint_type_color(type_name);
}

static void format_delta_str(char *buf, size_t size, bool enabled, bool success, word_t old_val, word_t current_val)
{
  if (!enabled || !success)
  {
    snprintf(buf, size, ANSI_FG_RED "N/A" ANSI_NONE);
    return;
  }

  char sign = '+';
  word_t delta = current_val;
  const char *color = ANSI_FG_BLUE;

  if (current_val >= old_val)
  {
    delta = current_val - old_val;
    if (delta != 0)
    {
      color = ANSI_FG_YELLOW;
    }
  }
  else
  {
    sign = '-';
    delta = old_val - current_val;
    color = ANSI_FG_MAGENTA;
  }

  snprintf(buf, size, "%s%c" FMT_WORD ANSI_NONE, color, sign, delta);
}

static void format_trigger_pc_str(char *buf, size_t size, const WP *wp)
{
  if (wp->has_last_trigger)
  {
    snprintf(buf, size, ANSI_FG_BLUE FMT_WORD ANSI_NONE, wp->last_trigger_pc);
  }
  else
  {
    snprintf(buf, size, ANSI_FG_WHITE "-" ANSI_NONE);
  }
}

/* 扫描监视点，可选择打印/更新值。
 * show_all : 为真时 => 打印所有监视点 (info w)
 * update_val : 为真时 => 当值变化时更新 wp->old_val (检查模式)
 * 如果有任何监视点的值发生变化（触发），返回 true
 */
static bool scan_watchpoints(bool show_all, bool update_val)
{
  WP *wp = head;
  if (wp == NULL && show_all)
  {
    printf("没有监视点。\n");
    return false;
  }

  /* 将活动监视点收集到数组中 */
  WP *arr[NR_WP];
  int cnt = 0;
  for (WP *it = head; it != NULL && cnt < NR_WP; it = it->next)
  {
    arr[cnt++] = it;
  }

  /* 按编号升序排序 */
  if (cnt > 1)
  {
    qsort(arr, (size_t)cnt, sizeof(WP *), wp_compare_by_no);
  }

  /* 计算表格宽度。按终端显示宽度计算，并忽略 ANSI 颜色码。 */
  int word_width = (int)(sizeof(word_t) * 2 + 2);
  int val_width = word_width;
  int delta_width = word_width + 1;
  int no_width = display_width("编号");
  int type_width = display_width("类型");
  int enable_width = display_width("启用状态");
  int status_width = display_width("状态");
  int trigger_width = display_width("触发位置");
  int expr_width = display_width("表达式");

  if (display_width("寄存器") > type_width)
    type_width = display_width("寄存器");
  if (display_width("解引用") > type_width)
    type_width = display_width("解引用");
  if (display_width("表达式") > type_width)
    type_width = display_width("表达式");
  if (display_width("常量") > type_width)
    type_width = display_width("常量");

  if (display_width("启用") > enable_width)
    enable_width = display_width("启用");
  if (display_width("禁用") > enable_width)
    enable_width = display_width("禁用");

  if (display_width("已变化") > status_width)
    status_width = display_width("已变化");
  if (display_width("正常") > status_width)
    status_width = display_width("正常");
  if (display_width("无效") > status_width)
    status_width = display_width("无效");
  if (display_width("停用") > status_width)
    status_width = display_width("停用");

  for (int idx = 0; idx < cnt; idx++)
  {
    WP *cur = arr[idx];
    char no_plain[32];
    snprintf(no_plain, sizeof(no_plain), "%d", cur->NO);

    if (display_width(no_plain) > no_width)
      no_width = display_width(no_plain);
    if (display_width(get_watchpoint_type_name(cur->expr)) > type_width)
      type_width = display_width(get_watchpoint_type_name(cur->expr));
    if (display_width(cur->expr) > expr_width)
      expr_width = display_width(cur->expr);
  }

  if (word_width > trigger_width)
    trigger_width = word_width;

  int col_widths[] = {
      no_width,
      type_width,
      val_width,
      val_width,
      delta_width,
      enable_width,
      status_width,
      trigger_width,
      expr_width,
  };

  bool header_printed = false;
  bool any_triggered = false;
  word_t old_vals[NR_WP] = {};
  word_t current_vals[NR_WP] = {};
  bool eval_success[NR_WP] = {};
  bool changed_flags[NR_WP] = {};

  for (int idx = 0; idx < cnt; idx++)
  {
    WP *cur = arr[idx];
    old_vals[idx] = cur->old_val;

    if (!cur->enabled)
    {
      eval_success[idx] = false;
      current_vals[idx] = old_vals[idx];
      changed_flags[idx] = false;
      continue;
    }

    sword_t tmp = expr(cur->expr, &eval_success[idx]);
    current_vals[idx] = (word_t)tmp;
    changed_flags[idx] = eval_success[idx] && (current_vals[idx] != old_vals[idx]);
  }

  for (int idx = 0; idx < cnt; idx++)
  {
    if (arr[idx]->enabled && eval_success[idx] && changed_flags[idx])
    {
      any_triggered = true;
      if (update_val)
      {
        arr[idx]->last_trigger_pc = cpu.pc;
        arr[idx]->has_last_trigger = true;
        arr[idx]->old_val = current_vals[idx];
      }
    }
  }

  for (int idx = 0; idx < cnt; idx++)
  {
    WP *cur = arr[idx];
    bool success = eval_success[idx];
    word_t old_val = old_vals[idx];
    word_t current_val = current_vals[idx];
    bool changed = changed_flags[idx];
    const char *type_name = get_watchpoint_type_name(cur->expr);

    if (cur->enabled && !show_all && !success)
    {
      printf("警告：无法计算监视点 %d 的值：%s\n", cur->NO, cur->expr);
      continue;
    }

    if (!(show_all || changed))
    {
      continue;
    }

    /* 所有表达式先求值完，再统一打印，避免调试日志插入表格中间。 */
    if (!header_printed)
    {
      char no_header[32], type_header[32], old_header[32], new_header[32], delta_header[32];
      char enable_header[32], status_header[32], trigger_header[32], expr_header[32];

      snprintf(no_header, sizeof(no_header), "%s编号%s", get_watchpoint_header_color("编号"), ANSI_NONE);
      snprintf(type_header, sizeof(type_header), "%s类型%s", get_watchpoint_header_color("类型"), ANSI_NONE);
      snprintf(old_header, sizeof(old_header), "%s旧值%s", get_watchpoint_header_color("旧值"), ANSI_NONE);
      snprintf(new_header, sizeof(new_header), "%s新值%s", get_watchpoint_header_color("新值"), ANSI_NONE);
      snprintf(delta_header, sizeof(delta_header), "%s变化量%s", get_watchpoint_header_color("变化量"), ANSI_NONE);
      snprintf(enable_header, sizeof(enable_header), "%s启用状态%s", get_watchpoint_header_color("启用状态"), ANSI_NONE);
      snprintf(status_header, sizeof(status_header), "%s状态%s", get_watchpoint_header_color("状态"), ANSI_NONE);
      snprintf(trigger_header, sizeof(trigger_header), "%s触发位置%s", get_watchpoint_header_color("触发位置"), ANSI_NONE);
      snprintf(expr_header, sizeof(expr_header), "%s表达式%s", get_watchpoint_header_color("表达式"), ANSI_NONE);

      if (!show_all)
        printf("\n监视点已触发：\n");
      print_table_border(col_widths, ARRLEN(col_widths));
      putchar('|');
      print_table_cell(no_header, no_width, true);
      print_table_cell(type_header, type_width, false);
      print_table_cell(old_header, val_width, true);
      print_table_cell(new_header, val_width, true);
      print_table_cell(delta_header, delta_width, true);
      print_table_cell(enable_header, enable_width, false);
      print_table_cell(status_header, status_width, false);
      print_table_cell(trigger_header, trigger_width, true);
      print_table_cell(expr_header, expr_width, false);
      putchar('\n');
      print_table_border(col_widths, ARRLEN(col_widths));
      header_printed = true;
    }

    char no_str[32], type_str[32], old_str[64], cur_str[64], delta_str[64];
    char enable_str[32], status_str[32], trigger_str[64], expr_str[64];
    snprintf(no_str, sizeof(no_str), "%s%d%s",
             get_watchpoint_no_color(cur->enabled, success, changed), cur->NO, ANSI_NONE);
    snprintf(type_str, sizeof(type_str), "%s%s%s", get_watchpoint_type_color(type_name), type_name, ANSI_NONE);
    snprintf(old_str, sizeof(old_str), ANSI_FG_WHITE FMT_WORD ANSI_NONE, old_val);
    snprintf(expr_str, sizeof(expr_str), "%s%s%s",
             get_watchpoint_expr_color(type_name, cur->enabled, success, changed), cur->expr, ANSI_NONE);
    format_trigger_pc_str(trigger_str, sizeof(trigger_str), cur);

    if (!cur->enabled)
    {
      snprintf(cur_str, sizeof(cur_str), ANSI_FG_WHITE "-" ANSI_NONE);
      snprintf(enable_str, sizeof(enable_str), ANSI_FG_RED "禁用" ANSI_NONE);
      snprintf(status_str, sizeof(status_str), ANSI_FG_MAGENTA "停用" ANSI_NONE);
      format_delta_str(delta_str, sizeof(delta_str), false, false, old_val, current_val);
    }
    else if (!success)
    {
      snprintf(cur_str, sizeof(cur_str), ANSI_FG_RED "N/A" ANSI_NONE);
      snprintf(enable_str, sizeof(enable_str), ANSI_FG_GREEN "启用" ANSI_NONE);
      snprintf(status_str, sizeof(status_str), ANSI_FG_RED "无效" ANSI_NONE);
      format_delta_str(delta_str, sizeof(delta_str), true, false, old_val, current_val);
    }
    else
    {
      snprintf(enable_str, sizeof(enable_str), ANSI_FG_GREEN "启用" ANSI_NONE);
      format_delta_str(delta_str, sizeof(delta_str), true, true, old_val, current_val);
      if (changed)
      {
        snprintf(cur_str, sizeof(cur_str), ANSI_FG_YELLOW FMT_WORD ANSI_NONE, current_val);
        snprintf(status_str, sizeof(status_str), ANSI_FG_YELLOW "已变化" ANSI_NONE);
      }
      else
      {
        snprintf(cur_str, sizeof(cur_str), ANSI_FG_GREEN FMT_WORD ANSI_NONE, current_val);
        snprintf(status_str, sizeof(status_str), ANSI_FG_GREEN "正常" ANSI_NONE);
      }
    }

    putchar('|');
    print_table_cell(no_str, no_width, true);
    print_table_cell(type_str, type_width, false);
    print_table_cell(old_str, val_width, true);
    print_table_cell(cur_str, val_width, true);
    print_table_cell(delta_str, delta_width, true);
    print_table_cell(enable_str, enable_width, false);
    print_table_cell(status_str, status_width, false);
    print_table_cell(trigger_str, trigger_width, true);
    print_table_cell(expr_str, expr_width, false);
    putchar('\n');
    print_table_border(col_widths, ARRLEN(col_widths));
  }

  return any_triggered;
}
