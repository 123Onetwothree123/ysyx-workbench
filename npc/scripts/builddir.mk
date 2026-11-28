# ============================================================================
# 按 effective config hash + build signature 隔离的构建目录
# ============================================================================
# 第一层 CONFIG_HASH 由 ParamGen 对 NPCConfigGEN 的 typed JSON 做 canonical
# 投影得到，只覆盖硬件有效参数。第二层 BUILD_SIGNATURE 覆盖编译器、编译
# 选项、sanitizer、SDB/trace/difftest/perf 开关以及工具版本，避免切换这些
# 选项后复用不兼容的对象。
CONFIG_HASH_TOOL := $(abspath tools/ParamGen/param_tool.py)
CONFIG_HASH_SCHEMA := $(abspath params/schema/schema.json)
CONFIG_HASH_MK := $(abspath $(BUILD_DIR)/generated/config_hash.mk)

# make 在读取 Makefile 时就要展开所有目标路径，直接在这里 $(shell ...)
# 现算只能拿到“上一次生成”的哈希：切换 .config 后的第一次 make 会把新
# 配置的 RTL/对象写进旧哈希目录。这里把 config_hash.mk 作为可重建的
# include 文件：配置或 staging typed JSON 更新后，make 先重建它再自动重启，
# 用新哈希重新展开全部路径，因此配置切换不会复用旧目录。
#
# 清理/menuconfig/defconfig 这类目标不依赖构建目录，保持原来的快速路径：
# 不触发生成，避免 `make clean` 反过来要求配置工具可用。
NPC_HASH_SKIP_GOALS := clean distclean menuconfig menuconfig-clean savedefconfig \
	profile-defconfig
NPC_HASH_REAL_GOALS := $(filter-out $(NPC_HASH_SKIP_GOALS) %defconfig %_profile,$(MAKECMDGOALS))

ifeq ($(strip $(MAKECMDGOALS)),)
NPC_NEEDS_HASH := 1
else ifneq ($(strip $(NPC_HASH_REAL_GOALS)),)
NPC_NEEDS_HASH := 1
else
NPC_NEEDS_HASH :=
endif

ifeq ($(wildcard .config),)
# 没有 .config 时无法生成 typed JSON。menuconfig/defconfig 必须仍能启动；
# 真正要构建的目标由 config-hash-require 明确报错。
NPC_NEEDS_HASH :=
endif

ifeq ($(NPC_NEEDS_HASH),1)
-include $(CONFIG_HASH_MK)
else ifneq ($(wildcard $(CONFIG_HASH_SCHEMA)),)
# 清理/配置目标不先生成配置，只从已有 staging typed JSON 取哈希；schema
# 缺失时 ParamGen 会静默返回另一套 canonical 结果，因此这里宁可不取。
CONFIG_HASH := $(shell python3 $(CONFIG_HASH_TOOL) hash --typed '$(NPC_CONFIG_TYPED_STAGE)' 2>/dev/null)
endif

# build signature 的 included makefile 也需要在路径展开前生成。和 config hash
# 一样，它只对真正使用构建目录的目标启用；clean 需要知道当前签名目录，
# menuconfig/defconfig/distclean 不需要。
NPC_SIGNATURE_SKIP_GOALS := distclean menuconfig menuconfig-clean savedefconfig \
	profile-defconfig
NPC_SIGNATURE_REAL_GOALS := $(filter-out $(NPC_SIGNATURE_SKIP_GOALS) %defconfig %_profile,$(MAKECMDGOALS))

ifeq ($(strip $(MAKECMDGOALS)),)
NPC_NEEDS_SIGNATURE := 1
else ifneq ($(strip $(NPC_SIGNATURE_REAL_GOALS)),)
NPC_NEEDS_SIGNATURE := 1
else
NPC_NEEDS_SIGNATURE :=
endif

ifeq ($(wildcard .config),)
NPC_NEEDS_SIGNATURE :=
endif

