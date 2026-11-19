NAME = locale-wide-test
SRCS = main.cpp include-order.cpp

# cpp-smoke 的 ISA 位宽跟随 Kconfig，不再写死 riscv32。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../xlen.mk)
ARCH ?= $(XLEN_AM_ARCH)
export ARCH
ifeq ($(XLEN_IS_64),64)
CPP_SMOKE_DEFS := -D__ISA__=\"riscv64\" -D__ISA_RISCV64__
else
CPP_SMOKE_DEFS := -D__ISA__=\"riscv32\" -D__ISA_RISCV32__
endif

include $(AM_HOME)/Makefile

cpp-smoke:
	$(CXX) -march=$(XLEN_GUEST_MARCH) -mabi=$(XLEN_GUEST_MABI) -std=gnu++17 \
		-ffreestanding -fno-exceptions -fno-rtti \
		-Wall -Wextra -Werror -fsyntax-only \
		-I$(AM_HOME)/klib/include -I$(AM_HOME)/am/include \
		$(CPP_SMOKE_DEFS) \
		-DARCH_H=\"arch/riscv.h\" cpp-header-smoke.cpp
.PHONY: cpp-smoke

TEST_PASS_REGEX := LOCALE WIDE TEST PASS
include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
