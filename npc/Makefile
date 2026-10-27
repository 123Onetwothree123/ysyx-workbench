NPC_HOME := $(CURDIR)
SHELL := /bin/bash
BUILD_DIR = ./build
# direct-NPC RAM 窗口。大小来自 Kconfig（默认 64 MiB），DTB、RTL、
# C++ 加载器、Linux 打包和 SBI 测试共享同一个值，不在各个脚本中
# 另造 Linux 专用 RAM 常量。需要改值时使用 CONFIG_NPC_RAM_SIZE。
override NPC_RAM_BASE := 0x80000000
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
#verilator生成C++的中间文件的目录
OBJ_DIR=$(BUILD_DIR)/obj_dir_$(TOPNAME)
#最后生成的可以执行的文件，给后面的run用的
BIN=$(BUILD_DIR)/$(TOPNAME)
VSRCS=$(shell find $(BUILD_DIR) -maxdepth 1 -type f -name "*.sv" ! -name "riscv32e_npc*" ! -name "ysyx_*_npc*" ! -name "*_yosys*" ! -name "*_merged*" ! -name "*_sv2v*" 2>/dev/null | sort) \
      $(YSYXSOC_FULL_V) \
      $(shell find $(BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort) \
      $(shell find $(YSYXSOC_PERIP_DIR) -type f -name "*.v" 2>/dev/null | sort)
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
CSRCS=$(shell find $(CSRC_DIR) -path "$(CSRC_DIR)/Test" -prune -o -path "$(CSRC_DIR)/tools/cachesim" -prune -o -path "$(CSRC_DIR)/tools/branchsim" -prune -o -path "$(CSRC_DIR)/tools/dcachesim" -prune -o -name "*.cpp" -print 2>/dev/null)
# C++ 侧配置入口是生成的模块，不是头文件。模块只从 .config 派生，
# 不构成第二配置源；verilator-build.mk 会把它放在模块依赖链最前面。
NPC_CONFIG_JSON   := $(abspath include/generated/npc_config.json)
NPC_CONFIG_MODULE := $(abspath $(CSRC_DIR)/generated/npc_config.ixx)
# C++ 模块、Scala elaboration 和脚本共用同一次 Kconfig + Makefile/命令行
# 覆盖的结果；effective 保留扁平键值，typed 是同一结果的结构化投影。
NPC_CONFIG_EFFECTIVE_JSON := $(abspath $(BUILD_DIR)/generated/npc_config.effective.json)
# RTL elaboration 使用嵌套 typed JSON；effective flat JSON 保留给 C++ 模块、
# 脚本和仍按扁平键读取的兼容路径。
NPC_CONFIG_TYPED_JSON := $(abspath $(BUILD_DIR)/generated/npc_config.typed.json)
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
# C++ 生成器负责所有派生值。Makefile 只转发 Kconfig 风格的 CONFIG_*
# 命令行覆盖；不再提供 SOC_XLEN/CHIPLINK 等裸 Make 变量兼容入口。
NPC_CONFIG_OVERRIDE_ARGS = $(foreach item,$(filter CONFIG_%,$(MAKEOVERRIDES)),--set '$(item)')

VERILATOR=verilator
remove_quote = $(patsubst "%",%,$(1))
# Kconfig 字符串可能包含空格/逗号（如 bootargs）。patsubst 会按空格拆词，
# 不能用来去掉这类字符串的引号；这里只删除引号并保留原样。
unquote_config = $(strip $(subst ",,$(1)))
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


# Kconfig 是唯一配置解析器：c_conf --syncconfig 直接生成
# include/generated/npc_config.json。C++ 构建期小工具再把 JSON 投影成
# 编译期模块 npc.config；运行时不解析 JSON，因为 XLen/数组大小/if
# constexpr 都必须是编译期常量。
$(NPC_CONFIG_JSON): .config $(CONF)
	$(CONF) -s --syncconfig $(Kconfig)

.PHONY: npc-config-stamp-force
npc-config-stamp-force:
$(NPC_CONFIG_STAMP): npc-config-stamp-force
	@mkdir -p $(@D)
	@printf '%s\n' \
		"savable=$(VERILATOR_SAVABLE)" \
		"perf_build=$(PERF_BUILD_METADATA)" \
		"perf_synth=$(PERF_SYNTH)" \
		"overrides=$(NPC_CONFIG_OVERRIDE_ARGS)" > $@.tmp
	@if test -f $@ && cmp -s $@.tmp $@; then rm -f $@.tmp; else mv -f $@.tmp $@; fi

# 三个产物必须一起生成：模块给 C++，flat JSON 给脚本/C++ 构建，
# typed JSON 给 Scala elaboration。grouped target 保证任一缺失时会重跑
# 同一份配置生成命令。
$(NPC_CONFIG_MODULE) $(NPC_CONFIG_EFFECTIVE_JSON) $(NPC_CONFIG_TYPED_JSON) &: $(NPC_CONFIG_JSON) $(NPC_CONFIG_GEN_BIN) $(NPC_CONFIG_STAMP)
	@mkdir -p $(dir $(NPC_CONFIG_MODULE))
	$(NPC_CONFIG_GEN_BIN) \
		--config-json $(NPC_CONFIG_JSON) \
		--effective-json $(NPC_CONFIG_EFFECTIVE_JSON) \
		--typed-json $(NPC_CONFIG_TYPED_JSON) \
		--perf-build-metadata $(PERF_BUILD_METADATA) \
		--perf-synth $(PERF_SYNTH) \
		--savable $(VERILATOR_SAVABLE) \
		$(NPC_CONFIG_OVERRIDE_ARGS) \
		--output $(NPC_CONFIG_MODULE)

npc-config-json: $(NPC_CONFIG_JSON)
npc-config-tool: $(NPC_CONFIG_GEN_BIN)
npc-config: $(NPC_CONFIG_MODULE) $(NPC_CONFIG_EFFECTIVE_JSON) $(NPC_CONFIG_TYPED_JSON)

.PHONY: npc-config npc-config-json npc-config-tool

# 配置契约：Kconfig 只能表达连续 range，这里补齐 XLEN/SOC_XLEN、
# PTE 大小、页大小 2 的幂以及 CUSTOM A/D 高位等离散约束。
# 校验器是纯 C++ 工具，配置阶段和回归都不再依赖 Python。
KCONFIG_CONTRACT_SRC := $(abspath tools/kconfig_contract.cpp)
KCONFIG_CONTRACT_BIN := $(abspath tools/build/kconfig_contract)
KCONFIG_CONTRACT_CXXFLAGS ?= -std=c++23 -O2 -Wall -Wextra

$(KCONFIG_CONTRACT_BIN): $(KCONFIG_CONTRACT_SRC)
	@mkdir -p $(@D)
	$(CXX) $(KCONFIG_CONTRACT_CXXFLAGS) -o $@ $<

kconfig-contract-check: $(KCONFIG_CONTRACT_BIN)
	$(KCONFIG_CONTRACT_BIN) --defconfigs
	$(KCONFIG_CONTRACT_BIN) --self-test
	@if [ -f .config ]; then $(KCONFIG_CONTRACT_BIN) --config .config; fi

kconfig-contract-tool: $(KCONFIG_CONTRACT_BIN)

.PHONY: kconfig-contract-check kconfig-contract-tool

# ============================================================================
# M 模式 SBI 固件
#
# SBI_ENABLE 默认 n；关闭时下列目标会明确报错，不会静默跳过。
# 实现通过 SBI_IMPL 字符串分派到 scripts/build_<impl>.sh：
#   opensbi -> scripts/build_opensbi.sh
# 后续增加 RustSBI 时只需新增 Kconfig choice + scripts/build_rustsbi.sh，
# 本 Makefile 的目标逻辑不需要改。
# ============================================================================

# Linux 启动镜像选项。地址完全复用 SBI_NEXT_ADDR / SBI_FDT_ADDR：
# Image 没有独立地址符号，DTB 使用 SBI_FDT_ADDR。
LINUX_BOOT_ENABLE ?= $(or $(CONFIG_LINUX_BOOT_ENABLE),n)
LINUX_IMAGE_PATH ?= $(call unquote_config,$(CONFIG_LINUX_IMAGE_PATH))
LINUX_INITRAMFS_ENABLE ?= $(or $(CONFIG_LINUX_INITRAMFS_ENABLE),n)
LINUX_INITRAMFS_PATH ?= $(call unquote_config,$(CONFIG_LINUX_INITRAMFS_PATH))
LINUX_INITRAMFS_ADDR ?= $(or $(CONFIG_LINUX_INITRAMFS_ADDR),0x82000000)
LINUX_BOOTARGS ?= $(call unquote_config,$(CONFIG_LINUX_BOOTARGS))
LINUX_OUT_DIR ?= $(abspath $(BUILD_DIR)/linux)
LINUX_SBI_OUT_DIR ?= $(LINUX_OUT_DIR)/sbi
LINUX_DTB_OUT_DIR ?= $(LINUX_OUT_DIR)/dtb
LINUX_PACK_TOOL := $(abspath tools/build/pack_linux_image)
# 主构建直接调用 C++ 工具；shell 入口保留给 NEMU/直接调用。
LINUX_PACK_SCRIPT ?= $(LINUX_PACK_TOOL)

$(LINUX_PACK_TOOL): $(NPC_BUILD_TOOLS_STAMP)
	@cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/pack_linux_image" "$@"

pack-linux-tool: $(LINUX_PACK_TOOL)
.PHONY: pack-linux-tool

SBI_IMPL ?= $(or $(call remove_quote,$(CONFIG_SBI_IMPL)),opensbi)
SBI_PLATFORM ?= $(or $(call remove_quote,$(CONFIG_SBI_PLATFORM)),npc)
SBI_FW_BASE ?= $(or $(CONFIG_SBI_FW_BASE),0x80000000)
SBI_FW_SIZE ?= $(or $(CONFIG_SBI_FW_SIZE),0x00200000)
SBI_NEXT_ADDR ?= $(or $(CONFIG_SBI_NEXT_ADDR),0x80200000)
SBI_BUILD_JOBS ?= $(or $(CONFIG_SBI_BUILD_JOBS),0)
SBI_FDT_ENABLE ?= $(or $(CONFIG_SBI_FDT_ENABLE),n)
SBI_FDT_PATH ?= $(or $(call unquote_config,$(CONFIG_SBI_FDT_PATH)),dts/npc.dts)
SBI_FDT_ADDR ?= $(or $(CONFIG_SBI_FDT_ADDR),0x80f00000)
# mtime 每个 CPU 周期 +1，实际增量频率 = SYNTH_FREQ(MHz) * 1,000,000。
# DTB timebase-frequency 与 OpenSBI NPC 平台的 mtime_freq 使用同一个值。
SBI_TIMEBASE_FREQ ?= $(shell expr $(SYNTH_FREQ) \* 1000000)
SBI_FDT_LINUX ?= $(LINUX_BOOT_ENABLE)
SBI_INITRAMFS_ENABLE ?= $(LINUX_INITRAMFS_ENABLE)
SBI_INITRAMFS_PATH ?= $(LINUX_INITRAMFS_PATH)
SBI_INITRAMFS_ADDR ?= $(LINUX_INITRAMFS_ADDR)
SBI_BOOTARGS ?= $(LINUX_BOOTARGS)
SBI_FDT_OUT_DIR ?= $(abspath $(BUILD_DIR)/dtb)
SBI_DTB_TOOL := $(abspath tools/build/build_dtb)
# 主构建直接调用 C++ 工具；shell 入口保留给 NEMU/直接调用。
SBI_DTB_SCRIPT ?= $(SBI_DTB_TOOL)

$(SBI_DTB_TOOL): $(NPC_BUILD_TOOLS_STAMP)
	@cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/build_dtb" "$@"

build-dtb-tool: $(SBI_DTB_TOOL)
.PHONY: build-dtb-tool
SBI_TOOLCHAIN_PREFIX ?= riscv64-unknown-elf-
SBI_ISA ?= rv64ima_zicsr_zifencei
SBI_ABI ?= lp64
SBI_LD ?= ld.lld
SBI_VENDOR_DIR ?= $(abspath $(NPC_HOME)/../vendor/opensbi)
SBI_OUT_DIR ?= $(abspath $(BUILD_DIR)/$(SBI_IMPL))
SBI_BUILD_SCRIPT ?= $(abspath scripts/build_$(SBI_IMPL).sh)
SBI_TEST_SCRIPT ?= $(abspath scripts/test_$(SBI_IMPL).sh)

# 把配置一次传给实现脚本；脚本自身仍负责校验和明确报错。
SBI_ENV = env \
	SBI_ENABLE="$(or $(CONFIG_SBI_ENABLE),n)" \
	SBI_IMPL="$(SBI_IMPL)" \
	SBI_PLATFORM="$(SBI_PLATFORM)" \
	SBI_FW_BASE="$(SBI_FW_BASE)" \
	SBI_FW_SIZE="$(SBI_FW_SIZE)" \
	SBI_NEXT_ADDR="$(SBI_NEXT_ADDR)" \
	SBI_BUILD_JOBS="$(SBI_BUILD_JOBS)" \
	SBI_FDT_ENABLE="$(SBI_FDT_ENABLE)" \
	SBI_FDT_PATH="$(SBI_FDT_PATH)" \
	SBI_FDT_ADDR="$(SBI_FDT_ADDR)" \
	SBI_FDT_OUT_DIR="$(SBI_FDT_OUT_DIR)" \
	SBI_TIMEBASE_FREQ="$(SBI_TIMEBASE_FREQ)" \
	SBI_FDT_LINUX="$(SBI_FDT_LINUX)" \
	SBI_XLEN="$(or $(CONFIG_XLEN),64)" \
	SBI_HAS_S="$(or $(CONFIG_HAS_S),n)" \
	SBI_HAS_PMP="$(or $(CONFIG_HAS_PMP),n)" \
	SBI_CACHE_PADDING="$(or $(CONFIG_CACHE_PADDING),0)" \
	SBI_INITRAMFS_ENABLE="$(SBI_INITRAMFS_ENABLE)" \
	SBI_INITRAMFS_PATH="$(SBI_INITRAMFS_PATH)" \
	SBI_INITRAMFS_ADDR="$(SBI_INITRAMFS_ADDR)" \
	SBI_BOOTARGS="$(SBI_BOOTARGS)" \
	NPC_RAM_BASE="$(NPC_RAM_BASE)" \
	NPC_RAM_SIZE="$(NPC_RAM_SIZE)" \
	SBI_TOOLCHAIN_PREFIX="$(SBI_TOOLCHAIN_PREFIX)" \
	SBI_ISA="$(SBI_ISA)" \
	SBI_ABI="$(SBI_ABI)" \
	SBI_LD="$(SBI_LD)" \
	SBI_VENDOR_DIR="$(SBI_VENDOR_DIR)" \
	SBI_OUT_DIR="$(SBI_OUT_DIR)" \
	SBI_PAYLOAD_PATH="$(SBI_PAYLOAD_PATH)" \
	NPC_HOME="$(NPC_HOME)" \
	WORKBENCH_HOME="$(abspath $(NPC_HOME)/..)"

# Linux 打包环境 = SBI 公共环境 + Linux 专属路径/参数。
LINUX_ENV = $(SBI_ENV) \
	LINUX_BOOT_ENABLE="$(LINUX_BOOT_ENABLE)" \
	LINUX_IMAGE_PATH="$(LINUX_IMAGE_PATH)" \
	LINUX_INITRAMFS_ENABLE="$(LINUX_INITRAMFS_ENABLE)" \
	LINUX_INITRAMFS_PATH="$(LINUX_INITRAMFS_PATH)" \
	LINUX_INITRAMFS_ADDR="$(LINUX_INITRAMFS_ADDR)" \
	LINUX_BOOTARGS="$(LINUX_BOOTARGS)" \
	LINUX_OUT_DIR="$(LINUX_OUT_DIR)"

# 校验开关和实现名。实现名会拼进脚本路径，必须限制字符集。
define sbi_check_enabled
	@set -eu; \
	if [ "$(or $(CONFIG_SBI_ENABLE),n)" != "y" ]; then \
		echo "错误：SBI_ENABLE=n，SBI 固件未启用。" >&2; \
		echo "      先运行 'make rv64_sbi_defconfig' 或 'make menuconfig' 打开 M 模式 SBI 固件。" >&2; \
		exit 2; \
	fi; \
	if [ "$(SBI_IMPL)" = "none" ]; then \
		echo "错误：SBI_ENABLE=y 但没有选择 SBI 实现（SBI_IMPL=none）。" >&2; \
		exit 2; \
	fi; \
	case "$(SBI_IMPL)" in \
		''|*[!A-Za-z0-9_]*) \
			echo "错误：SBI_IMPL='$(SBI_IMPL)' 非法（只允许字母、数字、下划线）。" >&2; \
			exit 2;; \
	esac
endef

.PHONY: sbi opensbi sbi-test opensbi-test

sbi: $(SBI_DTB_TOOL)
	$(sbi_check_enabled)
	@set -eu; \
	if [ ! -f "$(SBI_BUILD_SCRIPT)" ]; then \
		echo "错误：SBI 实现 '$(SBI_IMPL)' 的构建脚本不存在：$(SBI_BUILD_SCRIPT)" >&2; \
		echo "      请在 Kconfig 中选择已实现的 SBI_IMPL，或补齐该脚本。" >&2; \
		exit 2; \
	fi; \
	$(SBI_ENV) "$(SBI_BUILD_SCRIPT)"

# 历史/任务要求的别名；实际实现由 SBI_IMPL 决定。
opensbi: sbi

# 测试目标：构建最小 S-mode payload + 固件，并在已构建的 direct-NPC 上运行。
sbi-test:
	$(sbi_check_enabled)
	@set -eu; \
	if [ ! -f "$(SBI_TEST_SCRIPT)" ]; then \
		echo "错误：SBI 实现 '$(SBI_IMPL)' 的测试脚本不存在：$(SBI_TEST_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(SBI_ENV) "$(SBI_TEST_SCRIPT)"

opensbi-test: sbi-test

# 单独生成设备树，便于检查 DTS 或给外部加载器使用。
# 即使 SBI_FDT_ENABLE=n 也允许显式生成（不会改变 SBI 启动行为）。
# 依赖 effective JSON，确保 .config 改变后不会继续使用旧的配置投影。
dtb: $(NPC_CONFIG_EFFECTIVE_JSON) $(SBI_DTB_TOOL)
	@$(SBI_ENV) "$(SBI_DTB_SCRIPT)"

dtb-clean:
	rm -rf $(SBI_FDT_OUT_DIR)

.PHONY: dtb dtb-clean

# 打包 Linux 启动镜像：SBI 固件 + 用户提供的 Image + 可选 initramfs + DTB。
# 本目标不会下载/编译 Linux；Image/initramfs 缺失时明确报错。
# 顺序很重要：先让 build_<impl>.sh 按当前 Kconfig/initramfs 生成并内嵌
# DTB，再由 C++ pack_linux_image 重新生成同一 DTB、校验固件内嵌哈希并打包。
linux-image: $(LINUX_PACK_TOOL) $(SBI_DTB_TOOL)
	$(sbi_check_enabled)
	@set -eu; \
	if [ "$(LINUX_BOOT_ENABLE)" != "y" ]; then \
		echo "错误：LINUX_BOOT_ENABLE=$(LINUX_BOOT_ENABLE)，Linux 启动镜像未启用。" >&2; \
		echo "      先运行 'make rv64_linux_defconfig' 或 'make menuconfig' 打开 Linux 启动镜像。" >&2; \
		exit 2; \
	fi; \
	if [ -z "$(LINUX_IMAGE_PATH)" ]; then \
		echo "错误：LINUX_IMAGE_PATH 为空；请提供用户自己的 Linux Image 路径。" >&2; \
		exit 2; \
	fi; \
	if [ "$(SBI_FDT_ENABLE)" != "y" ]; then \
		echo "错误：Linux 需要 a1=DTB，但 SBI_FDT_ENABLE=$(SBI_FDT_ENABLE)。" >&2; \
		echo "      请打开 CONFIG_SBI_FDT_ENABLE 后重试。" >&2; \
		exit 2; \
	fi; \
	if [ ! -f "$(LINUX_PACK_SCRIPT)" ]; then \
		echo "错误：找不到打包工具 $(LINUX_PACK_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	mkdir -p "$(LINUX_OUT_DIR)"; \
	$(SBI_ENV) \
		SBI_OUT_DIR="$(LINUX_SBI_OUT_DIR)" \
		SBI_FDT_OUT_DIR="$(LINUX_DTB_OUT_DIR)" \
		"$(SBI_BUILD_SCRIPT)"; \
	$(LINUX_ENV) \
		SBI_OUT_DIR="$(LINUX_SBI_OUT_DIR)" \
		SBI_FDT_OUT_DIR="$(LINUX_DTB_OUT_DIR)" \
		"$(LINUX_PACK_SCRIPT)"

linux-image-clean:
	rm -rf $(LINUX_OUT_DIR)

# 配置矩阵测试：dummy Image/initramfs + DTB 生成 + 打包负向测试。
# 不构建/运行 Linux，也不需要 NPC 仿真器。
# 测试器是纯 C++ 工具，通过兼容脚本调用 C++ DTB/pack 工具；
# 不再依赖 Python。
LINUX_BOOT_TEST_SRC := $(abspath tools/linux_boot_test.cpp)
LINUX_BOOT_TEST_BIN := $(abspath tools/build/linux_boot_test)
LINUX_BOOT_TEST_CXXFLAGS ?= -std=c++23 -O2 -Wall -Wextra

$(LINUX_BOOT_TEST_BIN): $(LINUX_BOOT_TEST_SRC)
	@mkdir -p $(@D)
	$(CXX) $(LINUX_BOOT_TEST_CXXFLAGS) -o $@ $<

linux-boot-test: $(LINUX_BOOT_TEST_BIN) $(LINUX_PACK_TOOL) $(SBI_DTB_TOOL) $(NPC_CONFIG_GEN_BIN)
	$(LINUX_BOOT_TEST_BIN)

.PHONY: linux-image linux-image-clean linux-boot-test

# ============================================================================
# U-Boot EFI 启动镜像（NPC）
#
# 与 NEMU 共用 vendor/u-boot 的 nemu_riscv64_smode_defconfig：两者在
# S-mode 看到的 UART/CLINT/PLIC 地址和 SBI 接口相同，区别只在 DTB
# timebase（NPC 450MHz）与 RAM 大小。地址全部来自 Kconfig/Makefile，
# 不在脚本中另写一套。默认 64 MiB 布局与 NEMU 相同；128 MiB 配置把
# DTB 移到 0x87f00000，其余载荷地址保持不变。
# ============================================================================
NPC_UBOOT_ENABLE ?= $(or $(CONFIG_NPC_UBOOT_ENABLE),n)
NPC_UBOOT_OUT_DIR ?= $(abspath $(BUILD_DIR)/uboot)
NPC_UBOOT_BUILD_SCRIPT ?= $(abspath scripts/build_uboot_npc.sh)
NPC_UBOOT_DEFCONFIG ?= nemu_riscv64_smode_defconfig
NPC_UBOOT_EFI_APP_PATH ?= $(call unquote_config,$(CONFIG_NPC_UBOOT_EFI_APP_PATH))
NPC_UBOOT_INITRAMFS_PATH ?= $(call unquote_config,$(CONFIG_NPC_UBOOT_INITRAMFS_PATH))
NPC_UBOOT_KERNEL_PATH ?= $(call unquote_config,$(CONFIG_NPC_UBOOT_KERNEL_PATH))
NPC_UBOOT_EFI_APP_ADDR ?= $(or $(CONFIG_NPC_UBOOT_EFI_APP_ADDR),0x80600000)
NPC_UBOOT_INITRAMFS_ADDR ?= $(or $(CONFIG_NPC_UBOOT_INITRAMFS_ADDR),0x80800000)
NPC_UBOOT_KERNEL_ADDR ?= $(or $(CONFIG_NPC_UBOOT_KERNEL_ADDR),0x81000000)
NPC_UBOOT_BOOTARGS ?= $(or $(call unquote_config,$(CONFIG_NPC_UBOOT_BOOTARGS)),console=ttyS0,115200 earlycon=sbi no5lvl cpuidle.off=1 loglevel=7)
NPC_UBOOT_JOBS ?= $(or $(CONFIG_NPC_UBOOT_JOBS),0)
NPC_UBOOT_PACK_SRC := $(abspath tools/pack_uboot_image.cpp)
NPC_UBOOT_PACK_BIN := $(abspath tools/build/pack_uboot_image)
NPC_UBOOT_PACK_CXXFLAGS ?= -std=c++23 -O2 -Wall -Wextra

$(NPC_UBOOT_PACK_BIN): $(NPC_UBOOT_PACK_SRC)
	@mkdir -p $(@D)
	$(CXX) $(NPC_UBOOT_PACK_CXXFLAGS) -o $@ $<

NPC_UBOOT_ENV = $(SBI_ENV) \
	NPC_UBOOT_PACK_BIN="$(NPC_UBOOT_PACK_BIN)" \
	NPC_UBOOT_OUT_DIR="$(NPC_UBOOT_OUT_DIR)" \
	NPC_UBOOT_DEFCONFIG="$(NPC_UBOOT_DEFCONFIG)" \
	NPC_UBOOT_EFI_APP_PATH="$(NPC_UBOOT_EFI_APP_PATH)" \
	NPC_UBOOT_INITRAMFS_PATH="$(NPC_UBOOT_INITRAMFS_PATH)" \
	NPC_UBOOT_KERNEL_PATH="$(NPC_UBOOT_KERNEL_PATH)" \
	NPC_UBOOT_EFI_APP_ADDR="$(NPC_UBOOT_EFI_APP_ADDR)" \
	NPC_UBOOT_INITRAMFS_ADDR="$(NPC_UBOOT_INITRAMFS_ADDR)" \
	NPC_UBOOT_KERNEL_ADDR="$(NPC_UBOOT_KERNEL_ADDR)" \
	NPC_UBOOT_BOOTARGS="$(NPC_UBOOT_BOOTARGS)" \
	NPC_UBOOT_JOBS="$(NPC_UBOOT_JOBS)"

uboot-image: $(NPC_UBOOT_PACK_BIN) $(SBI_DTB_TOOL)
	@set -eu; \
	if [ "$(NPC_UBOOT_ENABLE)" != "y" ]; then \
		echo "错误：NPC_UBOOT_ENABLE=$(NPC_UBOOT_ENABLE)，U-Boot EFI 镜像未启用。" >&2; \
		echo "      先运行 'make rv64_linux_uboot_128m_defconfig' 或 'make menuconfig'。" >&2; \
		exit 2; \
	fi; \
	if [ ! -f "$(NPC_UBOOT_BUILD_SCRIPT)" ]; then \
		echo "错误：找不到 $(NPC_UBOOT_BUILD_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(NPC_UBOOT_ENV) "$(NPC_UBOOT_BUILD_SCRIPT)"

uboot-image-clean:
	rm -rf $(NPC_UBOOT_OUT_DIR)

.PHONY: uboot-image uboot-image-clean

# 显式构建 RustSBI 固件，不影响默认构建，也不与 make opensbi 共用产物。
# 脚本读取 Kconfig 参数；这里复用 SBI_ENV 传入 SBI_FW_*/SBI_FDT_*，
# 但把输出目录固定到 build/rustsbi，避免 SBI_IMPL=opensbi 时覆盖
# OpenSBI 的 fw.bin。
RUSTSBI_BUILD_SCRIPT := $(abspath scripts/build_rustsbi.sh)
RUSTSBI_ENV = $(SBI_ENV) \
	SBI_OUT_DIR="$(abspath $(BUILD_DIR)/rustsbi)" \
	RUSTSBI_OUT_DIR="$(abspath $(BUILD_DIR)/rustsbi)"
rustsbi: $(SBI_DTB_TOOL)
	@$(RUSTSBI_ENV) "$(RUSTSBI_BUILD_SCRIPT)"

rustsbi-clean:
	rm -rf $(BUILD_DIR)/rustsbi

.PHONY: rustsbi rustsbi-clean

# ============================================================================
# 根文件系统（BusyBox initramfs）
#
# ROOTFS_ENABLE=n 时 make rootfs 明确报错，不静默跳过。当前只实现
# BusyBox；Makefile 传入 ROOTFS_ENV，脚本在 npc/build/rootfs/ 下做 out-of-tree
# 构建，不修改 vendor/busybox。ELF 审计由 C++ 工具
# tools/BuildTools/audit_rootfs_elf.cpp 完成；Linux kernel 的下载、编译和启动不在
# 本目标范围内，rootfs 只产出可被内核加载的 initramfs。
# ============================================================================
ROOTFS_TYPE ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_TYPE)),none)
ROOTFS_OUT_DIR ?= $(abspath $(or $(call remove_quote,$(CONFIG_ROOTFS_OUT_DIR)),build/rootfs))
ROOTFS_BUSYBOX_BUILD_DIR ?= $(ROOTFS_OUT_DIR)/busybox
ROOTFS_TREE ?= $(ROOTFS_OUT_DIR)/tree
ROOTFS_IMAGE ?= $(ROOTFS_OUT_DIR)/busybox-initramfs.cpio.gz
ROOTFS_BUILD_SCRIPT ?= $(abspath scripts/build_rootfs_busybox.sh)
ROOTFS_BUSYBOX_PATH ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_PATH)),vendor/busybox)
ROOTFS_BUSYBOX_VERSION ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_VERSION)),1.36.1)
ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX)),riscv64-linux-gnu-)
ROOTFS_BUSYBOX_MARCH ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_MARCH)),rv64gc)
ROOTFS_BUSYBOX_MABI ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_MABI)),lp64d)
# auto.conf 只记录 y；缺失即表示 n，不能在这里回填 Kconfig 的 y 默认值。
ROOTFS_BUSYBOX_STATIC ?= $(or $(CONFIG_ROOTFS_BUSYBOX_STATIC),n)
ROOTFS_BUSYBOX_INIT_SCRIPT ?= $(or $(CONFIG_ROOTFS_BUSYBOX_INIT_SCRIPT),n)
# applet 列表本身含空格，不能使用按词匹配的 remove_quote。
ROOTFS_BUSYBOX_APPLETS ?= $(or $(subst ",,$(CONFIG_ROOTFS_BUSYBOX_APPLETS)),sh ls cat echo mount umount mkdir mknod ps kill sleep dmesg ifconfig ip vi grep sed awk find cp mv rm ln chmod chown df du free uname hostname reboot poweroff halt switch_root mdev insmod lsmod rmmod modprobe tar gzip gunzip wc head tail sort uniq tr cut printf test true false date id whoami env pwd touch sync init)
ROOTFS_BUSYBOX_JOBS ?= $(or $(CONFIG_ROOTFS_BUSYBOX_JOBS),0)
ROOTFS_FASTFETCH ?= $(or $(CONFIG_ROOTFS_FASTFETCH),y)
ROOTFS_FASTFETCH_PATH ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_FASTFETCH_PATH)),build/fastfetch/fastfetch)
ROOTFS_ELF_AUDIT_TOOL := $(abspath tools/build/audit_rootfs_elf)

