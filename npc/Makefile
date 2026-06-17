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
NPROC ?= $(shell nproc 2>/dev/null || echo 4)

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
remove_quote = $(patsubst "%",%,$(1))
CXX?=g++
ifneq ($(CONFIG_CC),)
CXX = $(call remove_quote,$(CONFIG_CC))
endif
GXX_VERSION = $(shell $(CXX) -dumpfullversion 2>/dev/null)
STD_MODULE_SRC = /usr/include/c++/$(GXX_VERSION)/bits/std.cc
CXX_STD = -std=c++23
# menuconfig 生成的配置（优先），手动传参也仍然有效
-include include/config/auto.conf
CONFIG_SDB ?= n
CONFIG_ITRACE ?= n
CONFIG_MTRACE ?= n
CONFIG_FTRACE ?= n
ifeq ($(CONFIG_SDB),y)
SDB_CFLAGS = -DCONFIG_SDB
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
COMMON_CFLAGS += $(COMMON_INCFLAGS) $(CXX_STD) $(SDB_CFLAGS) $(ITRACE_CFLAGS) $(MTRACE_CFLAGS) $(FTRACE_CFLAGS) $(DEBUG_CFLAGS) $(LTO_CFLAGS) $(ASAN_CFLAGS) $(CC_OPT)
#verilator的部分
$(BIN): verilog $(CSRCS)
	CXX=$(CXX) $(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-O3 --x-assign fast --x-initial fast --noassert \
		--timescale "1ns/1ns" --no-timing \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(COMMON_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN)) \
		--autoflush
run: $(BIN)
	@$(BIN) $(file) $(if $(elf),--elf $(elf),)

include scripts/config.mk

-include ../Makefile
