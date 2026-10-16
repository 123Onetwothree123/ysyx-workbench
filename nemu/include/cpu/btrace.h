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

// kind: 'b'=条件分支, 'j'=jal(非调用), 'c'=jal x1/x5直接调用,
//       'r'=jalr只弹RAS, 'i'=jalr只压RAS, 'o'=x1/x5协程弹后压,
//       'x'=无RAS hint的其他jalr。jalr_imm是符号扩展后的32位位模式。
void btrace_write(vaddr_t pc, vaddr_t target, bool taken, char kind,
                  word_t jalr_imm);

#define BTRACE_BRANCH(s, offset, cond) do { \
    vaddr_t _target = (s)->pc + (offset); \
    if (cond) { \
        (s)->dnpc = _target; \
        btrace_write((s)->pc, _target, true, 'b', 0); \
    } else { \
        btrace_write((s)->pc, _target, false, 'b', 0); \
    } \
} while(0)

#define BTRACE_JAL(s, offset, rd) do { \
    (s)->dnpc = (s)->pc + (offset); \
    btrace_write((s)->pc, (s)->dnpc, true, \
                 ((rd) == 1 || (rd) == 5) ? 'c' : 'j', 0); \
} while(0)

// 与RTL的RISC-V RAS hint表一致：x1和x5都是link register，
// 异名link register的jalr要同拍pop-then-push。
#define BTRACE_JALR(s, _rs1, _rd, _imm) do { \
    char _kind = 'x'; \
    bool _rd_link = ((_rd) == 1 || (_rd) == 5); \
    bool _rs1_link = ((_rs1) == 1 || (_rs1) == 5); \
    bool _pop = _rs1_link && (!_rd_link || (_rd) != (_rs1)); \
    bool _push = _rd_link; \
    if (_pop && _push) _kind = 'o'; \
    else if (_pop) _kind = 'r'; \
    else if (_push) _kind = 'i'; \
    btrace_write((s)->pc, (s)->dnpc, true, _kind, (word_t)(_imm)); \
} while(0)

#else

#define BTRACE_BRANCH(s, offset, cond) \
    do { if (cond) (s)->dnpc = (s)->pc + (offset); } while(0)

#define BTRACE_JAL(s, offset, rd) \
    do { (s)->dnpc = (s)->pc + (offset); } while(0)

#define BTRACE_JALR(s, _rs1, _rd, _imm) do { } while(0)

#endif

#endif