$(ROOTFS_ELF_AUDIT_TOOL): $(NPC_BUILD_TOOLS_STAMP)
	@cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/audit_rootfs_elf" "$@"

audit-rootfs-elf-tool: $(ROOTFS_ELF_AUDIT_TOOL)
.PHONY: audit-rootfs-elf-tool

# 把配置通过环境传给实现脚本；脚本不再自行解析 .config。
ROOTFS_ENV = env \
	ROOTFS_ENABLE="$(or $(CONFIG_ROOTFS_ENABLE),n)" \
	ROOTFS_TYPE="$(ROOTFS_TYPE)" \
	ROOTFS_BUSYBOX="$(or $(CONFIG_ROOTFS_BUSYBOX),n)" \
	ROOTFS_BUSYBOX_PATH="$(ROOTFS_BUSYBOX_PATH)" \
	ROOTFS_BUSYBOX_VERSION="$(ROOTFS_BUSYBOX_VERSION)" \
	ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX="$(ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX)" \
	ROOTFS_BUSYBOX_MARCH="$(ROOTFS_BUSYBOX_MARCH)" \
	ROOTFS_BUSYBOX_MABI="$(ROOTFS_BUSYBOX_MABI)" \
	ROOTFS_BUSYBOX_STATIC="$(ROOTFS_BUSYBOX_STATIC)" \
	ROOTFS_BUSYBOX_INIT_SCRIPT="$(ROOTFS_BUSYBOX_INIT_SCRIPT)" \
	ROOTFS_BUSYBOX_APPLETS="$(ROOTFS_BUSYBOX_APPLETS)" \
	ROOTFS_BUSYBOX_JOBS="$(ROOTFS_BUSYBOX_JOBS)" \
	ROOTFS_FASTFETCH="$(ROOTFS_FASTFETCH)" \
	ROOTFS_FASTFETCH_PATH="$(ROOTFS_FASTFETCH_PATH)" \
	ROOTFS_OUT_DIR="$(ROOTFS_OUT_DIR)" \
	ROOTFS_BUSYBOX_BUILD_DIR="$(ROOTFS_BUSYBOX_BUILD_DIR)" \
	ROOTFS_TREE="$(ROOTFS_TREE)" \
	ROOTFS_IMAGE="$(ROOTFS_IMAGE)" \
	ELF_AUDIT_BIN="$(ROOTFS_ELF_AUDIT_TOOL)" \
	NPC_HOME="$(NPC_HOME)" \
	WORKBENCH_HOME="$(abspath $(NPC_HOME)/..)"

