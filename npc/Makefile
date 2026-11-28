NPC_HOME := $(CURDIR)
SHELL := /bin/bash
# 构建根目录；每个 effective config + build signature 的实际产物在
# build/<CONFIG_HASH>/<BUILD_SIGNATURE>/，配置投影在 build/<CONFIG_HASH>/config/。
# 旧路径 build/ysyx_26030103_npc 等是指向当前配置/签名的兼容 symlink。
BUILD_DIR = ./build
# direct-NPC RAM 窗口。大小来自 Kconfig（默认 64 MiB），DTB、RTL、
# C++ 加载器、Linux 打包和 SBI 测试共享同一个值，不在各个脚本中
# 另造 Linux 专用 RAM 常量。需要改值时使用 CONFIG_NPC_RAM_SIZE。
override NPC_RAM_BASE := $(or $(CONFIG_NPC_RAM_BASE),0x80000000)
override NPC_RAM_SIZE := $(or $(CONFIG_NPC_RAM_SIZE),0x04000000)
PERF_BUILD_METADATA := $(abspath $(BUILD_DIR)/perf_build_metadata.txt)
PERF_SYNTH := $(abspath $(BUILD_DIR)/synth.txt)
YSYXSOC_DIR = ../ysyxSoC
YSYXSOC_PERIP_DIR = $(YSYXSOC_DIR)/perip
YSYXSOC_PERIP_INCDIRS = \
	$(YSYXSOC_PERIP_DIR)/uart16550/rtl \
	$(YSYXSOC_PERIP_DIR)/spi/rtl
YSYXSOC_FULL_V=$(YSYXSOC_DIR)/build/ysyxSoCFull.v
#仿照Verilog版本的makefile
# ../Makefile is included near the end of this file, after make has already
# expanded target names.  Give the local build its canonical default here so
# targets do not accidentally become build/ and build/_npc.
STUID ?= ysyx_26030103
TOPNAME=$(STUID)
SOC_TOPNAME=ysyxSoCFull
SIM_TOP ?= npc
NPC_VERILATOR_MK = Vriscv32e_npc_SimTop.mk
# Verilator 中间文件、RTL、可执行文件、GSIM 产物的路径由
# scripts/builddir.mk 按 effective config hash 展开。

