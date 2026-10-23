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

#ifndef __RISCV_FPU_H__
#define __RISCV_FPU_H__

#include <common.h>

struct Decode;

#ifdef CONFIG_RISCV_F
/*
 * OP-FP/FMA/LOAD-FP/STORE-FP 的集中解码入口。返回 true 表示该指令属于
 * 浮点扩展并已处理（包括非法编码陷入），false 表示不是浮点 opcode。
 */
bool riscv_fp_decode_exec(struct Decode *s);

/* 供 RVC 的 C.FLD/C.FLW/C.FSD/C.FSW 复用，size 为 4 或 8。 */
void riscv_fp_load(struct Decode *s, vaddr_t addr, int size, int rd);
void riscv_fp_store(struct Decode *s, vaddr_t addr, int size, int rs2);
#endif

#endif
