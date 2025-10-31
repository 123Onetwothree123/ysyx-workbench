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

typedef struct watchpoint {
  int NO;
  struct watchpoint *next;

  /* TODO: Add more members if necessary */
  char expr[32];//The expression for monitoring
  uint32_t old_val;//The value of the last time
} WP;

static WP wp_pool[NR_WP] = {};
static WP *head = NULL, *free_ = NULL;

void init_wp_pool() {
  int i;
  for (i = 0; i < NR_WP; i ++) {
    wp_pool[i].NO = i;
    wp_pool[i].next = (i == NR_WP - 1 ? NULL : &wp_pool[i + 1]);
  }

  head = NULL;
  free_ = wp_pool;
}

/* TODO: Implement the functionality of watchpoint */
/**
 * @brief Print all currently set watchpoint information
 *
 * Traverse the linked list of active watchpoints and print each watchpoint's
 * index, expression, and current value.
 * If no watchpoints are set, print a message indicating this.
 */
void PrintWatchPoint(){
  if (head == NULL) {
    printf("monitor watchpoint file PrintWatchPoint function check, detect head==NULL\n");
    printf("No watchpoints are set.\n");
    return;//this return can direct return this function.
  }
  printf("Num\tExpr\t\tValue\n");
  printf("----\t----\t\t-----\n");
  WP *p = head;
  while (p!=NULL)
  {
    printf("%d\t%s\t\t0x%08x\n", p->NO, p->expr, p->old_val);
    p = p->next;
  }
}