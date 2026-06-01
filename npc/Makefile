BUILD_DIR = ./build
#仿照Verilog版本的makefile
TOPNAME=RV32I
#verilator生成C++的中间文件的目录
OBJ_DIR=$(BUILD_DIR)/obj_dir_$(TOPNAME)
#最后生成的可以执行的文件，给后面的run用的
BIN=$(BUILD_DIR)/$(TOPNAME)
VSRCS=$(shell find $(BUILD_DIR) -maxdepth 1 -type f -name "*.sv" 2>/dev/null | sort) \
      $(shell find $(BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort)
VINCDIRS=$(BUILD_DIR) $(shell find $(BUILD_DIR)/verification -type d 2>/dev/null | sort)
CSRCS=$(shell find ./csrc -name "*.cpp" 2>/dev/null)
VERILATOR=verilator
NPROC ?= $(shell nproc 2>/dev/null || echo 4)

test:
	mill -i test

verilog:
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	mill -i runMain Elaborate --target-dir $(BUILD_DIR)

help:
	mill -i runMain Elaborate --help

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
GXX_VERSION = $(shell $(CXX) -dumpfullversion 2>/dev/null)
STD_MODULE_SRC = /usr/include/c++/$(GXX_VERSION)/bits/std.cc
CXX_STD = -std=c++23 -fmodules
COMMON_INCFLAGS = $(addprefix -I, $(CSRC_DIR) $(OBJ_DIR))
COMMON_CFLAGS += $(COMMON_INCFLAGS) $(CXX_STD) -O3
#verilator的部分
$(BIN): verilog $(CSRCS)
	CXX=$(CXX) $(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-O3 --x-assign fast --x-initial fast --noassert \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(TOPNAME) \
		$(VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(COMMON_CFLAGS)) \
		-LDFLAGS -lcapstone \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
run: $(BIN)
	@$(BIN) $(file) $(if $(elf),--elf $(elf),)

-include ../Makefile
