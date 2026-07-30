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

// kind: 'b'=条件分支, 'j'=jal(非调用), 'c'=jal ra直接调用,
//       'r'=ret(jalr x0,x1,0), 'i'=jalr ra间接调用, 'x'=其他jalr
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

#define BTRACE_JAL(s, offset, rd) do { \
    (s)->dnpc = (s)->pc + (offset); \
    btrace_write((s)->pc, (s)->dnpc, true, (rd) == 1 ? 'c' : 'j'); \
} while(0)

// jalr目标运行时才知道, 但类型可以区分: ret弹RAS, 间接call压RAS, 其他不预测
#define BTRACE_JALR(s, _rs1, _rd, _imm) do { \
    char _kind = 'x'; \
    if ((_rd) == 0 && (_rs1) == 1 && (_imm) == 0) _kind = 'r'; \
    else if ((_rd) == 1) _kind = 'i'; \
    btrace_write((s)->pc, (s)->dnpc, true, _kind); \
} while(0)

#else

#define BTRACE_BRANCH(s, offset, cond) \
    do { if (cond) (s)->dnpc = (s)->pc + (offset); } while(0)

#define BTRACE_JAL(s, offset, rd) \
    do { (s)->dnpc = (s)->pc + (offset); } while(0)

#define BTRACE_JALR(s, _rs1, _rd, _imm) do { } while(0)

#endif

#endif