define rootfs_check_enabled
	@set -eu; \
	if [ "$(or $(CONFIG_ROOTFS_ENABLE),n)" != "y" ]; then \
		echo "错误：ROOTFS_ENABLE=n，rootfs 未启用。" >&2; \
		echo "      先运行 'make menuconfig' 打开“根文件系统 (rootfs)”，或使用带 CONFIG_ROOTFS_ENABLE=y 的 defconfig。" >&2; \
		exit 2; \
	fi; \
	if [ "$(ROOTFS_TYPE)" != "busybox" ]; then \
		echo "错误：ROOTFS_ENABLE=y 但 ROOTFS_TYPE='$(ROOTFS_TYPE)'；当前只实现 BusyBox。" >&2; \
		exit 2; \
	fi
endef

.PHONY: rootfs rootfs-clean

rootfs: $(ROOTFS_ELF_AUDIT_TOOL)
	$(rootfs_check_enabled)
	@set -eu; \
	if [ ! -f "$(ROOTFS_BUILD_SCRIPT)" ]; then \
		echo "错误：rootfs 构建脚本不存在：$(ROOTFS_BUILD_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(ROOTFS_ENV) "$(ROOTFS_BUILD_SCRIPT)"

# 清理只删除 rootfs 输出目录（默认 build/rootfs），不触碰 vendor/busybox。
rootfs-clean:
	@set -eu; \
	if [ ! -f "$(ROOTFS_BUILD_SCRIPT)" ]; then \
		echo "错误：rootfs 构建脚本不存在：$(ROOTFS_BUILD_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(ROOTFS_ENV) "$(ROOTFS_BUILD_SCRIPT)" --clean

