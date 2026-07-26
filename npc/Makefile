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
VSRCS=$(shell find $(BUILD_DIR) -maxdepth 1 -type f -name "*.sv" ! -name "riscv32e_npc*" ! -name "ysyx_*_npc*" ! -name "*_yosys*" ! -name "*_merged*" ! -name "*_sv2v*" 2>/dev/null | sort) \
      $(YSYXSOC_FULL_V) \
      $(shell find $(BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort) \
      $(shell find $(YSYXSOC_PERIP_DIR) -type f -name "*.v" 2>/dev/null | sort) \
      $(BUILD_DIR)/ysyx_26030103_PutcharProbe.v
VINCDIRS=$(BUILD_DIR) \
         $(shell find $(BUILD_DIR)/verification -type d 2>/dev/null | sort) \
         $(YSYXSOC_PERIP_INCDIRS)

SIM_TOP ?= npc
BIN_NPC = $(BUILD_DIR)/$(TOPNAME)_npc
NPC_OBJ_DIR = $(BUILD_DIR)/obj_dir_$(TOPNAME)_npc
NPC_VERILATOR_MK = Vriscv32e_npc_SimTop.mk
NPC_VSRCS = rtl/riscv32e_npc_SimTop.sv \
             $(shell find $(BUILD_DIR) -maxdepth 1 \( -name "riscv32e_npc_AXIRAM.sv" -o -name "layers-riscv32e_npc_AXIRAM*.sv" -o -name "ysyx_26030103_npc.sv" -o -name "layers-ysyx_26030103_npc*.sv" -o -name "mem_*.sv" \) 2>/dev/null | sort) \
             $(shell find $(BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort)
NPC_VINCDIRS = $(BUILD_DIR) \
                $(shell find $(BUILD_DIR)/verification -type d 2>/dev/null | sort)

CSRC_DIR=./csrc
CSRCS=$(shell find $(CSRC_DIR) -path "$(CSRC_DIR)/Test" -prune -o -path "$(CSRC_DIR)/tools/cachesim" -prune -o -name "*.cpp" -print 2>/dev/null)
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

CACHE_BLOCK_SIZE_LOG2 ?= $(or $(CONFIG_CACHE_BLOCK_SIZE_LOG2),4)
CACHE_INDEX_BITS ?= $(or $(CONFIG_CACHE_INDEX_BITS),5)

VERILOG_ENV = env \
	CACHE_BLOCK_SIZE_LOG2=$(CACHE_BLOCK_SIZE_LOG2) \
	CACHE_INDEX_BITS=$(CACHE_INDEX_BITS) \
	APB_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
	APB_DEVICE_FREQ_MHZ=$(APB_DEVICE_FREQ) \
	APB_DELAY_SCALE_FACTOR=$(APB_DELAY_SCALE) \
	AXI_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
	AXI_DEVICE_FREQ_MHZ=$(AXI_DEVICE_FREQ) \
	AXI_DELAY_SCALE_FACTOR=$(AXI_DELAY_SCALE)

verilog:
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	$(VERILOG_ENV) mill -i runMain ysyx_26030103_Elaborate --target-dir $(BUILD_DIR)
	ln -sf layers-ysyx_26030103-Verification.sv $(BUILD_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv
	cp -f src/ysyx_26030103_PutcharProbe.v $(BUILD_DIR)/

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
	-rm -rf $(BUILD_DIR) itrace.txt

CACHESIM_DIR  := $(CSRC_DIR)/tools/cachesim
CACHESIM_BIN  := $(CACHESIM_DIR)/build/cachesim

$(CACHESIM_BIN):
	@cmake -B $(CACHESIM_DIR)/build -S $(CACHESIM_DIR) && cmake --build $(CACHESIM_DIR)/build

.PHONY: cachesim
cachesim: $(CACHESIM_BIN)
	@rm -f itrace.txt
	@$(MAKE) run CONFIG_TRACE=y CONFIG_ITRACE=y CONFIG_ITRACE_WRITE_FILE=y file=$(file)
	@echo ""
	@echo "cachesim 结果："
	@$(CACHESIM_BIN) itrace.txt

.PHONY: cachesim_dse
cachesim_dse: $(CACHESIM_BIN)
	@rm -f itrace.txt
	@$(MAKE) run CONFIG_TRACE=y CONFIG_ITRACE=y CONFIG_ITRACE_WRITE_FILE=y file=$(file)
	@echo ""
	@echo "cachesim DSE 探索中..."
	@$(CACHESIM_BIN) --dse itrace.txt

.PHONY: test verilog help reformat checkformat clean synth perf

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
CONFIG_ITRACE_WRITE_FILE ?= n
CONFIG_MTRACE ?= n
CONFIG_FTRACE ?= n
CONFIG_NVBOARD ?= n
CONFIG_NVBOARD_UPDATE_INTERVAL ?= 1
CONFIG_NVBOARD_NXDC ?= csrc/nvboard/gpio.nxdc
ifeq ($(CONFIG_NVBOARD),y)
NVBOARD_HOME ?= $(abspath $(NPC_HOME)/../nvboard)
NVBOARD_ARCHIVE = $(NVBOARD_HOME)/build/nvboard.a
NVBOARD_AUTOBIND = $(BUILD_DIR)/auto_bind.cpp
NVBOARD_CFLAGS = -DCONFIG_NVBOARD -DCONFIG_NVBOARD_UPDATE_INTERVAL=$(call remove_quote,$(CONFIG_NVBOARD_UPDATE_INTERVAL)) -I$(NVBOARD_HOME)/usr/include $(shell sdl2-config --cflags 2>/dev/null)
NVBOARD_LDADD = $(NVBOARD_ARCHIVE) $(shell sdl2-config --libs 2>/dev/null) -lSDL2_image -lSDL2_ttf
NVBOARD_LDFLAGS = $(addprefix -LDFLAGS ,$(NVBOARD_LDADD))
endif
ifeq ($(CONFIG_SDB),y)
SDB_CFLAGS = -DCONFIG_SDB
endif
ifeq ($(CONFIG_TRACE),y)
TRACE_CFLAGS = -DCONFIG_TRACE
endif
ifneq ($(CONFIG_TRACE_DEPTH),)
TRACE_CFLAGS += -DCONFIG_TRACE_DEPTH=$(call remove_quote,$(CONFIG_TRACE_DEPTH))
endif
ifeq ($(CONFIG_ITRACE),y)
ITRACE_CFLAGS = -DCONFIG_ITRACE
endif
ifeq ($(CONFIG_ITRACE_WRITE_FILE),y)
ITRACE_WRITE_FILE_CFLAGS = -DCONFIG_ITRACE_WRITE_FILE
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
ifeq ($(CONFIG_PERF_STATS),y)
PERF_STATS_CFLAGS = -DCONFIG_PERF_STATS
endif
ifeq ($(CONFIG_PERF_SAVE),y)
PERF_SAVE_CFLAGS = -DCONFIG_PERF_SAVE
GIT_COMMIT := $(shell git rev-parse --short HEAD 2>/dev/null || echo "0000000")
PERF_SAVE_CFLAGS += -DCONFIG_PERF_GIT_COMMIT=$(GIT_COMMIT)
$(shell git log -1 --format=%s 2>/dev/null > $(BUILD_DIR)/git_msg.txt || echo "" > $(BUILD_DIR)/git_msg.txt)
endif
COMMON_INCFLAGS = $(addprefix -I, $(abspath $(CSRC_DIR)) $(abspath $(CSRC_DIR)/sdb) $(abspath $(CSRC_DIR)/sdb/command) $(abspath $(CSRC_DIR)/tools/expressions) $(abspath $(CSRC_DIR)/trace) $(abspath $(OBJ_DIR)))
CC_OPT ?= -O3
ifneq ($(CONFIG_CC_OPT),)
CC_OPT = $(call remove_quote,$(CONFIG_CC_OPT))
endif
ifeq ($(CONFIG_CC_LTO),y)
LTO_CFLAGS = -flto
endif
ifeq ($(CONFIG_PGO_GENERATE),y)
PGO_CFLAGS = -fprofile-generate
endif
ifeq ($(CONFIG_PGO_USE),y)
PGO_CFLAGS = -fprofile-use -fprofile-correction
endif
LD_FLAG ?=
ifneq ($(CONFIG_LD_FLAG),)
LD_FLAG = $(call remove_quote,$(CONFIG_LD_FLAG))
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
ifeq ($(CONFIG_CC_TSAN),y)
TSAN_CFLAGS = -fsanitize=thread
endif
ifeq ($(CONFIG_CC_COVERAGE),y)
COVERAGE_CFLAGS = --coverage
endif
ifeq ($(CONFIG_CC_WALL),y)
WALL_CFLAGS = -Wall -Wextra
endif
ifeq ($(CONFIG_CC_WERROR),y)
WERROR_CFLAGS = -Werror
endif
ifeq ($(CONFIG_CC_PEDANTIC),y)
PEDANTIC_CFLAGS = -Wpedantic
endif
ifeq ($(CONFIG_CC_WCONVERSION),y)
WCONVERSION_CFLAGS = -Wconversion
endif
ifeq ($(CONFIG_CC_COLOR),y)
COLOR_CFLAGS = -fdiagnostics-color=always
endif
ifeq ($(CONFIG_CC_PIPE),y)
PIPE_CFLAGS = -pipe
endif
ifeq ($(CONFIG_CC_SPLIT_DWARF),y)
SPLIT_DWARF_CFLAGS = -gsplit-dwarf
endif
ifeq ($(CONFIG_CC_GC_SECTIONS),y)
GC_SECTIONS_CFLAGS = -ffunction-sections -fdata-sections
GC_SECTIONS_LDFLAGS = -Wl,--gc-sections
endif
ifeq ($(CONFIG_CC_VISIBILITY_HIDDEN),y)
VISIBILITY_CFLAGS = -fvisibility=hidden
endif
ifeq ($(CONFIG_MARCH_NATIVE),y)
MARCH_CFLAGS = -march=native
endif
ifeq ($(CONFIG_MARCH_CUSTOM),y)
MARCH_CFLAGS = -march=$(call remove_quote,$(CONFIG_MARCH_CUSTOM_VALUE))
endif
ifeq ($(CONFIG_CC_IPA_PTA),y)
IPA_PTA_CFLAGS = -fipa-pta
endif
ifeq ($(CONFIG_CC_PREFETCH),y)
PREFETCH_CFLAGS = -fprefetch-loop-arrays
endif
ifeq ($(CONFIG_BUILD_TIMESTAMP),y)
TIMESTAMP_CFLAGS = -DBUILD_TIMESTAMP='"$(shell date "+%Y-%m-%d %H:%M:%S")"'
endif
ifneq ($(CONFIG_LOG_LEVEL),)
LOG_CFLAGS = -DCONFIG_LOG_LEVEL=$(call remove_quote,$(CONFIG_LOG_LEVEL))
endif
ifeq ($(CONFIG_LOG_TO_FILE),y)
LOG_CFLAGS += -DCONFIG_LOG_TO_FILE
endif
ifeq ($(CONFIG_LOG_TIMESTAMP),y)
LOG_CFLAGS += -DCONFIG_LOG_TIMESTAMP
endif
ifeq ($(CONFIG_LOG_SOURCE_LOCATION),y)
LOG_CFLAGS += -DCONFIG_LOG_SOURCE_LOCATION
endif
ifeq ($(CONFIG_CC_GPROF),y)
GPROF_CFLAGS = -pg
endif
ifeq ($(CONFIG_CC_TIME_REPORT),y)
TIME_REPORT_CFLAGS = -ftime-report
endif
ifeq ($(CONFIG_CC_NO_OMIT_FP),y)
NO_OMIT_FP_CFLAGS = -fno-omit-frame-pointer
endif
ifneq ($(filter clang% icpx%,$(notdir $(CXX))),)
MODULE_CFLAGS =
else
MODULE_CFLAGS = -fmodules
endif
ifeq ($(CONFIG_CC_ICPX_NOSTDINCXX),y)
ICPX_NOSTDINCXX_CFLAGS = -nostdinc++ -I/usr/include/c++/v1 -I/usr/include
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
SHARED_CFLAGS += $(COMMON_INCFLAGS) $(SDB_CFLAGS) $(TRACE_CFLAGS) $(ITRACE_CFLAGS) $(ITRACE_WRITE_FILE_CFLAGS) $(MTRACE_CFLAGS) $(FTRACE_CFLAGS) $(IRINGBUF_CFLAGS) $(WATCHPOINT_CFLAGS) $(DIFFTEST_CFLAGS) $(PERF_STATS_CFLAGS) $(PERF_SAVE_CFLAGS) $(MBASE_CFLAGS) $(MSIZE_CFLAGS) $(RESET_PC_CFLAGS) $(PDK_CFLAGS) $(DEBUG_CFLAGS) $(LTO_CFLAGS) $(PGO_CFLAGS) $(ASAN_CFLAGS) $(UBSAN_CFLAGS) $(TSAN_CFLAGS) $(COVERAGE_CFLAGS) $(COLOR_CFLAGS) $(PIPE_CFLAGS) $(SPLIT_DWARF_CFLAGS) $(GC_SECTIONS_CFLAGS) $(VISIBILITY_CFLAGS) $(MARCH_CFLAGS) $(IPA_PTA_CFLAGS) $(PREFETCH_CFLAGS) $(GPROF_CFLAGS) $(TIME_REPORT_CFLAGS) $(TIMESTAMP_CFLAGS) $(LOG_CFLAGS) $(NO_OMIT_FP_CFLAGS) $(TRACE_VCD_CFLAGS) $(TRACE_FST_CFLAGS) $(ICPX_NOSTDINCXX_CFLAGS) $(STDLIB_FLAG) $(CC_OPT) $(NVBOARD_CFLAGS)
VERILATOR_CFLAGS = $(SHARED_CFLAGS) $(VERILATOR_CXX_STD_FLAG)
USER_CXXFLAGS = $(USER_CXX_STD_FLAG) $(MODULE_CFLAGS) $(WALL_CFLAGS) $(WERROR_CFLAGS) $(PEDANTIC_CFLAGS) $(WCONVERSION_CFLAGS)
#verilator的部分
VERILATOR_OPT_FLAG ?= -O3
ifneq ($(CONFIG_VERILATOR_OPT),)
VERILATOR_OPT_FLAG = $(call remove_quote,$(CONFIG_VERILATOR_OPT))
endif
ifeq ($(CONFIG_TRACE_VCD),y)
VERILATOR_TRACE = --trace
TRACE_VCD_CFLAGS = -DCONFIG_TRACE_VCD
endif
ifeq ($(CONFIG_TRACE_FST),y)
VERILATOR_TRACE = --trace-fst
TRACE_FST_CFLAGS = -DCONFIG_TRACE_FST
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
ifneq ($(CONFIG_OUTPUT_SPLIT),)
OUTPUT_SPLIT_VAL := $(call remove_quote,$(CONFIG_OUTPUT_SPLIT))
ifneq ($(OUTPUT_SPLIT_VAL),0)
VERILATOR_OUTPUT_SPLIT = --output-split $(OUTPUT_SPLIT_VAL)
endif
endif
VERILATOR_GENERATED_MK = V$(SOC_TOPNAME).mk
VERILATOR_BUILD_WRAPPER = $(abspath scripts/verilator-build.mk)
ifeq ($(CONFIG_NVBOARD),y)
$(NVBOARD_AUTOBIND): $(call remove_quote,$(CONFIG_NVBOARD_NXDC))
	@mkdir -p $(dir $@)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $< $@
endif

simtest: $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) $(NVBOARD_AUTOBIND) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) $(NVBOARD_LDFLAGS) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'

$(YSYXSOC_FULL_V):
	@echo "[perf] (re)building ysyxSoC with APB(DEV=$(APB_DEVICE_FREQ)MHz S=$(APB_DELAY_SCALE)) AXI(DEV=$(AXI_DEVICE_FREQ)MHz S=$(AXI_DELAY_SCALE))"
	@cd $(YSYXSOC_DIR) && env \
		APB_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
		APB_DEVICE_FREQ_MHZ=$(APB_DEVICE_FREQ) \
		APB_DELAY_SCALE_FACTOR=$(APB_DELAY_SCALE) \
		AXI_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
		AXI_DEVICE_FREQ_MHZ=$(AXI_DEVICE_FREQ) \
		AXI_DELAY_SCALE_FACTOR=$(AXI_DELAY_SCALE) \
		make verilog

$(BIN): verilog $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND) $(YSYXSOC_FULL_V)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) $(NVBOARD_AUTOBIND) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) $(NVBOARD_LDFLAGS) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'
$(BIN_NPC): verilog $(CSRCS) $(VERILATOR_BUILD_WRAPPER)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(NPC_VINCDIRS)) \
		--top-module riscv32e_npc_SimTop \
		$(NPC_VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(filter-out -DCONFIG_MBASE=% -DCONFIG_RESET_PC=%,$(VERILATOR_CFLAGS)) -DVRISCV32E_NPC -DCONFIG_RESET_PC=0x80000000 -DCONFIG_MBASE=0x80000000) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) \
		--Mdir $(NPC_OBJ_DIR) \
		--exe -o $(abspath $(BIN_NPC))
	$(MAKE) -C $(NPC_OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(NPC_VERILATOR_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'

ifeq ($(SIM_TOP),soc)
run: $(BIN)
	$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) 2>&1
else
run: $(BIN_NPC)
	od -An -tx4 -w4 -v "$(file)" | awk '{print $$1}' > $(BUILD_DIR)/program.hex
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) 2>&1
endif

