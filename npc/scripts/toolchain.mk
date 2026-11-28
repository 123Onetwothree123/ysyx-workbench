# ============================================================================
# 工具链与编译选项：CXX / 标准库 / 告警 / 消毒器 / Verilator / GSIM
# ============================================================================

#C++
CXX?=g++
ifneq ($(CONFIG_CC),)
CXX = $(call remove_quote,$(CONFIG_CC))
endif
USER_CXX_STD ?= c++26
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

# GSIM生成的C++使用_BitInt，GCC尚不支持该C++扩展，因此harness
# 默认用clang++编译；CONFIG_CC只服务Verilator，不适用于GSIM
GSIM_COMMAND ?= gsim
ifneq ($(CONFIG_GSIM_COMMAND),)
GSIM_COMMAND = $(call unquote_config,$(CONFIG_GSIM_COMMAND))
endif
GSIM_EXTRA_ARGS ?=
ifneq ($(CONFIG_GSIM_EXTRA_ARGS),)
GSIM_EXTRA_ARGS = $(call unquote_config,$(CONFIG_GSIM_EXTRA_ARGS))
endif
# GSIM的--threads只并行CHIRRTL解析和C++生成，生成的模型运行时仍是单线程
GSIM_THREADS_REQ := 0
ifneq ($(CONFIG_GSIM_THREADS),)
GSIM_THREADS_REQ := $(call remove_quote,$(CONFIG_GSIM_THREADS))
endif
GSIM_THREADS := $(shell if [ "$(GSIM_THREADS_REQ)" -eq 0 ] 2>/dev/null || [ "$(GSIM_THREADS_REQ)" -gt "$(NPROC_MAX)" ] 2>/dev/null; then echo $(NPROC_MAX); else echo $(GSIM_THREADS_REQ); fi)
GSIM_THREADS_FLAG := --threads=$(GSIM_THREADS)
GSIM_DIR := $(abspath $(CONFIG_BUILD_DIR)/gsim)
GSIM_CHIRRTL := $(abspath $(CONFIG_BUILD_DIR)/riscv32e_npc_SimTop.fir)
GSIM_MODEL := $(GSIM_DIR)/riscv32e_npc_SimTop0.cpp
GSIM_BIN := $(abspath $(CONFIG_BUILD_DIR)/ysyx_26030103_gsim)
GSIM_HARNESS := $(abspath $(CSRC_DIR)/gsim/main.cpp)
GSIM_OBJ_DIR := $(abspath $(CONFIG_BUILD_DIR)/gsim_obj)
GSIM_CONFIG_STAMP := $(abspath $(CONFIG_BUILD_DIR)/gsim_config.stamp)
GSIM_SCALA_SOURCES := $(shell find src -type f -name "*.scala" 2>/dev/null | sort)

