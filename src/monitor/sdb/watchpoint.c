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
#include <stdlib.h>

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

static int wp_compare_by_no(const void *a, const void *b);
static char first_nonspace_char(const char *s);
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
  // Mode: Only show changes, update values
  bool triggered = scan_watchpoints(false, true);

  if (triggered)
  {
    printf("Program stopped due to watchpoint change.\n");
  }
  return triggered;
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

/* Helper: skip leading whitespace, return the first non-space character, or '\0' */
static char first_nonspace_char(const char *s)
{
  if (!s)
    return '\0';
  while (*s != '\0' && (*s == ' ' || *s == '\t'))
    s++;
  return *s;
}
/* Scan watchpoints and optionally print / update values.
 * show_all : when true => print all watchpoints (info w)
 * update_val : when true => update wp->old_val when value changed (check mode)
 * return true if any watchpoint value changed (triggered)
 */
static bool scan_watchpoints(bool show_all, bool update_val)
{
  WP *wp = head;
  if (wp == NULL && show_all)
  {
    printf("No watchpoints.\n");
    return false;
  }

  /* Calculate format width */
  int hex_len = sizeof(word_t) * 2 + 2;
  int val_width = (hex_len > 9) ? hex_len : 9;
  int no_width = 4;
  int status_width = 14;
  int expr_width = 32;
  int total_len = 16 + no_width + (val_width * 2) + status_width + expr_width;

#define PRINT_DIVIDER()                    \
  do                                       \
  {                                        \
    for (int _i = 0; _i < total_len; _i++) \
      putchar('-');                        \
    putchar('\n');                         \
  } while (0)

  /* Collect active watchpoints into an array */
  WP *arr[NR_WP];
  int cnt = 0;
  for (WP *it = head; it != NULL && cnt < NR_WP; it = it->next)
  {
    arr[cnt++] = it;
  }

  /* Sort in ascending order of NO */
  if (cnt > 1)
  {
    qsort(arr, (size_t)cnt, sizeof(WP *), wp_compare_by_no);
  }

  bool header_printed = false;
  bool any_triggered = false;

  for (int idx = 0; idx < cnt; idx++)
  {
    WP *cur = arr[idx];
    bool success = false;
    word_t current_val = 0;

    /* Safe evaluation logic */
    if (show_all)
    {
      char fc = first_nonspace_char(cur->expr);
      if (fc == '*')
        success = false;
      else
      {
        sword_t tmp = expr(cur->expr, &success);
        current_val = (word_t)tmp;
      }
    }
    else
    {
      sword_t tmp = expr(cur->expr, &success);
      current_val = (word_t)tmp;
    }

    word_t old_val = cur->old_val;
    bool changed = success && (current_val != old_val);

    if (!show_all && !success)
    {
      printf("Warning: Failed to evaluate watchpoint %d: %s\n", cur->NO, cur->expr);
      continue;
    }

    /* Print Logic */
    if (show_all || changed)
    {
      if (!header_printed)
      {
        if (!show_all)
          printf("\nWatchpoint triggered:\n");
        PRINT_DIVIDER();

        /*
         * Header with BLUE Color (\033[1;34m)
         * We add 11 to the width: 7 bytes for start code + 4 bytes for reset code
         */
        printf("| %-*s | %-*s | %-*s | %-*s | %-*s |\n",
               no_width + 11, "\033[1;34mNO\033[0m",
               val_width + 11, "\033[1;34mOLD VALUE\033[0m",
               val_width + 11, "\033[1;34mNEW VALUE\033[0m",
               status_width + 11, "\033[1;34mSTATUS\033[0m",
               expr_width + 11, "\033[1;34mEXPRESSION\033[0m");

        PRINT_DIVIDER();
        header_printed = true;
      }

      char old_str[32], cur_str[32];
      const char *status_str;
#ifdef CONFIG_ISA64
#define V_FMT "0x%016lx"
#else
#define V_FMT "0x%08x"
#endif
      char no_str[32];
      snprintf(no_str, sizeof(no_str), ANSI_FG_CYAN "%d" ANSI_NONE, cur->NO);
      snprintf(old_str, sizeof(old_str), V_FMT, old_val);

      /* Row Colors: Red for Invalid, Yellow/Green for Status */
      if (!success)
      {
        snprintf(cur_str, sizeof(cur_str), "N/A");
        status_str = "\033[1;31mInvalid\033[0m";
      }
      else
      {
        snprintf(cur_str, sizeof(cur_str), V_FMT, current_val);
        status_str = changed ? "\033[1;33mCHANGED\033[0m" : "\033[1;32mOK\033[0m";
      }

      /* Compensation for row status color codes */
      int status_fmt_width = status_width;
      if (status_str[0] == '\033')
        status_fmt_width += 11;

      printf("| %-*s | %-*s | %-*s | %-*s | %-*s |\n",
             no_width + 11, no_str,
             val_width, old_str,
             val_width, cur_str,
             status_fmt_width, status_str,
             expr_width, cur->expr);
    }

    if (success && changed)
    {
      if (update_val)
        cur->old_val = current_val;
      any_triggered = true;
    }
  }

  if (header_printed)
  {
    PRINT_DIVIDER();
  }

#undef PRINT_DIVIDER
  return any_triggered;
}