.PHONY: pgo
pgo: verilog
	@echo "PGO Pass 1: 编译插桩版本"
	@rm -f $(BIN)
	$(MAKE) PGO_CFLAGS=-fprofile-generate $(BIN)
	@echo "PGO Pass 1: 运行收集profile"
	@$(BIN) $(file) $(if $(elf),--elf $(elf),)
	@echo "PGO Pass 2: 使用profile重新编译"
	@find $(OBJ_DIR) -name '*.o' -delete
	@rm -f $(OBJ_DIR)/.npc_modules_built $(BIN)
	$(MAKE) PGO_CFLAGS='-fprofile-use -fprofile-correction' $(BIN)
	@echo "PGO 完成，运行优化后的程序"
	@$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),)


PERF_DIR   = NPCSimResult
PERF_SYNTH = $(BUILD_DIR)/synth.txt

YOSYS_STA_HOME ?= $(abspath $(NPC_HOME)/../yosys-sta)
ifneq ($(CONFIG_SYNTH_FREQ),)
SYNTH_FREQ = $(call remove_quote,$(CONFIG_SYNTH_FREQ))
else
SYNTH_FREQ ?= 450
endif
ifneq ($(CONFIG_PDK),)
PDK_VALUE = $(call remove_quote,$(CONFIG_PDK))
else
PDK_VALUE = icsprout55
endif
PDK_CFLAGS = -DCONFIG_PDK=$(PDK_VALUE)

