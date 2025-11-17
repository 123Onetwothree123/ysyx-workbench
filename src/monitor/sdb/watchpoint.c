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
  if (head == NULL)
  {
    printf("monitor watchpoint file PrintWatchPoint function check, detect head==NULL\n");
    printf("No watchpoints are set.\n");
    return; // this return can direct return this function.
  }
  printf("Num\tExpr\t\tValue\n");
  printf("----\t----\t\t-----\n");
  WP *p = head;
  while (p != NULL)
  {
    printf("%d\t%s\t\t0x%08x\n", p->NO, p->expr, p->old_val);
    p = p->next;
  }
}
WP *new_wp()
{
  // check NULL
  assert(free_ != NULL);
  WP *wp = free_;
  free_ = free_->next;
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
  wp->expr[0] = '\0';
  wp->old_val = 0;
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
  size_t dest_size = sizeof(wp->expr);
  size_t src_len = strlen(expr);
  if (src_len >= dest_size)
  {
    printf("Warning: Expression truncated from %zu to %zu characters\n", src_len, dest_size - 1);
    src_len = dest_size - 1;
  }
  memcpy(wp->expr, expr, src_len);
  wp->expr[src_len] = '\0';
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
bool wp_check_triggered(const WP *wp)
{
  if (!check_null_pointer(wp, "watchpoint"))
  {
    return false;
  }
  // todo, now set and return bool false
  return false;
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
  WP *target_wp = &wp_pool[id];
  WP *current = head;
  while (current != NULL)
  {
    if (current == target_wp)
    {
      return target_wp;
    }
    current = current->next;
  }
  return NULL;
}