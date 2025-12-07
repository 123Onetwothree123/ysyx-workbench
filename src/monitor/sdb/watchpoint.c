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
#include <memory/paddr.h>

#define NR_WP 32

typedef struct watchpoint
{
  int NO;
  struct watchpoint *next;

  /* TODO: Add more members if necessary */
  char expr[32];    // The expression for monitoring
  uint32_t old_val; // The value of the last time
} WP;

static WP wp_pool[NR_WP] = {};
static WP *head = NULL, *free_ = NULL;

WP *new_wp();
void free_wp(WP *wp);
extern const char *expr_get_error_msg();
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
  WP *wp = head;
  if (wp == NULL)
  {
    printf("No watchpoints.\n");
    return;
  }
  // Define the base width for each column
  int hex_len = sizeof(word_t) * 2 + 2;        // Length of 0x...
  int val_width = (hex_len > 9) ? hex_len : 9; // Value column width
  int no_width = 4;                            // NO column width
  int status_width = 14;                       // STATUS column width (wide enough to hold "Invalid Addr")
  int expr_width = 32;                         // EXPRESSION column width
  //int col_width = (hex_len > 9) ? hex_len : 9;
  // Calculate the total table length
  // Format structure: "| NO | OLD | NEW | STATUS | EXPR |"
  // Separator occupancy: "| " (2) + " | " (3)*4 + " |" (2) = 16 characters
  int total_len = 16 + no_width + (val_width * 2) + status_width + expr_width;
  //Helper macro: print a dynamic-length separator line
  #define PRINT_DIVIDER() do { for(int _i=0; _i<total_len; _i++) putchar('-'); putchar('\n'); } while(0)
  PRINT_DIVIDER();
  printf("| %-*s | %-*s | %-*s | %-*s | %-*s |\n",
         no_width, "NO",
         val_width, "OLD VALUE",
         val_width, "NEW VALUE",
         status_width, "STATUS",
         expr_width, "EXPRESSION");
  PRINT_DIVIDER();

  while (wp != NULL)
  {
    bool success = false;
    sword_t current_val_signed = expr((char *)wp_get_expr(wp), &success);
    word_t current_val = (word_t)current_val_signed;
    word_t old_val = wp_get_value(wp);
    char old_str[32];
    char cur_str[32];
    const char *status_str;

#ifdef CONFIG_ISA64
#define V_FMT "0x%016lx"
#else
#define V_FMT "0x%08x"
#endif

    snprintf(old_str, 32, V_FMT, old_val);

    if (!success)
    {
      snprintf(cur_str, 32, "N/A");
      status_str = expr_get_error_msg();
    }
    else
    {
      snprintf(cur_str, 32, V_FMT, current_val);
      if (current_val != old_val)
      {
        status_str = "\033[1;33mCHANGED\033[0m"; // 黄色
      }
      else
      {
        status_str = "\033[1;32mOK\033[0m"; // 绿色
      }
    }

    // 4. 处理 ANSI 颜色导致的对齐问题
    // 如果字符串包含颜色代码（以 ESC 开头），printf 宽度需要增加
    // 标准颜色格式 \033[...m ... \033[0m 通常包含 11 个不可见字符
    int status_fmt_width = status_width;
    if (status_str && status_str[0] == '\033') {
        status_fmt_width += 11; 
    }

    printf("| %-*d | %-*s | %-*s | %-*s | %-*s |\n", 
           no_width, wp_get_no(wp), 
           val_width, old_str, 
           val_width, cur_str, 
           status_fmt_width, status_str,
           expr_width, wp_get_expr(wp));
           
    wp = wp_get_next(wp);
  }
  PRINT_DIVIDER();
  #undef PRINT_DIVIDER
}
WP *new_wp()
{
  if (free_ == NULL)
  {
    printf("free_ == NULL, I need use assert(free_ != NULL) end this program\n");
  }
  // check NULL
  assert(free_ != NULL);
  WP *wp = free_;
  free_ = free_->next;
  wp->NO = wp - wp_pool;
  wp->next = head;
  head = wp;
  return wp;
}
void free_wp(WP *wp)
{
  // check null args
  if (wp == NULL)
  {
    printf("Warning: Attempt to free a NULL watchpoint\n");
    return;
  }
  if (head == NULL)
  {
    printf("Warning: No watchpoints in use, cannot free WP #%d\n", wp->NO);
    return;
  }
  if (wp->NO < 0 || wp->NO >= NR_WP)
  {
    printf("Warning: Invalid watchpoint ID %d\n", wp->NO);
    return;
  }
  WP *previous = NULL;
  WP *current = head;
  // search goal node and before node
  while (current != NULL && current != wp)
  {
    previous = current;
    current = current->next;
  }
  // if not found this node
  if (current == NULL)
  {
    printf("Warning: Watchpoint #%d is not in the active list\n", wp->NO);
    return;
  }
  if (previous == NULL)
  {
    head = wp->next; // head point next node
  }
  else if (previous->next == wp)
  {
    previous->next = wp->next;
  }
  else
  {
    printf("I do not know watchpoint.c list happend something\n");
    printf("Maybe WP#%d not reachable from prev node\n", wp->NO);
    assert(0); // direct exit
  }
  // clear data
  memset(wp->expr, 0, sizeof(wp->expr));
  wp->old_val = 0;
  wp->NO = -1;
  wp->next = free_;
  free_ = wp;
}
void wp_set_expr(WP *wp, const char *expr)
{
  if (wp == NULL || expr == NULL)
  {
    printf("watchpoint.c wp_set_expr function wp or expr ==NULL\n");
    return;
  }
  strncpy(wp->expr, expr, sizeof(wp->expr) - 1);
  wp->expr[sizeof(wp->expr) - 1] = '\0';
  if (strlen(expr) >= sizeof(wp->expr))
  {
    printf("Warning: Expression truncated to %zu characters\n", sizeof(wp->expr) - 1);
  }
}
const char *wp_get_expr(const WP *wp)
{
  return wp ? wp->expr : NULL;
}
void wp_set_value(WP *wp, uint32_t value)
{
  if (wp)
  {
    wp->old_val = value;
  }
}
uint32_t wp_get_value(const WP *wp)
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
  WP *wp = wp_get_head();
  bool any_triggered = false;
  bool header_printed = false;