ifneq ($(CONFIG_SYNTH_STRATEGY),)
SYNTH_STRATEGY = $(call remove_quote,$(CONFIG_SYNTH_STRATEGY))
else
SYNTH_STRATEGY = AREA 0
endif
ifneq ($(CONFIG_SYNTH_NOMAP),n)
SYNTH_NOMAP = 1
else
SYNTH_NOMAP = 0
endif

ifneq ($(CONFIG_APB_DEVICE_FREQ),)
APB_DEVICE_FREQ = $(call remove_quote,$(CONFIG_APB_DEVICE_FREQ))
else
APB_DEVICE_FREQ = 100
endif
ifneq ($(CONFIG_APB_DELAY_SCALE),)
APB_DELAY_SCALE = $(call remove_quote,$(CONFIG_APB_DELAY_SCALE))
else
APB_DELAY_SCALE = 64
endif
ifneq ($(CONFIG_AXI_DEVICE_FREQ),)
AXI_DEVICE_FREQ = $(call remove_quote,$(CONFIG_AXI_DEVICE_FREQ))
else
AXI_DEVICE_FREQ = 100
endif
ifneq ($(CONFIG_AXI_DELAY_SCALE),)
AXI_DELAY_SCALE = $(call remove_quote,$(CONFIG_AXI_DELAY_SCALE))
else
AXI_DELAY_SCALE = 64
endif

