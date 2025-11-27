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

#ifndef __SDB_H__
#define __SDB_H__

#include <common.h>

sword_t expr(char *e, bool *success);
typedef struct watchpoint WP;
WP *new_wp();
void free_wp(WP *wp);
void PrintWatchPoint();

// Accessor function
void wp_set_expr(WP *wp, const char *expr);
const char *wp_get_expr(const WP *wp);
void wp_set_value(WP *wp, uint32_t value);
uint32_t wp_get_value(const WP *wp);
void wp_set_no(WP *wp, int no);
int wp_get_no(const WP *wp);
// Linked list operation
void wp_set_next(WP *wp, WP *next);
WP *wp_get_next(const WP *wp);

int get_max_watchpoints(void);
WP *wp_get_head(void);

WP *find_wp_by_id(int id);

// check function
bool check_array_bounds(int index, int array_size);                          // check index and range
bool check_null_pointer(const void *ptr, const char *name);                  // check nullptr
bool check_memory_address(word_t addr);                                      // check memory address effective
bool check_string_length(const char *str, size_t max_len, const char *name); // check string length

// cpu_exec.c function
//  Check all watchpoints, return whether any watchpoint is triggered
bool check_watchpoints(void);
#endif