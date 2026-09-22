NAME = locale-wide-test
SRCS = main.cpp include-order.cpp
include $(AM_HOME)/Makefile

cpp-smoke:
	$(CXX) -march=rv32im_zicsr -mabi=ilp32 -std=gnu++17 \
		-ffreestanding -fno-exceptions -fno-rtti \
		-Wall -Wextra -Werror -fsyntax-only \
		-I$(AM_HOME)/klib/include -I$(AM_HOME)/am/include \
		-D__ISA__=\"riscv32\" -D__ISA_RISCV32__ \
		-DARCH_H=\"arch/riscv.h\" cpp-header-smoke.cpp
.PHONY: cpp-smoke
