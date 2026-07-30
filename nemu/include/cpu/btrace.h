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

#ifndef __CPU_BTRACE_H__
#define __CPU_BTRACE_H__

#include <isa.h>

#ifdef CONFIG_BTRACE

// kind: 'b'=条件分支, 'j'=jal; jalr/ret 需要RAS, 暂不记录
void btrace_write(vaddr_t pc, vaddr_t target, bool taken, char kind);

#define BTRACE_BRANCH(s, offset, cond) do { \
    vaddr_t _target = (s)->pc + (offset); \
    if (cond) { \
        (s)->dnpc = _target; \
        btrace_write((s)->pc, _target, true, 'b'); \
    } else { \
        btrace_write((s)->pc, _target, false, 'b'); \
    } \
} while(0)

#define BTRACE_JAL(s, offset) do { \
    (s)->dnpc = (s)->pc + (offset); \
    btrace_write((s)->pc, (s)->dnpc, true, 'j'); \
} while(0)

#else

#define BTRACE_BRANCH(s, offset, cond) \
    do { if (cond) (s)->dnpc = (s)->pc + (offset); } while(0)

#define BTRACE_JAL(s, offset) \
    do { (s)->dnpc = (s)->pc + (offset); } while(0)

#endif

#endif