include scripts/buildsignature.mk

ifeq ($(NPC_NEEDS_SIGNATURE),1)
# 没有 CONFIG_HASH 时先让 config_hash.mk 生成/重启，再在下一轮读取签名；
# clean 在 typed JSON 缺失时也能继续走“只删旧 symlink”的快速路径。
ifneq ($(strip $(CONFIG_HASH)),)
-include $(BUILD_SIGNATURE_MK)
endif
else ifneq ($(wildcard $(BUILD_SIGNATURE_MK)),)
include $(BUILD_SIGNATURE_MK)
endif

# 配置投影与构建产物分成两层：
#   build/<CONFIG_HASH>/config/          同一硬件配置的输入投影（跨签名共享）
#   build/<CONFIG_HASH>/<BUILD_SIGNATURE>/ 实际对象/RTL/可执行文件
# 任何一层未知时使用哨兵目录名，绝不回退到固定的 build/ 根目录。
CONFIG_PROJECTION_DIR := $(abspath $(BUILD_DIR))/$(or $(CONFIG_HASH),unconfigured)/config
CONFIG_BUILD_DIR := $(BUILD_DIR)/$(or $(CONFIG_HASH),unconfigured)/$(or $(BUILD_SIGNATURE),unconfigured)

# 以下路径全部消费 CONFIG_BUILD_DIR：Verilator/GSIM 的对象、RTL、可执行
# 文件按配置+签名隔离；同一个目录可在多次 make 之间复用。
OBJ_DIR = $(CONFIG_BUILD_DIR)/obj_dir_$(TOPNAME)
BIN = $(CONFIG_BUILD_DIR)/$(TOPNAME)
BIN_NPC = $(CONFIG_BUILD_DIR)/$(TOPNAME)_npc
NPC_OBJ_DIR = $(CONFIG_BUILD_DIR)/obj_dir_$(TOPNAME)_npc

