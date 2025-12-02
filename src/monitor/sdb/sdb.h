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

#include <stdbool.h>
#include <string.h>
#include <ctype.h>
#include <assert.h>
#include <generated/autoconf.h>
#include <common.h>

#define MAX_MEMORY_REGIONS 16
typedef enum
{
    MEM_TYPE_RAM,      // Main memory
    MEM_TYPE_ROM,      // Read-only memory
    MEM_TYPE_MMIO,     // Memory-mapped IO
    MEM_TYPE_RESERVED, // Reserved region
    MEM_TYPE_UNKNOWN   // Unknown type
} MemoryType;
typedef struct
{
    word_t start;
    word_t end;
    bool is_readable;
    bool is_writable;
    bool is_executable;
    MemoryType type;
    const char *name;
    const char *description;
} MemoryRegion;
// Global memory region table (dynamically populated at runtime)
extern MemoryRegion g_memory_regions[MAX_MEMORY_REGIONS];
extern int g_region_count;

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

bool safe_paddr_read(word_t addr, word_t *result, size_t size);
bool is_valid_memory_region(word_t addr, size_t size);
bool validate_expression_memory_access(char *args);
bool add_custom_memory_region(word_t start, word_t end, MemoryType type, bool readable, bool writable, bool executable, const char *name, const char *description);// Dynamically add custom memory region
void auto_configure_memory_regions(void);
const MemoryRegion *query_memory_region(word_t addr); // Query memory region information at runtime
bool validate_address_flexible(word_t addr, size_t size, bool is_write);
bool validate_expression_memory_access_flexible(const char *expression);

const char* expr_get_error_msg();
#endif