# npc 独立仿真路径的复位 PC：0x80000000 + Kconfig 的 CACHE_PADDING。
NPC_RESET_PC := $(shell printf '0x%x' $$((0x80000000 + $(or $(CONFIG_CACHE_PADDING),0))))


# Keep the effective hardware knobs in one stable, human-readable record.
# The hash of this exact string identifies configurations even when two builds
# come from the same git commit.  Target selection is recorded separately:
# one elaboration emits both the direct-NPC and SoC CPU wrappers.
# 性能计数器：组 → 组内单计数器键。键名与Kconfig符号后缀、RTL端口后缀
# 以及C++宏名一一对应；单计数器同时受所属组总开关与PERF_STATS约束。

verilog: $(NPC_CONFIG_MODULE) $(NPC_CONFIG_TYPED_JSON)
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	env NPC_CONFIG_JSON='$(NPC_CONFIG_TYPED_JSON)' \
		mill -i runMain ysyx_26030103_Elaborate --target-dir $(BUILD_DIR)
	ln -sf layers-ysyx_26030103-Verification.sv $(BUILD_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv


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
NVBOARD_AUTOBIND = $(BUILD_DIR)/auto_bind.cpp
NVBOARD_CFLAGS = -I$(NVBOARD_HOME)/usr/include $(shell sdl2-config --cflags 2>/dev/null)
NVBOARD_LDADD = $(NVBOARD_ARCHIVE) $(shell sdl2-config --libs 2>/dev/null) -lSDL2_image -lSDL2_ttf
NVBOARD_LDFLAGS = $(addprefix -LDFLAGS ,$(NVBOARD_LDADD))
NVBOARD_DIRECT_GUARD = nvboard-direct-unsupported

.PHONY: nvboard-direct-unsupported
nvboard-direct-unsupported:
	@echo "错误：NVBoard 只能绑定 ysyxSoCFull 的外设引脚；direct-NPC 顶层没有可绑定的 GPIO/VGA/UART 引脚。请使用 SIM_TOP=soc。" >&2
	@exit 2
endif
# The perf recipe's ordered sub-make reuses the RTL and metadata that its
# successful synth prerequisite just produced.  All ordinary binary targets
# retain the normal PHONY elaboration dependency.
.PHONY: perf-simulator-rebuild
perf-simulator-rebuild:

ifeq ($(PERF_SYNTH_READY),1)
NPC_VERILOG_PREREQ := perf-simulator-rebuild
else
NPC_VERILOG_PREREQ := verilog
endif

# 仅用于性能记录的简短摘要；硬件有效值以 effective JSON 的 SHA256 为准。
PERF_CONFIG_SUMMARY = XLEN=$(or $(CONFIG_XLEN),32);SOC_XLEN=$(or $(CONFIG_SOC_XLEN),32);CHIPLINK=$(or $(CONFIG_CHIPLINK),n);SDRAM_USE_AXI=$(or $(CONFIG_SDRAM_USE_AXI),y);SYNTH_FREQ=$(or $(CONFIG_SYNTH_FREQ),450)

.PHONY: perf-build-metadata
perf-build-metadata: verilog $(NPC_CONFIG_EFFECTIVE_JSON)
	@set -eu; \
	mkdir -p "$(dir $(PERF_BUILD_METADATA))"; \
	tmp="$(PERF_BUILD_METADATA).tmp.$$$$"; \
	summary='$(PERF_CONFIG_SUMMARY)'; \
	config_sha256=$$(sha256sum "$(NPC_CONFIG_EFFECTIVE_JSON)" | cut -d' ' -f1); \
	npc_rtl="$(abspath $(BUILD_DIR)/ysyx_26030103_npc.sv)"; \
	soc_rtl="$(abspath $(BUILD_DIR)/ysyx_26030103.sv)"; \
	test -f "$$npc_rtl"; \
	test -f "$$soc_rtl"; \
	npc_sha256=$$(sha256sum "$$npc_rtl" | cut -d' ' -f1); \
	soc_sha256=$$(sha256sum "$$soc_rtl" | cut -d' ' -f1); \
	commit=$$(git -C "$(NPC_HOME)" rev-parse --short=12 HEAD 2>/dev/null || printf '%s' unknown); \
	message=$$(git -C "$(NPC_HOME)" log -1 --format=%s 2>/dev/null || true); \
	if git_status=$$(git -C "$(NPC_HOME)" status --porcelain --untracked-files=normal 2>/dev/null); then \
		if test -z "$$git_status"; then dirty=0; else dirty=1; fi; \
	else \
		dirty=unknown; \
	fi; \
	{ \
		printf 'format=1\n'; \
		printf 'commit=%s\n' "$$commit"; \
		printf 'git_dirty=%s\n' "$$dirty"; \
		printf 'message=%s\n' "$$message"; \
		printf 'config_sha256=%s\n' "$$config_sha256"; \
		printf 'config_summary=%s\n' "$$summary"; \
		printf 'rtl_npc_sha256=%s\n' "$$npc_sha256"; \
		printf 'rtl_soc_sha256=%s\n' "$$soc_sha256"; \
		printf 'pdk=%s\n' "$(PDK_VALUE)"; \
	} > "$$tmp"; \
	if test -f "$(PERF_BUILD_METADATA)" && cmp -s "$$tmp" "$(PERF_BUILD_METADATA)"; then \
		rm -f "$$tmp"; \
	else \
		mv -f "$$tmp" "$(PERF_BUILD_METADATA)"; \
	fi

ifeq ($(CONFIG_PERF_SAVE),y)
ifneq ($(PERF_SYNTH_READY),1)
simtest $(BIN) $(BIN_NPC): | perf-build-metadata
endif
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
ifneq ($(CONFIG_TRACE_FILE),)
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

simtest: $(NPC_CONFIG_MODULE) $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) $(NVBOARD_AUTOBIND) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline -LDFLAGS -ldl $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) $(NVBOARD_LDFLAGS) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))' \
		NPC_SAVABLE='$(VERILATOR_SAVABLE)' \
		NPC_CONFIG_EFFECTIVE_JSON='$(NPC_CONFIG_EFFECTIVE_JSON)'