CSRC_DIR=./csrc
CSRCS=$(shell find $(CSRC_DIR) -path "$(CSRC_DIR)/Test" -prune -o -path "$(CSRC_DIR)/gsim" -prune -o -path "$(CSRC_DIR)/tools/cachesim" -prune -o -path "$(CSRC_DIR)/tools/branchsim" -prune -o -path "$(CSRC_DIR)/tools/dcachesim" -prune -o -name "*.cpp" -print 2>/dev/null)
# C++ 侧配置入口是生成的模块，不是头文件。模块只从 .config 派生，
# 不构成第二配置源；verilator-build.mk 会把它放在模块依赖链最前面。
#
# 生成器先写入 build/generated/ staging（config hash 的稳定输入），随后
# 按 hash 复制到 build/<CONFIG_HASH>/config/。主构建消费投影副本；
# staging 路径保留给仍按旧路径读取的测试/工具。
NPC_CONFIG_JSON   := $(abspath include/generated/npc_config.json)
# Kconfig 的 C 头投影与 npc_config.json 由同一次 syncconfig 生成。这里必须
# 在 scripts/builddir.mk 之前定义：CONFIG_HASH_MK 的依赖在解析期展开。
NPC_CONFIG_AUTOCONF := $(abspath include/generated/autoconf.h)
NPC_CONFIG_STAGE_DIR := $(abspath $(BUILD_DIR)/generated)
NPC_CONFIG_MODULE_STAGE := $(NPC_CONFIG_STAGE_DIR)/npc_config.ixx
# C++ 模块、Scala elaboration 和脚本共用同一次 Kconfig + Makefile/命令行
# 覆盖的结果；effective 保留扁平键值，typed 是同一结果的结构化投影。
NPC_CONFIG_EFFECTIVE_STAGE := $(NPC_CONFIG_STAGE_DIR)/npc_config.effective.json
# RTL elaboration 使用嵌套 typed JSON；effective flat JSON 保留给 C++ 模块、
# 脚本和仍按扁平键读取的兼容路径。
NPC_CONFIG_TYPED_STAGE := $(NPC_CONFIG_STAGE_DIR)/npc_config.typed.json
# mill 的源目录固定为 npc/src，生成的 Scala 默认值必须留在 src/generated；
# 投影目录只保存快照，不能改变 mill 实际编译的源码路径。
NPC_CONFIG_SCALA_DEFAULTS := $(abspath src/generated/ysyx_26030103_ConfigDefaults.scala)
NPC_CONFIG_GEN_DIR := $(abspath tools/NPCConfigGEN)
NPC_CONFIG_GEN_SOURCES := $(wildcard $(NPC_CONFIG_GEN_DIR)/*.cpp) \
	$(wildcard $(NPC_CONFIG_GEN_DIR)/JsonConfig/*.cpp) \
	$(wildcard $(NPC_CONFIG_GEN_DIR)/DeriveConfig/*.cpp) \
	$(wildcard $(NPC_CONFIG_GEN_DIR)/ModuleConfig/*.cpp) \
	$(wildcard $(NPC_CONFIG_GEN_DIR)/TypedConfig/*.cpp)
NPC_CONFIG_GEN_MODULE := $(NPC_CONFIG_GEN_DIR)/npc.configgen.ixx
NPC_CONFIG_GEN_CMAKE := $(NPC_CONFIG_GEN_DIR)/CMakeLists.txt
NPC_CONFIG_GEN_BUILD_DIR := $(abspath tools/build/NPCConfigGEN)
NPC_CONFIG_GEN_BIN := $(abspath tools/build/npc_config_gen)
include $(NPC_CONFIG_GEN_DIR)/build.mk
NPC_BUILD_TOOLS_DIR := $(abspath tools/BuildTools)
NPC_BUILD_TOOLS_SOURCES := $(wildcard $(NPC_BUILD_TOOLS_DIR)/*.cpp)
NPC_BUILD_TOOLS_MODULE := $(NPC_BUILD_TOOLS_DIR)/npc.buildtools.ixx
NPC_BUILD_TOOLS_CMAKE := $(NPC_BUILD_TOOLS_DIR)/CMakeLists.txt
NPC_BUILD_TOOLS_BUILD_DIR := $(abspath tools/build/BuildTools)
NPC_BUILD_TOOLS_STAMP := $(NPC_BUILD_TOOLS_BUILD_DIR)/.built
NPC_BUILD_TOOLS_JOBS ?= $(shell nproc 2>/dev/null || echo 4)
$(NPC_BUILD_TOOLS_STAMP): $(NPC_BUILD_TOOLS_SOURCES) $(NPC_BUILD_TOOLS_MODULE) $(NPC_BUILD_TOOLS_CMAKE)
	@cmake -S "$(NPC_BUILD_TOOLS_DIR)" -B "$(NPC_BUILD_TOOLS_BUILD_DIR)" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$(CXX)"
	@cmake --build "$(NPC_BUILD_TOOLS_BUILD_DIR)" -j $(NPC_BUILD_TOOLS_JOBS)
	@touch $@
NPC_CONFIG_STAMP  := $(abspath $(BUILD_DIR)/generated/npc_config.stamp)
# 按 effective config hash + build signature 隔离的构建目录（必须在
# NPC_CONFIG_TYPED_STAGE 定义之后、所有消费 OBJ_DIR/BIN/GSIM_DIR 的脚本之前包含）。
include scripts/builddir.mk
# 主构建读取 build/<CONFIG_HASH>/config/ 下的投影副本；staging 只作为
# hash 输入和旧测试路径的兼容层。
NPC_CONFIG_EFFECTIVE_JSON := $(CONFIG_PROJECTION_DIR)/npc_config.effective.json
NPC_CONFIG_TYPED_JSON := $(CONFIG_PROJECTION_DIR)/npc_config.typed.json
NPC_CONFIG_MODULE := $(CONFIG_PROJECTION_DIR)/npc_config.ixx
# C++ 生成器负责所有派生值。Makefile 只转发 Kconfig 风格的 CONFIG_*
# 命令行覆盖；不再提供 SOC_XLEN/CHIPLINK 等裸 Make 变量兼容入口。
NPC_CONFIG_OVERRIDE_ARGS = $(foreach item,$(filter CONFIG_%,$(MAKEOVERRIDES)),--set '$(item)')

# direct-NPC的SimTop允许32/64位SoC AXI，AXIRAM使用同一宽度直接连接；
# 完整SoC仍按Kconfig的SOC_XLEN生成

VERILATOR=verilator
remove_quote = $(patsubst "%",%,$(1))
# Kconfig 字符串可能包含空格/逗号（如 bootargs）。patsubst 会按空格拆词，
# 不能用来去掉这类字符串的引号；这里只删除引号并保留原样。
unquote_config = $(strip $(subst ",,$(1)))
-include include/config/auto.conf
# FPGA 模式：关闭 Verilator 仿真构建，但保留 verilog/synth。
SIM_GUARD :=
ifeq ($(CONFIG_NPC_MODE_FPGA_ONLY),y)
SIM_GUARD := sim-disabled
endif
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

# 配置工具、生成物与 Kconfig 契约
include scripts/config.mk

# csrc/Test 注册表入口
include scripts/test.mk

# SBI / Linux / U-Boot / rootfs 等平台目标
include scripts/platform.mk

# 编译器、标准库、告警/消毒器与 Verilator/GSIM 编译选项
include scripts/toolchain.mk

# 性能元数据、综合与 perf
include scripts/perf.mk

# Chisel/FIRRTL/Verilog 生成与 ysyxSoC RTL
include scripts/rtl.mk

# Verilator/GSIM 构建、运行与测试
include scripts/sim.mk

-include ../Makefile