ifeq ($(SIM_TOP),soc)
SYNTH_DESIGN = ysyx_26030103
SYNTH_RTL_FILES = $(abspath $(BUILD_DIR)/ysyx_26030103.sv) $(abspath $(BUILD_DIR)/ysyx_26030103_PutcharProbe.v)
else
SYNTH_DESIGN = ysyx_26030103
SYNTH_RTL_FILES = $(abspath $(BUILD_DIR)/ysyx_26030103_npc.sv)
endif

YOSYS_PP = $(BUILD_DIR)/preprocess_for_yosys
ifeq ($(filter clang% icpx%,$(notdir $(CXX))),)
STD_MODULE_SRC = $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))
$(BUILD_DIR)/gcm.cache/std.gcm: $(STD_MODULE_SRC)
	@mkdir -p $(@D)
	cd $(BUILD_DIR) && $(CXX) $(USER_CXXFLAGS) -x c++ -c $< -o std_module.o

$(YOSYS_PP): $(NPC_HOME)/scripts/preprocess_for_yosys.cpp $(BUILD_DIR)/gcm.cache/std.gcm
	@mkdir -p $(@D)
	cd $(BUILD_DIR) && $(CXX) $(USER_CXXFLAGS) $(SHARED_CFLAGS) -O2 -o $(notdir $@) $(abspath $<)