# Always enter the SoC sub-build.  Its own config stamp and Scala dependencies
# decide whether regeneration is necessary; checking only for the final file
# here would otherwise reuse stale RTL after a CPU port or CHIPLINK change.
.PHONY: ysyxsoc-verilog-force
ysyxSoC-verilog-force:

$(YSYXSOC_FULL_V): ysyxsoc-verilog-force $(NPC_CONFIG_TYPED_JSON)
	@echo "[perf] (re)building ysyxSoC ..."
	@$(MAKE) -C $(YSYXSOC_DIR) \
		NPC_CONFIG_JSON='$(NPC_CONFIG_TYPED_JSON)' \
		verilog

$(BIN): $(NPC_CONFIG_MODULE) $(NPC_VERILOG_PREREQ) $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND) $(YSYXSOC_FULL_V)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) $(NVBOARD_AUTOBIND) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline -LDFLAGS -ldl $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) $(NVBOARD_LDFLAGS) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))' \
		NPC_SAVABLE='$(VERILATOR_SAVABLE)' \
		NPC_CONFIG_EFFECTIVE_JSON='$(NPC_CONFIG_EFFECTIVE_JSON)'
$(BIN_NPC): $(NVBOARD_DIRECT_GUARD) $(NPC_CONFIG_MODULE) $(NPC_VERILOG_PREREQ) $(CSRCS) $(VERILATOR_BUILD_WRAPPER)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(SAVABLE_VERILATOR_FLAG) $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(NPC_VINCDIRS)) \
		--top-module riscv32e_npc_SimTop \
		$(NPC_VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS) -DVRISCV32E_NPC) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline -LDFLAGS -ldl $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) \
		--Mdir $(NPC_OBJ_DIR) \
		--exe -o $(abspath $(BIN_NPC))
	$(MAKE) -C $(NPC_OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(NPC_VERILATOR_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))' \
		NPC_SAVABLE='$(VERILATOR_SAVABLE)' \
		NPC_CONFIG_EFFECTIVE_JSON='$(NPC_CONFIG_EFFECTIVE_JSON)'

