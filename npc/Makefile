NPC_HOME := $(CURDIR)
BUILD_DIR = ./build
YSYXSOC_DIR = ../ysyxSoC
YSYXSOC_PERIP_DIR = $(YSYXSOC_DIR)/perip
YSYXSOC_PERIP_INCDIRS = \
	$(YSYXSOC_PERIP_DIR)/uart16550/rtl \
	$(YSYXSOC_PERIP_DIR)/spi/rtl
YSYXSOC_FULL_V=$(YSYXSOC_DIR)/build/ysyxSoCFull.v
#仿照Verilog版本的makefile
TOPNAME=$(STUID)
SOC_TOPNAME=ysyxSoCFull
#verilator生成C++的中间文件的目录
OBJ_DIR=$(BUILD_DIR)/obj_dir_$(TOPNAME)
#最后生成的可以执行的文件，给后面的run用的
BIN=$(BUILD_DIR)/$(TOPNAME)
VSRCS=$(shell find $(BUILD_DIR) -maxdepth 1 -type f -name "*.sv" 2>/dev/null | sort) \
      $(YSYXSOC_FULL_V) \
      $(shell find $(BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort) \
      $(shell find $(YSYXSOC_PERIP_DIR) -type f -name "*.v" 2>/dev/null | sort)
VINCDIRS=$(BUILD_DIR) \
         $(shell find $(BUILD_DIR)/verification -type d 2>/dev/null | sort) \
         $(YSYXSOC_PERIP_INCDIRS)
CSRC_DIR=./csrc
CSRCS=$(shell find $(CSRC_DIR) -path "$(CSRC_DIR)/Test" -prune -o -name "*.cpp" -print 2>/dev/null)
VERILATOR=verilator
remove_quote = $(patsubst "%",%,$(1))
-include include/config/auto.conf
NPROC_MAX := $(shell nproc 2>/dev/null || echo 4)
ifneq ($(CONFIG_BUILD_THREADS),)
NPROC_REQ := $(call remove_quote,$(CONFIG_BUILD_THREADS))
else
NPROC_REQ := 0
endif
NPROC := $(shell if [ "$(NPROC_REQ)" -eq 0 ] 2>/dev/null || [ "$(NPROC_REQ)" -gt "$(NPROC_MAX)" ] 2>/dev/null; then echo $(NPROC_MAX); else echo $(NPROC_REQ); fi)
ifneq ($(CONFIG_SIM_THREADS),)
SIM_THREADS_REQ := $(call remove_quote,$(CONFIG_SIM_THREADS))
else
SIM_THREADS_REQ := 1
endif
SIM_THREADS := $(shell if [ "$(SIM_THREADS_REQ)" -eq 0 ] 2>/dev/null; then echo $(NPROC_MAX); elif [ "$(SIM_THREADS_REQ)" -gt "$(NPROC_MAX)" ] 2>/dev/null; then echo $(NPROC_MAX); else echo $(SIM_THREADS_REQ); fi)
VERILATOR_THREADS := $(shell if [ "$(SIM_THREADS)" -gt 1 ] 2>/dev/null; then echo "--threads $(SIM_THREADS)"; fi)

test:
	mill -i test

verilog:
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	mill -i runMain ysyx_26030103_Elaborate --target-dir $(BUILD_DIR)

help:
	mill -i runMain ysyx_26030103_Elaborate --help

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help reformat checkformat clean

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by yourself."

#C++
CXX?=g++
ifneq ($(CONFIG_CC),)
CXX = $(call remove_quote,$(CONFIG_CC))
endif
USER_CXX_STD ?= c++23
ifneq ($(CONFIG_USER_CXX_STD),)
USER_CXX_STD = $(call remove_quote,$(CONFIG_USER_CXX_STD))
endif
VERILATOR_CXX_STD ?= c++20
ifneq ($(CONFIG_VERILATOR_CXX_STD),)
VERILATOR_CXX_STD = $(call remove_quote,$(CONFIG_VERILATOR_CXX_STD))
endif
USER_CXX_STD_FLAG = -std=$(USER_CXX_STD)
VERILATOR_CXX_STD_FLAG = -std=$(VERILATOR_CXX_STD)
STDLIB_FLAG ?=
ifneq ($(filter clang% icpx%,$(notdir $(CXX))),)
ifneq ($(CONFIG_STDLIB_FLAG),)
STDLIB_FLAG = $(call remove_quote,$(CONFIG_STDLIB_FLAG))
endif
endif

ifneq ($(filter clang% icpx%,$(notdir $(CXX))),)
VERILATOR_COMPILER = --compiler clang
else
VERILATOR_COMPILER = --compiler gcc
endif
CONFIG_SDB ?= n
CONFIG_ITRACE ?= n
CONFIG_MTRACE ?= n
CONFIG_FTRACE ?= n
ifeq ($(CONFIG_SDB),y)
SDB_CFLAGS = -DCONFIG_SDB
endif
ifeq ($(CONFIG_TRACE),y)
TRACE_CFLAGS = -DCONFIG_TRACE
endif
ifeq ($(CONFIG_ITRACE),y)
ITRACE_CFLAGS = -DCONFIG_ITRACE
endif
ifeq ($(CONFIG_MTRACE),y)
MTRACE_CFLAGS = -DCONFIG_MTRACE
endif
ifeq ($(CONFIG_FTRACE),y)
FTRACE_CFLAGS = -DCONFIG_FTRACE
endif
ifeq ($(CONFIG_IRINGBUF),y)
IRINGBUF_CFLAGS = -DCONFIG_IRINGBUF
ifneq ($(CONFIG_IRINGBUF_SIZE),)
IRINGBUF_CFLAGS += -DCONFIG_IRINGBUF_SIZE=$(call remove_quote,$(CONFIG_IRINGBUF_SIZE))
endif
endif
ifeq ($(CONFIG_WATCHPOINT),y)
WATCHPOINT_CFLAGS = -DCONFIG_WATCHPOINT
endif
ifeq ($(CONFIG_DIFFTEST),y)
DIFFTEST_CFLAGS = -DCONFIG_DIFFTEST
endif
COMMON_INCFLAGS = $(addprefix -I, $(abspath $(CSRC_DIR)) $(abspath $(CSRC_DIR)/sdb) $(abspath $(CSRC_DIR)/sdb/command) $(abspath $(CSRC_DIR)/tools/expressions) $(abspath $(CSRC_DIR)/trace) $(abspath $(OBJ_DIR)))
CC_OPT ?= -O3
ifneq ($(CONFIG_CC_OPT),)
CC_OPT = $(call remove_quote,$(CONFIG_CC_OPT))
endif
ifeq ($(CONFIG_CC_LTO),y)
LTO_CFLAGS = -flto
endif
ifeq ($(CONFIG_CC_DEBUG),y)
DEBUG_CFLAGS = -g
endif
ifeq ($(CONFIG_CC_ASAN),y)
ASAN_CFLAGS = -fsanitize=address
endif
ifeq ($(CONFIG_CC_UBSAN),y)
UBSAN_CFLAGS = -fsanitize=undefined
endif
ifeq ($(CONFIG_CC_WALL),y)
WALL_CFLAGS = -Wall -Wextra
endif
ifeq ($(CONFIG_CC_WERROR),y)
WERROR_CFLAGS = -Werror
endif
ifeq ($(CONFIG_CC_NO_OMIT_FP),y)
NO_OMIT_FP_CFLAGS = -fno-omit-frame-pointer
endif
ifneq ($(filter clang% icpx%,$(notdir $(CXX))),)
MODULE_CFLAGS =
else
MODULE_CFLAGS = -fmodules
endif
ifneq ($(CONFIG_MBASE),)
MBASE_CFLAGS = -DCONFIG_MBASE=$(call remove_quote,$(CONFIG_MBASE))
endif
ifneq ($(CONFIG_MSIZE),)
MSIZE_CFLAGS = -DCONFIG_MSIZE=$(call remove_quote,$(CONFIG_MSIZE))
endif
ifneq ($(CONFIG_RESET_PC),)
RESET_PC_CFLAGS = -DCONFIG_RESET_PC=$(call remove_quote,$(CONFIG_RESET_PC))
endif
COMMON_INCFLAGS += $(addprefix -I, $(abspath $(CSRC_DIR)/difftest))
SHARED_CFLAGS += $(COMMON_INCFLAGS) $(SDB_CFLAGS) $(TRACE_CFLAGS) $(ITRACE_CFLAGS) $(MTRACE_CFLAGS) $(FTRACE_CFLAGS) $(IRINGBUF_CFLAGS) $(WATCHPOINT_CFLAGS) $(DIFFTEST_CFLAGS) $(MBASE_CFLAGS) $(MSIZE_CFLAGS) $(RESET_PC_CFLAGS) $(DEBUG_CFLAGS) $(LTO_CFLAGS) $(ASAN_CFLAGS) $(UBSAN_CFLAGS) $(WALL_CFLAGS) $(WERROR_CFLAGS) $(NO_OMIT_FP_CFLAGS) $(STDLIB_FLAG) $(CC_OPT)
VERILATOR_CFLAGS = $(SHARED_CFLAGS) $(VERILATOR_CXX_STD_FLAG)
USER_CXXFLAGS = $(USER_CXX_STD_FLAG) $(MODULE_CFLAGS)
#verilator的部分
VERILATOR_OPT_FLAG ?= -O3
ifneq ($(CONFIG_VERILATOR_OPT),)
VERILATOR_OPT_FLAG = $(call remove_quote,$(CONFIG_VERILATOR_OPT))
endif
ifeq ($(CONFIG_TRACE_VCD),y)
VERILATOR_TRACE = --trace
endif
ifeq ($(CONFIG_TRACE_FST),y)
VERILATOR_TRACE = --trace-fst
endif
ifneq ($(CONFIG_TRACE_DEPTH),)
TRACE_DEPTH_VAL := $(call remove_quote,$(CONFIG_TRACE_DEPTH))
ifneq ($(TRACE_DEPTH_VAL),0)
VERILATOR_TRACE += --trace-depth $(TRACE_DEPTH_VAL)
endif
endif
ifeq ($(CONFIG_PROF_CFUNCS),y)
VERILATOR_PROF = --prof-cfuncs
endif
ifeq ($(CONFIG_AUTOFLUSH),y)
VERILATOR_AUTOFLUSH = --autoflush
endif
ifeq ($(CONFIG_ASSERT),y)
VERILATOR_ASSERT = --assert
else
VERILATOR_ASSERT = --noassert
endif
ifeq ($(CONFIG_X_ASSIGN_0),y)
VERILATOR_X_ASSIGN = --x-assign 0
else ifeq ($(CONFIG_X_ASSIGN_1),y)
VERILATOR_X_ASSIGN = --x-assign 1
else ifeq ($(CONFIG_X_ASSIGN_UNIQUE),y)
VERILATOR_X_ASSIGN = --x-assign unique
else
VERILATOR_X_ASSIGN = --x-assign fast
endif
ifeq ($(CONFIG_X_INITIAL_0),y)
VERILATOR_X_INITIAL = --x-initial 0
else ifeq ($(CONFIG_X_INITIAL_1),y)
VERILATOR_X_INITIAL = --x-initial 1
else ifeq ($(CONFIG_X_INITIAL_UNIQUE),y)
VERILATOR_X_INITIAL = --x-initial unique
else
VERILATOR_X_INITIAL = --x-initial fast
endif
ifeq ($(CONFIG_COVERAGE),y)
VERILATOR_COVERAGE = --coverage
endif
VERILATOR_GENERATED_MK = V$(SOC_TOPNAME).mk
VERILATOR_BUILD_WRAPPER = $(abspath scripts/verilator-build.mk)
$(BIN): verilog $(CSRCS) $(VERILATOR_BUILD_WRAPPER)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG) -LDFLAGS -lc++ -LDFLAGS -lc++abi,) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'
run: $(BIN)
	@$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),)

include scripts/config.mk

-include ../Makefile