#ifdef CONFIG_ISA64
#define V_FMT "0x%016lx"
#define COL_WIDTH 18
#else
#define V_FMT "0x%08x"
#define COL_WIDTH 11
#endif
  while (wp != NULL)
  {
    bool success = false;
    sword_t current_val_signed = expr((char *)wp_get_expr(wp), &success);
    if (!success)
    {
      printf("Warning: Failed to evaluate watchpoint %d: %s (Error: %s)\n",
             wp_get_no(wp), wp_get_expr(wp), expr_get_error_msg());
      wp = wp_get_next(wp);
      continue;
    }
    word_t current_val = (word_t)current_val_signed;
    word_t old_val = wp_get_value(wp);
    if (current_val != old_val)
    {
      if (!header_printed)
      {
        printf("\nWatchpoint triggered:\n");
        printf("-----------------------------------------------------------------------\n");
        printf("| %-4s | %-18s | %-18s | %-10s |\n",
               "NO", "OLD VALUE", "NEW VALUE", "EXPRESSION");
        printf("-----------------------------------------------------------------------\n");
        header_printed = true;
      }
      printf("| %-4d | " V_FMT " | " V_FMT " | %-10s |\n",
             wp_get_no(wp), old_val, current_val, wp_get_expr(wp));
      wp_set_value(wp, current_val);
      any_triggered = true;
    }
    wp = wp_get_next(wp);
  }
  if (header_printed)
  {
    printf("-----------------------------------------------------------------------\n");
    printf("Program stopped due to watchpoint change.\n");
  }
  return any_triggered;
}
bool safe_paddr_read(word_t addr, word_t *result, size_t size)
{
  if (result == NULL)
  {
    Log("safe_paddr_read: NULL result pointer");
    return false;
  }
  if (size == 0 || size > sizeof(word_t))
  {
    Log("safe_paddr_read: Invalid size %zu", size);
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