else
STD_MODULE_SRC = $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))
$(BUILD_DIR)/std.pcm: $(STD_MODULE_SRC)
	@mkdir -p $(@D)
	cd $(BUILD_DIR) && $(CXX) -std=c++23 -x c++-module --precompile $< -o std.pcm

$(YOSYS_PP): $(NPC_HOME)/scripts/preprocess_for_yosys.cpp $(BUILD_DIR)/std.pcm
	@mkdir -p $(@D)
	cd $(BUILD_DIR) && $(CXX) -std=c++23 -fprebuilt-module-path=. -O2 -o $(notdir $@) $(abspath $<)
endif

synth: verilog $(YOSYS_PP)
	@echo "[perf] 综合 ($(SYNTH_DESIGN), $(SYNTH_FREQ)MHz, $(PDK_VALUE), strategy=$(SYNTH_STRATEGY), nomap=$(SYNTH_NOMAP)) ..."
	@$(YOSYS_PP) $(abspath $(BUILD_DIR)/ysyx_26030103.sv) > $(BUILD_DIR)/$(SYNTH_DESIGN)_yosys.sv
	@cat $(abspath $(BUILD_DIR)/ysyx_26030103_PutcharProbe.v) >> $(BUILD_DIR)/$(SYNTH_DESIGN)_yosys.sv
	@export SYNTH_STRATEGY='$(SYNTH_STRATEGY)' && export SYNTH_NOMAP=$(SYNTH_NOMAP) && \
	$(MAKE) -C $(YOSYS_STA_HOME) syn \
		PDK=$(PDK_VALUE) \
		DESIGN=$(SYNTH_DESIGN) \
		RTL_FILES="$(abspath $(BUILD_DIR)/$(SYNTH_DESIGN)_yosys.sv)" \
		CLK_FREQ_MHZ=$(SYNTH_FREQ) \
		O=$(abspath $(YOSYS_STA_HOME))/result
	@mkdir -p $(BUILD_DIR)
	@echo $(SYNTH_FREQ) > $(PERF_SYNTH)
	@SYNTH_STAT="$(abspath $(YOSYS_STA_HOME))/result/$(SYNTH_DESIGN)-$(SYNTH_FREQ)MHz-$(PDK_VALUE)/synth_stat.txt"; \
	if [ -f "$$SYNTH_STAT" ]; then \
		awk '/Chip area/ {print $$6}' "$$SYNTH_STAT" >> $(PERF_SYNTH); \
	else \
		echo "0" >> $(PERF_SYNTH); \
	fi

ifeq ($(SIM_TOP),soc)
perf: synth $(BIN)
	@mkdir -p $(PERF_DIR)
	@echo "[perf] 运行仿真 ..."
	@RESULT_DIR="$(PERF_DIR)/$$(date +%Y%m%d-%H%M%S)"; \
	mkdir -p "$$RESULT_DIR"; \
	$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) --result-dir "$$RESULT_DIR" 2>&1 | tee "$$RESULT_DIR/ysyxsoc.log"
else
perf: synth $(BIN_NPC)
	@mkdir -p $(PERF_DIR)
	@echo "[perf] 编译 hex ..."
	@od -An -tx4 -w4 -v "$(file)" | awk '{print $$1}' > $(BUILD_DIR)/program.hex
	@echo "[perf] 运行仿真 ..."
	@RESULT_DIR="$(PERF_DIR)/$$(date +%Y%m%d-%H%M%S)"; \
	mkdir -p "$$RESULT_DIR"; \
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) --result-dir "$$RESULT_DIR" 2>&1 | tee "$$RESULT_DIR/npc.log"
endif

include scripts/config.mk

-include ../Makefile
