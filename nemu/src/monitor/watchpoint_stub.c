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

#include "sdb/sdb.h"

#if defined(CONFIG_TARGET_AM) && defined(CONFIG_WATCHPOINT)

struct watchpoint
{
  int NO;
  struct watchpoint *next;
};

static const char am_watchpoint_stub_error[] = "watchpoint is unavailable on CONFIG_TARGET_AM";

word_t expr(char *e, bool *success)
{
  (void)e;
  if (success != NULL)
  {
    *success = false;
  }
  return 0;
}

WP *new_wp()
{
  return NULL;
}

void free_wp(WP *wp)
{
  (void)wp;
}

void PrintWatchPoint()
{
}

void wp_set_expr(WP *wp, const char *expr_text)
{
  (void)wp;
  (void)expr_text;
}

const char *wp_get_expr(const WP *wp)
{
  (void)wp;
  return am_watchpoint_stub_error;
}

void wp_set_value(WP *wp, word_t value)
{
  (void)wp;
  (void)value;
}

word_t wp_get_value(const WP *wp)
{
  (void)wp;
  return 0;
}

void wp_set_no(WP *wp, int no)
{
  (void)wp;
  (void)no;
}

int wp_get_no(const WP *wp)
{
  (void)wp;
  return -1;
}

void wp_set_next(WP *wp, WP *next)
{
  (void)wp;
  (void)next;
}

WP *wp_get_next(const WP *wp)
{
  (void)wp;
  return NULL;
}

int get_max_watchpoints(void)
{
  return 0;
}

WP *wp_get_head(void)
{
  return NULL;
}

WP *find_wp_by_id(int id)
{
  (void)id;
  return NULL;
}

bool check_array_bounds(int index, int array_size)
{
  return index >= 0 && index < array_size;
}

bool check_null_pointer(const void *ptr, const char *name)
{
  (void)name;
  return ptr != NULL;
}

bool check_string_length(const char *str, size_t max_len, const char *name)
{
  (void)name;
  if (str == NULL)
  {
    return false;
  }
  return strlen(str) <= max_len;
}

bool check_watchpoints(void)
{
  return false;
}

bool safe_paddr_read(word_t addr, word_t *result, size_t size)
{
  (void)addr;
  (void)size;
  if (result != NULL)
  {
    *result = 0;
  }
  return false;
}

bool validate_expression_syntax(const char *expression)
{
  (void)expression;
  return false;
}

bool check_memory_address(word_t addr)
{
  (void)addr;
  return false;
}

const char *expr_get_error_msg()
{
  return am_watchpoint_stub_error;
}

void print_watchpoint_stop_msg(void)
{
}

#endif