# GSIM的C++构建复用Kconfig里对harness同样有意义的选项；CONFIG_CC
# 只用于Verilator，GSIM仍默认clang++，但允许用CONFIG_GSIM_CXX覆盖
GSIM_CXX ?= clang++
ifneq ($(call unquote_config,$(CONFIG_GSIM_CXX)),)
GSIM_CXX = $(call unquote_config,$(CONFIG_GSIM_CXX))
endif
GSIM_CXX_STD ?= $(or $(call remove_quote,$(CONFIG_USER_CXX_STD)),c++20)
GSIM_OPT_FLAG ?= $(or $(call remove_quote,$(CONFIG_CC_OPT)),-O2)
GSIM_STDLIB_FLAG :=
ifneq ($(filter clang% icpx%,$(notdir $(GSIM_CXX))),)
GSIM_STDLIB_FLAG := $(call remove_quote,$(CONFIG_STDLIB_FLAG))
endif
GSIM_LD_FLAG ?= $(call remove_quote,$(CONFIG_LD_FLAG))
GSIM_WARN_FLAGS :=
ifeq ($(CONFIG_CC_WALL),y)
GSIM_WARN_FLAGS += -Wall -Wextra
endif
ifeq ($(CONFIG_CC_WERROR),y)
GSIM_WARN_FLAGS += -Werror
endif
ifeq ($(CONFIG_CC_PEDANTIC),y)
GSIM_WARN_FLAGS += -Wpedantic
endif
ifeq ($(CONFIG_CC_WCONVERSION),y)
GSIM_WARN_FLAGS += -Wconversion
endif
GSIM_RUNTIME_FLAGS :=
ifeq ($(CONFIG_CC_ASAN),y)
GSIM_RUNTIME_FLAGS += -fsanitize=address
endif
ifeq ($(CONFIG_CC_UBSAN),y)
GSIM_RUNTIME_FLAGS += -fsanitize=undefined
endif
ifeq ($(CONFIG_CC_TSAN),y)
GSIM_RUNTIME_FLAGS += -fsanitize=thread
endif
ifeq ($(CONFIG_CC_COVERAGE),y)
GSIM_RUNTIME_FLAGS += --coverage
endif
ifeq ($(CONFIG_CC_GPROF),y)
GSIM_RUNTIME_FLAGS += -pg
endif
override GSIM_CACHE_PADDING := $(or $(call remove_quote,$(strip $(CONFIG_CACHE_PADDING))),0)
override GSIM_RAM_BYTES := $(or $(call remove_quote,$(strip $(CONFIG_NPC_RAM_SIZE))),0x04000000)
GSIM_DEFINES := -DNPC_GSIM_CACHE_PADDING=$(GSIM_CACHE_PADDING) \
	-DNPC_GSIM_RAM_BYTES=$(GSIM_RAM_BYTES)
GSIM_BASE_CXXFLAGS := -std=$(GSIM_CXX_STD) $(GSIM_OPT_FLAG) \
	$(GSIM_RUNTIME_FLAGS) $(GSIM_STDLIB_FLAG) $(GSIM_DEFINES) -isystem $(GSIM_DIR)
# GSIM生成的C++不保证warning-clean，警告选项只作用于harness
GSIM_HARNESS_CXXFLAGS := $(GSIM_BASE_CXXFLAGS) $(GSIM_WARN_FLAGS)
GSIM_MODEL_CXXFLAGS := $(GSIM_BASE_CXXFLAGS)
GSIM_LDFLAGS := $(GSIM_RUNTIME_FLAGS) $(GSIM_STDLIB_FLAG) $(GSIM_LD_FLAG)
ifeq ($(CONFIG_LIB_HARDENING),y)
ifeq ($(CONFIG_STDLIB_LIBCXX),y)
HARDENING_CFLAGS = -D_LIBCPP_HARDENING_MODE=$(call remove_quote,$(CONFIG_LIB_HARDENING_MODE))
else
HARDENING_CFLAGS = -D_GLIBCXX_ASSERTIONS
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
CONFIG_NVBOARD_UPDATE_INTERVAL ?= 100
CONFIG_NVBOARD_NXDC ?= csrc/nvboard/gpio.nxdc
ifeq ($(CONFIG_NVBOARD),y)
NVBOARD_HOME ?= $(abspath $(NPC_HOME)/../nvboard)
NVBOARD_ARCHIVE = $(NVBOARD_HOME)/build/nvboard.a
NVBOARD_AUTOBIND = $(CONFIG_BUILD_DIR)/auto_bind.cpp
NVBOARD_CFLAGS = -I$(NVBOARD_HOME)/usr/include $(shell sdl2-config --cflags 2>/dev/null)
NVBOARD_LDADD = $(NVBOARD_ARCHIVE) $(shell sdl2-config --libs 2>/dev/null) -lSDL2_image -lSDL2_ttf
NVBOARD_LDFLAGS = $(addprefix -LDFLAGS ,$(NVBOARD_LDADD))
NVBOARD_DIRECT_GUARD = nvboard-direct-unsupported