VSRCS = $(shell find $(CONFIG_BUILD_DIR) -maxdepth 1 -type f -name "*.sv" ! -name "riscv32e_npc*" ! -name "ysyx_*_npc*" ! -name "*_yosys*" ! -name "*_merged*" 2>/dev/null | sort) \
        $(YSYXSOC_FULL_V) \
        $(shell find $(CONFIG_BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort) \
        $(shell find $(YSYXSOC_PERIP_DIR) -type f -name "*.v" 2>/dev/null | sort)
VINCDIRS = $(CONFIG_BUILD_DIR) \
           $(shell find $(CONFIG_BUILD_DIR)/verification -type d 2>/dev/null | sort) \
           $(YSYXSOC_PERIP_INCDIRS)

# Chisel 生成的 SimTop 已包含 CPU/AXIRAM 的完整层次；再单独加入
# ysyx_26030103_npc.sv 或 riscv32e_npc_AXIRAM.sv 会让 Verilator 看到重复模块。
NPC_VSRCS = $(CONFIG_BUILD_DIR)/riscv32e_npc_SimTop.sv \
            $(shell find $(CONFIG_BUILD_DIR)/verification -type f -name "*.sv" 2>/dev/null | sort)
NPC_VINCDIRS = $(CONFIG_BUILD_DIR) \
               $(shell find $(CONFIG_BUILD_DIR)/verification -type d 2>/dev/null | sort)

# 旧路径兼容：build/ysyx_26030103_npc 等仍可 make，成功后更新为指向当前
# 配置+签名目录的 symlink。symlink 由真实构建产物作为普通依赖驱动，构建
# 失败时不会被更新，因此不会指向半成品。
LEGACY_BIN := $(BUILD_DIR)/$(TOPNAME)
LEGACY_BIN_NPC := $(BUILD_DIR)/$(TOPNAME)_npc
LEGACY_GSIM_BIN := $(BUILD_DIR)/$(TOPNAME)_gsim

.PHONY: build-links-force
build-links-force:

# 只更新 symlink；真实二进制必须已经构建成功（普通依赖）。
$(LEGACY_BIN_NPC): $(BIN_NPC) build-links-force
	@set -eu; \
	test -x "$(BIN_NPC)" || { echo "错误：构建产物不存在：$(BIN_NPC)" >&2; exit 2; }; \
	ln -sfn "$(CONFIG_HASH)/$(BUILD_SIGNATURE)/$(notdir $(BIN_NPC))" "$@"

$(LEGACY_BIN): $(BIN) build-links-force
	@set -eu; \
	test -x "$(BIN)" || { echo "错误：构建产物不存在：$(BIN)" >&2; exit 2; }; \
	ln -sfn "$(CONFIG_HASH)/$(BUILD_SIGNATURE)/$(notdir $(BIN))" "$@"

# schema 是哈希 canonical 投影的一部分，必须存在；ParamGen 在缺省 schema
# 时不会报错，因此这里显式拦截，避免静默算出另一套哈希。
CONFIG_HASH_SCHEMA_DEPS := $(CONFIG_HASH_SCHEMA)

$(CONFIG_HASH_SCHEMA):
	@echo "错误：缺少 ParamGen schema：$(CONFIG_HASH_SCHEMA)" >&2
	@echo "      effective config hash 必须基于 schema 的 canonical 投影，缺少时不回退。" >&2
	@exit 2

# copy_if_changed：只在内容变化时替换，避免无谓地刷新 mtime。
npc_copy_if_changed = \
	if test -f "$(2)" && cmp -s "$(1)" "$(2)"; then :; else cp -f "$(1)" "$(2)"; fi

# makefile 重建阶段先生成配置，再算哈希并落盘配置投影；失败时明确报错，
# 不静默使用固定目录。内容不变时不更新时间戳，避免 make 无谓重启。
$(CONFIG_HASH_MK): $(NPC_CONFIG_TYPED_STAGE) $(NPC_CONFIG_EFFECTIVE_STAGE) \
	$(NPC_CONFIG_MODULE_STAGE) $(NPC_CONFIG_SCALA_DEFAULTS) \
	$(CONFIG_HASH_TOOL) $(CONFIG_HASH_SCHEMA_DEPS) .config
	@set -eu; \
	test -f "$(CONFIG_HASH_SCHEMA)" || { \
		echo "错误：缺少 ParamGen schema：$(CONFIG_HASH_SCHEMA)" >&2; \
		exit 2; \
	}; \
	mkdir -p "$(@D)"; \
	tmp="$(abspath $(BUILD_DIR))/generated/.npc-effective.$$$$.json"; \
	trap 'rm -f "$$tmp"' EXIT HUP INT TERM; \
	hash="$$(python3 $(CONFIG_HASH_TOOL) hash --typed '$(NPC_CONFIG_TYPED_STAGE)' --out "$$tmp")" || { \
		echo "错误：无法从 $(NPC_CONFIG_TYPED_STAGE) 计算 effective config hash。" >&2; \
		echo "      请先运行 make npc-config（或 make menuconfig / make <defconfig>）生成配置。" >&2; \
		exit 2; \
	}; \
	case "$$hash" in \
		*[!0-9a-f]*) \
			echo "错误：effective config hash 不是十六进制字符串：$$hash" >&2; \
			exit 2 ;; \
	esac; \
	dir="$(abspath $(BUILD_DIR))/$$hash"; \
	config_dir="$$dir/config"; \
	mkdir -p "$$dir" "$$config_dir"; \
	mv -f "$$tmp" "$$dir/effective.json"; \
	printf '%s\n' "$$hash" > "$$dir/effective.hash"; \
	$(call npc_copy_if_changed,.config,$$config_dir/.config); \
	$(call npc_copy_if_changed,$(NPC_CONFIG_JSON),$$config_dir/npc_config.json); \
	$(call npc_copy_if_changed,include/generated/autoconf.h,$$config_dir/autoconf.h); \
	$(call npc_copy_if_changed,include/config/auto.conf,$$config_dir/auto.conf); \
	$(call npc_copy_if_changed,$(NPC_CONFIG_EFFECTIVE_STAGE),$$config_dir/npc_config.effective.json); \
	$(call npc_copy_if_changed,$(NPC_CONFIG_TYPED_STAGE),$$config_dir/npc_config.typed.json); \
	$(call npc_copy_if_changed,$(NPC_CONFIG_MODULE_STAGE),$$config_dir/npc_config.ixx); \
	$(call npc_copy_if_changed,$(NPC_CONFIG_SCALA_DEFAULTS),$$config_dir/ysyx_26030103_ConfigDefaults.scala); \
	mkdir -p "$(CSRC_DIR)/generated"; \
	$(call npc_copy_if_changed,$$config_dir/npc_config.ixx,$(CSRC_DIR)/generated/npc_config.ixx); \
	{ \
		printf 'format=1\n'; \
		printf 'config_hash=%s\n' "$$hash"; \
		printf 'config_source=.config\n'; \
		printf 'flat_source=include/generated/npc_config.json\n'; \
		printf 'typed_source=$(NPC_CONFIG_TYPED_STAGE)\n'; \
		printf 'effective_source=$(NPC_CONFIG_EFFECTIVE_STAGE)\n'; \
		printf 'autoconf_source=include/generated/autoconf.h\n'; \
		printf 'auto_conf_source=include/config/auto.conf\n'; \
		printf 'auto_conf_note=Kconfig output from .config; command-line CONFIG_* overrides appear only in effective/typed JSON\n'; \
		printf 'cxx_module_source=$(NPC_CONFIG_MODULE_STAGE)\n'; \
		printf 'cxx_module_compat=csrc/generated/npc_config.ixx (compatibility copy, not used by main build)\n'; \
		printf 'scala_source=$(NPC_CONFIG_SCALA_DEFAULTS)\n'; \
		printf 'scala_note=mill source root is npc/src; global file remains authoritative\n'; \
		printf 'global_staging=build/generated (hash input; still shared by processes)\n'; \
		printf 'global_limits=mill out/, tools/build/, include/config/auto.conf remain global; SoC build output is ysyxSoC/build\n'; \
		printf 'param_schema_source=$(CONFIG_HASH_SCHEMA)\n'; \
		printf 'param_schema_sha256='; sha256sum "$(CONFIG_HASH_SCHEMA)" | cut -d' ' -f1; \
	} > "$$config_dir/projection.manifest"; \
	{ \
		printf '# generated from %s; do not edit\n' '$(NPC_CONFIG_TYPED_STAGE)'; \
		printf 'CONFIG_HASH := %s\n' "$$hash"; \
		printf 'CONFIG_SOURCE_SHA256 := %s\n' "$$(sha256sum .config | cut -d' ' -f1)"; \
	} > $@.tmp; \
	if test -f "$@" && cmp -s $@.tmp $@; then \
		rm -f $@.tmp; \
	else \
		mv -f $@.tmp $@; \
	fi

# 没有 .config/typed JSON 时，任何会生成构建物的目标都必须先在这里报错，
# 不能把 build/unconfigured 当成正常目录继续写。
.PHONY: config-hash-require
config-hash-require: config-buildsignature-require
	@if [ -z "$(CONFIG_HASH)" ]; then \
		echo "错误：无法确定 effective config hash。" >&2; \
		echo "      缺少 .config 或 $(NPC_CONFIG_TYPED_STAGE)，或者 ParamGen 哈希失败。" >&2; \
		echo "      请先运行 make menuconfig（或 make <defconfig> / make npc-config）。" >&2; \
		exit 2; \
	fi