# arch-test / CI 通过递归 make 调用 run 时没有交互终端。SDB 在 EOF 上会
# 立刻退出并报告“未触发陷阱”，程序实际根本没跑；这里在非 tty 下自动加
# --batch。NPC_BATCH=y/n 可强制批处理或强制交互（例如要手工喂 SDB 命令）。
ifeq ($(CONFIG_SDB),y)
NPC_BATCH ?= auto
ifeq ($(NPC_BATCH),auto)
NPC_BATCH_FLAG = $$(if [ -t 0 ]; then :; else printf '%s' --batch; fi)
else ifeq ($(filter y Y yes YES true TRUE 1,$(NPC_BATCH)),)
NPC_BATCH_FLAG =
else
NPC_BATCH_FLAG = --batch
endif
else
NPC_BATCH_FLAG =
endif

ifeq ($(CONFIG_PGO_AUTO),y)
# PGO_AUTO（自动一步到位）：make run 等价于 make pgo file=镜像
# 内部完成 插桩编译→收集profile→优化编译→运行最终程序
ifeq ($(SIM_TOP),soc)
run: pgo
else
run: $(BIN_NPC)
	@echo "警告: PGO_AUTO 目前仅支持 SIM_TOP=soc，本次执行普通运行"
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) $(NPC_BATCH_FLAG) 2>&1
endif
else ifeq ($(SIM_TOP),soc)
run: $(BIN)
	$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) $(NPC_BATCH_FLAG) 2>&1
