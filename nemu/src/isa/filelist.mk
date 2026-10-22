#***************************************************************************************
# Copyright (c) 2014-2024 Zihao Yu, Nanjing University
#
# NEMU is licensed under Mulan PSL v2.
# You can use this software according to the terms and conditions of the Mulan PSL v2.
# You may obtain a copy of Mulan PSL v2 at:
#          http://license.coscl.org.cn/MulanPSL2
#
# THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
# EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
# MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#
# See the Mulan PSL v2 for more details.
#**************************************************************************************/

# RV32/RV64 共用同一套 RISC-V 实现。Kconfig 里的 GUEST_ISA 分别为
# riscv32/riscv64（影响二进制名和 __GUEST_ISA__），但源代码目录仍是
# src/isa/riscv32。这里做一次显式映射，避免再维护一份重复实现。
RISCV_ISA_DIR := $(if $(filter riscv32 riscv64,$(GUEST_ISA)),riscv32,$(GUEST_ISA))

INC_PATH += $(NEMU_HOME)/src/isa/$(RISCV_ISA_DIR)/include
DIRS-y += src/isa/$(RISCV_ISA_DIR)