.PHONY: nvboard-direct-unsupported
nvboard-direct-unsupported:
	@echo "错误：NVBoard 只能绑定 ysyxSoCFull 的外设引脚；direct-NPC 顶层没有可绑定的 GPIO/VGA/UART 引脚。请使用 SIM_TOP=soc。" >&2
	@exit 2
endif
# C++ 侧的配置入口是生成的 npc.config 模块。C++ 源码不再消费任何
# CONFIG_* 编译宏，模块由 .config 派生，构建命令只需区分目标顶层。
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
# These are properties of the fixed ysyxSoC platform, not tunable simulator
# options.  Keeping stale Kconfig values here previously let the C++ loader and
# DiffTest disagree with the hard-wired RTL/PMA map.
COMMON_INCFLAGS += $(addprefix -I, $(abspath $(CSRC_DIR)/difftest))
SHARED_CFLAGS += $(COMMON_INCFLAGS) $(DEBUG_CFLAGS) $(LTO_CFLAGS) $(PGO_CFLAGS) $(ASAN_CFLAGS) $(UBSAN_CFLAGS) $(TSAN_CFLAGS) $(COVERAGE_CFLAGS) $(COLOR_CFLAGS) $(PIPE_CFLAGS) $(SPLIT_DWARF_CFLAGS) $(GC_SECTIONS_CFLAGS) $(VISIBILITY_CFLAGS) $(MARCH_CFLAGS) $(IPA_PTA_CFLAGS) $(PREFETCH_CFLAGS) $(GPROF_CFLAGS) $(TIME_REPORT_CFLAGS) $(TIMESTAMP_CFLAGS) $(NO_OMIT_FP_CFLAGS) $(ICPX_NOSTDINCXX_CFLAGS) $(STDLIB_FLAG) $(HARDENING_CFLAGS) $(CC_OPT) $(NVBOARD_CFLAGS)
VERILATOR_CFLAGS = $(SHARED_CFLAGS) $(VERILATOR_CXX_STD_FLAG)
USER_CXXFLAGS = $(USER_CXX_STD_FLAG) $(MODULE_CFLAGS) $(WALL_CFLAGS) $(WERROR_CFLAGS) $(PEDANTIC_CFLAGS) $(WCONVERSION_CFLAGS)
#verilator的部分
VERILATOR_OPT_FLAG ?= -O3
# 可选：生成带 Verilator --savable 状态序列化的 direct-NPC 仿真器。
# 默认关闭，不影响正常构建；检查点实验/调试时用
#   make VERILATOR_SAVABLE=y BUILD_DIR=... build-xxx/ysyx_26030103_npc
VERILATOR_SAVABLE ?= n
SAVABLE_VERILATOR_FLAG = $(if $(filter y,$(VERILATOR_SAVABLE)),--savable,)
# NPC 顶层比 ysyxSoC 的 CPU 黑盒多出直接仿真别名（如 perf_trap_count）；
# 这些别名在 SoC 实例中故意不连接，PINMISSING 是预期行为，不应让整个
# SoC 仿真构建失败。RV64 调试 `_hi` 端口已由 ysyxSoC 黑盒显式连接。
VERILATOR_WARN_FLAGS ?= -Wno-PINMISSING
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
# 仅用于大批量 ACT 回归的仿真加速：APBSPI 走 spi_top_apb 的 FAST_FLASH
# 直读路径，真实 XIP 状态机默认仍开启。对应 Scala 侧由同名环境变量选择。
SOC_FLASH_XIP_FAST ?= 0
ifneq ($(filter 1 y yes true on,$(SOC_FLASH_XIP_FAST)),)
VERILATOR_FLASH_XIP_DEFINE = +define+FAST_FLASH
YSYXSOC_FLASH_XIP_ENV = SOC_FLASH_XIP_FAST=1
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