else
run: $(BIN_NPC)
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) $(NPC_BATCH_FLAG) 2>&1
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

YOSYS_STA_HOME ?= $(abspath $(NPC_HOME)/../yosys-sta)
# Never place synthesis products in yosys-sta's repository-global result/
# directory.  Besides keeping custom BUILD_DIR builds self-contained, the
# identity subdirectory prevents two concurrent builds (or two synthesis
# strategies for the same RTL) from sharing a synth_stat.txt.
SYNTH_OUTPUT_BASE ?= $(abspath $(BUILD_DIR)/yosys-sta)
override SYNTH_FREQ := $(or $(call remove_quote,$(CONFIG_SYNTH_FREQ)),450)
ifneq ($(CONFIG_PDK),)
PDK_VALUE = $(call remove_quote,$(CONFIG_PDK))
else
PDK_VALUE = icsprout55
endif

ifneq ($(CONFIG_SYNTH_STRATEGY_TYPE),)
SYNTH_STRATEGY_TYPE = $(call remove_quote,$(CONFIG_SYNTH_STRATEGY_TYPE))
else
SYNTH_STRATEGY_TYPE = AREA
endif
ifneq ($(CONFIG_SYNTH_STRATEGY_IDX),)
SYNTH_STRATEGY_IDX = $(call remove_quote,$(CONFIG_SYNTH_STRATEGY_IDX))
else
SYNTH_STRATEGY_IDX = 0
endif
ifeq ($(CONFIG_SYNTH_NOMAP),y)
SYNTH_NOMAP = 1
else
SYNTH_NOMAP = 0
endif
ifeq ($(CONFIG_SYNTH_NOABC),y)
SYNTH_NOABC = 1
else
SYNTH_NOABC = 0
endif

ifeq ($(SIM_TOP),soc)
SYNTH_DESIGN = ysyx_26030103
SYNTH_RTL_FILES = $(abspath $(BUILD_DIR)/ysyx_26030103.sv)
else
SYNTH_DESIGN = ysyx_26030103
SYNTH_RTL_FILES = $(abspath $(BUILD_DIR)/ysyx_26030103_npc.sv)
endif

