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

# F/D 扩展使用仓库内的 Berkeley SoftFloat（RISC-V 特殊化）。
# 源码放在 tools/ 下以保持第三方代码与 NEMU 自身代码分离；NEMU 的
# filelist 只从 src/ 收集 DIRS，因此这里显式加入源文件和头文件路径。
ifeq ($(CONFIG_RISCV_F),y)
SOFTFLOAT_HOME := $(NEMU_HOME)/tools/softfloat
INC_PATH += $(SOFTFLOAT_HOME)/include $(SOFTFLOAT_HOME)/source/RISCV $(SOFTFLOAT_HOME)
SRCS-y += $(patsubst $(NEMU_HOME)/%,%,$(wildcard $(SOFTFLOAT_HOME)/source/*.c))
SRCS-y += $(patsubst $(NEMU_HOME)/%,%,$(wildcard $(SOFTFLOAT_HOME)/source/RISCV/*.c))
endif