# The identity directory is deliberately reusable.  Publish an invalid
# manifest and delete the exact old area report before each run, so a failed
# synthesis or a missing new report cannot inherit a valid old measurement.
synth: verilog perf-build-metadata
	@echo "[perf] 综合 ($(SYNTH_DESIGN), $(SYNTH_FREQ)MHz, $(PDK_VALUE), strategy=$(SYNTH_STRATEGY_TYPE) $(SYNTH_STRATEGY_IDX), nomap=$(SYNTH_NOMAP), noabc=$(SYNTH_NOABC)) ..."

	@set -eu; \
	config_sha256=$$(sed -n 's/^config_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	if test "$(SIM_TOP)" = soc; then \
		target=soc; \
		rtl_sha256=$$(sed -n 's/^rtl_soc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	else \
		target=npc; \
		rtl_sha256=$$(sed -n 's/^rtl_npc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	fi; \
	test -n "$$config_sha256"; \
	test -n "$$rtl_sha256"; \
	synth_output="$(SYNTH_OUTPUT_BASE)/$$target-$$config_sha256-$$rtl_sha256"; \
	SYNTH_STAT="$$synth_output/$(SYNTH_DESIGN)-$(SYNTH_FREQ)MHz-$(PDK_VALUE)/synth_stat.txt"; \
	mkdir -p "$(dir $(PERF_SYNTH))"; \
	tmp="$(PERF_SYNTH).tmp.$$$$"; \
	{ \
		printf 'format=2\n'; \
		printf 'target=%s\n' "$$target"; \
		printf 'pdk=%s\n' "$(PDK_VALUE)"; \
		printf 'config_sha256=%s\n' "$$config_sha256"; \
		printf 'rtl_sha256=%s\n' "$$rtl_sha256"; \
		printf 'measurement_valid=0\n'; \
		printf 'frequency_mhz=%s\n' "$(SYNTH_FREQ)"; \
		printf 'area_um2=0\n'; \
	} > "$$tmp"; \
	mv -f "$$tmp" "$(PERF_SYNTH)"; \
	rm -f "$$SYNTH_STAT"; \
	export SYNTH_STRATEGY_TYPE=$(SYNTH_STRATEGY_TYPE); \
	export SYNTH_STRATEGY_IDX=$(SYNTH_STRATEGY_IDX); \
	export SYNTH_NOMAP=$(SYNTH_NOMAP); \
	export SYNTH_NOABC=$(SYNTH_NOABC); \
	$(MAKE) -C $(YOSYS_STA_HOME) syn \
		PDK=$(PDK_VALUE) \
		DESIGN=$(SYNTH_DESIGN) \
		RTL_FILES="$(SYNTH_RTL_FILES)" \
		CLK_FREQ_MHZ=$(SYNTH_FREQ) \
		O="$$synth_output"
	@set -eu; \
	config_sha256=$$(sed -n 's/^config_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	if test "$(SIM_TOP)" = soc; then \
		target=soc; \
		rtl_sha256=$$(sed -n 's/^rtl_soc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	else \
		target=npc; \
		rtl_sha256=$$(sed -n 's/^rtl_npc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	fi; \
	test -n "$$config_sha256"; \
	test -n "$$rtl_sha256"; \
	synth_output="$(SYNTH_OUTPUT_BASE)/$$target-$$config_sha256-$$rtl_sha256"; \
	SYNTH_STAT="$$synth_output/$(SYNTH_DESIGN)-$(SYNTH_FREQ)MHz-$(PDK_VALUE)/synth_stat.txt"; \
	area=0; \
	measurement_valid=0; \
	if test -f "$$SYNTH_STAT"; then \
		candidate=$$(awk '/Chip area/ {print $$6; exit}' "$$SYNTH_STAT"); \
		if test -n "$$candidate"; then \
			area="$$candidate"; \
			measurement_valid=1; \
		fi; \
	fi; \
	mkdir -p "$(dir $(PERF_SYNTH))"; \
	tmp="$(PERF_SYNTH).tmp.$$$$"; \
	{ \
		printf 'format=2\n'; \
		printf 'target=%s\n' "$$target"; \
		printf 'pdk=%s\n' "$(PDK_VALUE)"; \
		printf 'config_sha256=%s\n' "$$config_sha256"; \
		printf 'rtl_sha256=%s\n' "$$rtl_sha256"; \
		printf 'measurement_valid=%s\n' "$$measurement_valid"; \
		printf 'frequency_mhz=%s\n' "$(SYNTH_FREQ)"; \
		printf 'area_um2=%s\n' "$$area"; \
	} > "$$tmp"; \
	mv -f "$$tmp" "$(PERF_SYNTH)"

YOSYS_STA_RESULT = $(NPC_HOME)/yosys-sta-result

yosys-sta: verilog perf-build-metadata
	@echo "[yosys-sta] 全流程 syn+sta ($(SYNTH_DESIGN), $(SYNTH_FREQ)MHz, $(PDK_VALUE)) ..."
	@set -eu; \
	config_sha256=$$(sed -n 's/^config_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	if test "$(SIM_TOP)" = soc; then \
		target=soc; \
		rtl_sha256=$$(sed -n 's/^rtl_soc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	else \
		target=npc; \
		rtl_sha256=$$(sed -n 's/^rtl_npc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	fi; \
	test -n "$$config_sha256"; \
	test -n "$$rtl_sha256"; \
	synth_output="$(SYNTH_OUTPUT_BASE)/$$target-$$config_sha256-$$rtl_sha256"; \
	export SYNTH_STRATEGY_TYPE=$(SYNTH_STRATEGY_TYPE); \
	export SYNTH_STRATEGY_IDX=$(SYNTH_STRATEGY_IDX); \
	export SYNTH_NOMAP=$(SYNTH_NOMAP); \
	export SYNTH_NOABC=$(SYNTH_NOABC); \
	$(MAKE) -C $(YOSYS_STA_HOME) syn sta \
		PDK=$(PDK_VALUE) \
		DESIGN=$(SYNTH_DESIGN) \
		RTL_FILES="$(SYNTH_RTL_FILES)" \
		CLK_FREQ_MHZ=$(SYNTH_FREQ) \
		O="$$synth_output"
	@config_sha256=$$(sed -n 's/^config_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	if test "$(SIM_TOP)" = soc; then \
		target=soc; \
		rtl_sha256=$$(sed -n 's/^rtl_soc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	else \
		target=npc; \
		rtl_sha256=$$(sed -n 's/^rtl_npc_sha256=//p' "$(PERF_BUILD_METADATA)"); \
	fi; \
	SRC_DIR="$(SYNTH_OUTPUT_BASE)/$$target-$$config_sha256-$$rtl_sha256/$(SYNTH_DESIGN)-$(SYNTH_FREQ)MHz-$(PDK_VALUE)"; \
	TS_DIR="$(YOSYS_STA_RESULT)/$$(date +%Y%m%d-%H%M%S)"; \
	set -o pipefail; \
	mkdir -p "$$TS_DIR" && \
	tar -C "$$SRC_DIR" --exclude='yosys.log' --exclude='sta.log' --exclude='*.v' --exclude='*.v.sim' -cf - . | tar -C "$$TS_DIR" -xf - && \
	find "$$SRC_DIR" -type f \( -name '*.v' -o -name '*.v.sim' \) -delete && \
	echo "[yosys-sta] 结果已保存到 $$TS_DIR"

# `perf` must finish synthesis before compiling the simulator: otherwise a
# parallel make can compile against regenerated RTL while synthesis is still
# consuming it.  Use a recipe sub-make rather than a GNU make 4.4-only ordered
# prerequisite feature, so GNU make 4.3 remains supported.
# PERF_SYNTH_READY suppresses only duplicate elaboration/metadata;
# perf-simulator-rebuild still forces Verilator to rebuild the executable from
# the RTL that synthesis just consumed.
ifeq ($(SIM_TOP),soc)
perf: synth
	@$(MAKE) --no-print-directory PERF_SYNTH_READY=1 $(BIN)
	@mkdir -p $(PERF_DIR)
	@echo "[perf] 运行仿真 ..."
	@set -o pipefail; \
	base="$(PERF_DIR)/$$(date +%Y%m%d-%H%M%S)"; \
	suffix=0; \
	while :; do \
		if test "$$suffix" -eq 0; then RESULT_DIR="$$base"; else RESULT_DIR="$$base-$$suffix"; fi; \
		if mkdir "$$RESULT_DIR" 2>/dev/null; then break; fi; \
		if test ! -e "$$RESULT_DIR"; then echo "无法创建性能结果目录 $$RESULT_DIR" >&2; exit 1; fi; \
		suffix=$$((suffix + 1)); \
	done; \
	$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) --result-dir "$$RESULT_DIR" 2>&1 | tee "$$RESULT_DIR/ysyxsoc.log"
else
perf: synth
	@$(MAKE) --no-print-directory PERF_SYNTH_READY=1 $(BIN_NPC)
	@mkdir -p $(PERF_DIR)
	@echo "[perf] 运行仿真 ..."
	@set -o pipefail; \
	base="$(PERF_DIR)/$$(date +%Y%m%d-%H%M%S)"; \
	suffix=0; \
	while :; do \
		if test "$$suffix" -eq 0; then RESULT_DIR="$$base"; else RESULT_DIR="$$base-$$suffix"; fi; \
		if mkdir "$$RESULT_DIR" 2>/dev/null; then break; fi; \
		if test ! -e "$$RESULT_DIR"; then echo "无法创建性能结果目录 $$RESULT_DIR" >&2; exit 1; fi; \
		suffix=$$((suffix + 1)); \
	done; \
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) --result-dir "$$RESULT_DIR" 2>&1 | tee "$$RESULT_DIR/npc.log"
endif

include scripts/config.mk

-include ../Makefile
