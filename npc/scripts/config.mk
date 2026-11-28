COLOR_RED := $(shell echo "\033[1;31m")
COLOR_END := $(shell echo "\033[0m")

ifeq ($(wildcard .config),)
$(warning $(COLOR_RED)Warning: .config does not exist!$(COLOR_END))
$(warning $(COLOR_RED)To build the project, first run 'make menuconfig'.$(COLOR_END))
endif

Q            := @
KCONFIG_PATH := $(NPC_HOME)/tools/kconfig
Kconfig      := $(NPC_HOME)/Kconfig
# schema.json is the parameter authority manifest; parts/ holds one file per
# top-level menu.  npc/Kconfig is only the stable entry point; the actual
# symbol/menu tree is generated before every config tool invocation.  The
# stamp makes manifest/part/generator changes rebuild the saved config
# projection without touching the generated files on no-op runs.
KCONFIG_SCHEMA := $(NPC_HOME)/params/schema/schema.json
KCONFIG_SCHEMA_DIR := $(dir $(KCONFIG_SCHEMA))
KCONFIG_SCHEMA_PARTS_DIR := $(KCONFIG_SCHEMA_DIR)parts
KCONFIG_SCHEMA_PARTS := $(wildcard $(KCONFIG_SCHEMA_PARTS_DIR)/*.json)
KCONFIG_GENERATOR := $(NPC_HOME)/tools/ParamGen/gen_kconfig.py
KCONFIG_GENERATED_DIR := $(NPC_HOME)/params/generated
KCONFIG_GENERATED := $(KCONFIG_GENERATED_DIR)/Kconfig
KCONFIG_GEN_STAMP := $(KCONFIG_GENERATED_DIR)/.generated.stamp
KCONFIG_GEN_DEPS := $(KCONFIG_SCHEMA) $(KCONFIG_SCHEMA_PARTS) \
	$(if $(wildcard $(KCONFIG_SCHEMA_PARTS_DIR)),$(KCONFIG_SCHEMA_PARTS_DIR),) \
	$(if $(wildcard $(KCONFIG_SCHEMA_DIR)),$(KCONFIG_SCHEMA_DIR),) \
	$(KCONFIG_GENERATOR) $(NPC_HOME)/tools/ParamGen/paramlib.py
KCONFIG_GEN_CMD = python3 "$(KCONFIG_GENERATOR)" \
	--schema "$(KCONFIG_SCHEMA)" --out "$(KCONFIG_GENERATED)"
CONFIG_CONTRACT := $(NPC_HOME)/tools/build/kconfig_contract
rm-distclean += include/generated include/config csrc/generated \
	tools/build/npc_config_gen tools/build/kconfig_contract \
	tools/build/linux_boot_test tools/build/pack_uboot_image \
	tools/build/audit_rootfs_elf tools/build/build_dtb \
	tools/build/pack_linux_image tools/build/NPCConfigGEN tools/build/BuildTools tools/build/KconfigContract tools/build/LinuxBootTest \
	tools/build/c_conf tools/build/c_mconf tools/kconfig/build tools/kconfig/gcm.cache \
	.config .config.old tools/build/merge_config
silent := -s

CONF          := $(KCONFIG_PATH)/../build/c_conf
MCONF         := $(KCONFIG_PATH)/../build/c_mconf
KCONFIG_BUILD := $(KCONFIG_PATH)/build
# Kconfig 工具自己的编译器/标准库，由 Kconfig 的“Kconfig 工具”菜单选择；
# 与 NPC 仿真器的 CONFIG_CC/CONFIG_STDLIB 相互独立。没有 .config 时回退到
# GCC + libstdc++，保证第一次 make menuconfig 可以启动。
KCONFIG_CXX    ?= $(or $(call remove_quote,$(CONFIG_KCONFIG_CC)),g++)
KCONFIG_STDLIB ?= $(or $(call remove_quote,$(CONFIG_KCONFIG_STDLIB)),libstdc++)
KCONFIG_BUILD_TYPE ?= $(or $(call remove_quote,$(CONFIG_KCONFIG_BUILD_TYPE)),Release)
KCONFIG_CXXFLAGS ?= $(call remove_quote,$(CONFIG_KCONFIG_CXXFLAGS))
KCONFIG_LDFLAGS  ?= $(call remove_quote,$(CONFIG_KCONFIG_LDFLAGS))
KCONFIG_TOOL_STAMP := $(KCONFIG_BUILD)/.tool-compiler
PROFILE       ?=
MERGE_CONFIG := $(NPC_HOME)/tools/build/merge_config

$(MERGE_CONFIG): $(NPC_BUILD_TOOLS_STAMP)
	$(Q)mkdir -p $(@D)
	$(Q)cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/merge_config" "$@"

merge-config-tool: $(MERGE_CONFIG)
.PHONY: merge-config-tool

KCONFIG_SOURCES := $(wildcard $(KCONFIG_PATH)/*.cpp $(KCONFIG_PATH)/*.ixx $(KCONFIG_PATH)/*.h $(KCONFIG_PATH)/*.hpp $(KCONFIG_PATH)/*.c $(KCONFIG_PATH)/*.y $(KCONFIG_PATH)/*.l $(KCONFIG_PATH)/lxdialog/*.cpp $(KCONFIG_PATH)/lxdialog/*.hpp)

$(MCONF): $(KCONFIG_BUILD)/CMakeCache.txt $(KCONFIG_SOURCES)
	$(Q)cmake --build $(KCONFIG_BUILD) -j $$(nproc)

$(CONF): $(MCONF)

# Generate the Kconfig tree from schema.json.  Failure is fatal: never fall
# back to a stale tree or silently build with missing symbols.
$(KCONFIG_GEN_STAMP): $(KCONFIG_GEN_DEPS)
	$(Q)test -f "$(KCONFIG_SCHEMA)" || { \
		echo "错误：缺少参数 schema：$(KCONFIG_SCHEMA)" >&2; \
		exit 2; \
	}
	$(Q)echo "[kconfig-gen] schema -> $(KCONFIG_GENERATED)"
	$(Q)$(KCONFIG_GEN_CMD) || { \
		echo "错误：从 schema 生成 Kconfig 失败：$(KCONFIG_SCHEMA)" >&2; \
		exit 2; \
	}
	$(Q)touch $@

# The generated entry is a normal dependency so a schema change re-runs
# syncconfig.  If only this file is missing, repair it instead of silently
# continuing with a stale .config projection.
$(KCONFIG_GENERATED): $(KCONFIG_GEN_STAMP)
	$(Q)test -f "$@" || { \
		echo "[kconfig-gen] 生成入口缺失，重新生成..." >&2; \
		rm -f "$(KCONFIG_GEN_STAMP)"; \
		$(KCONFIG_GEN_CMD) || { \
			echo "错误：从 schema 生成 Kconfig 失败：$(KCONFIG_SCHEMA)" >&2; \
			exit 2; \
		}; \
		touch "$(KCONFIG_GEN_STAMP)"; \
	}

# Order-only guard for recipes that invoke c_conf/mconf directly.  It also
# repairs a checkout where the stamp survived but a generated part is missing.
.PHONY: kconfig-generated-check
kconfig-generated-check: $(KCONFIG_GEN_STAMP)
	$(Q)set -eu; \
	need=0; \
	test -f "$(KCONFIG_GENERATED)" || need=1; \
	for f in "$(KCONFIG_GENERATED_DIR)"/kconfig/part-*.Kconfig; do \
		test -f "$$f" || need=1; \
	done; \
	if [ "$$need" -ne 0 ]; then \
		echo "[kconfig-gen] 生成文件缺失，重新生成..." >&2; \
		rm -f "$(KCONFIG_GEN_STAMP)"; \
		$(KCONFIG_GEN_CMD) || { \
			echo "错误：从 schema 生成 Kconfig 失败：$(KCONFIG_SCHEMA)" >&2; \
			exit 2; \
		}; \
		touch "$(KCONFIG_GEN_STAMP)"; \
	elif ! python3 "$(KCONFIG_GENERATOR)" --check \
		--schema "$(KCONFIG_SCHEMA)" --out "$(KCONFIG_GENERATED)"; then \
		echo "[kconfig-gen] 生成文件与 schema 不一致，重新生成..." >&2; \
		rm -f "$(KCONFIG_GEN_STAMP)"; \
		$(KCONFIG_GEN_CMD) || { \
			echo "错误：从 schema 生成 Kconfig 失败：$(KCONFIG_SCHEMA)" >&2; \
			exit 2; \
		}; \
		touch "$(KCONFIG_GEN_STAMP)"; \
	fi

# 编译器/标准库选择变化时删除 CMakeCache，强制用新参数重新 configure。
$(KCONFIG_TOOL_STAMP): $(if $(wildcard .config),.config,)
	$(Q)mkdir -p $(KCONFIG_BUILD)
	$(Q)printf '%s\n' \
		"$(KCONFIG_CXX)" \
		"$(KCONFIG_STDLIB)" \
		"$(KCONFIG_BUILD_TYPE)" \
		"$(KCONFIG_CXXFLAGS)" \
		"$(KCONFIG_LDFLAGS)" > $@.tmp
	$(Q)if test -f $@ && cmp -s $@.tmp $@; then \
		rm -f $@.tmp; \
	else \
		rm -f $(KCONFIG_BUILD)/CMakeCache.txt; \
		mv -f $@.tmp $@; \
	fi

$(KCONFIG_BUILD)/CMakeCache.txt: $(KCONFIG_TOOL_STAMP)
	$(Q)mkdir -p $(KCONFIG_BUILD)
	$(Q)cd $(KCONFIG_BUILD) && cmake -G Ninja \
		-DCMAKE_CXX_COMPILER="$(KCONFIG_CXX)" \
		-DKCONFIG_STDLIB="$(KCONFIG_STDLIB)" \
		-DCMAKE_BUILD_TYPE="$(KCONFIG_BUILD_TYPE)" \
		-DCMAKE_CXX_FLAGS="$(KCONFIG_CXXFLAGS)" \
		-DCMAKE_EXE_LINKER_FLAGS="$(KCONFIG_LDFLAGS)" ..

menuconfig: $(MCONF) $(CONF) $(CONFIG_CONTRACT) | kconfig-generated-check
	$(Q)TERM=xterm LC_ALL=C.utf8 $(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

# 只清理 menuconfig 的构建产物（CMake 目录、c_conf/c_mconf、模块缓存），
# 保留 .config 和 include/generated，避免误删用户当前配置。
.PHONY: menuconfig-clean
menuconfig-clean:
	@echo "清理 menuconfig 构建工具..."
	-@rm -rf $(KCONFIG_BUILD) $(CONF) $(MCONF) $(KCONFIG_PATH)/gcm.cache

savedefconfig: $(CONF) | kconfig-generated-check
	$(Q)$< $(silent) --$@=configs/defconfig $(Kconfig)

%defconfig: $(CONF) $(CONFIG_CONTRACT) | kconfig-generated-check
	$(Q)$(CONFIG_CONTRACT) --config configs/$@
	$(Q)$< $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)$< $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

profile-defconfig: $(CONF) $(CONFIG_CONTRACT) $(MERGE_CONFIG) | kconfig-generated-check
	$(Q)if [ -z "$(strip $(PROFILE))" ]; then \
		echo "错误：PROFILE 为空。" >&2; \
		echo "用法：make profile-defconfig PROFILE=rv64_linux" >&2; \
		exit 2; \
	fi
	$(Q)$(MERGE_CONFIG) "$(PROFILE)"

%_profile: $(CONF) $(CONFIG_CONTRACT) $(MERGE_CONFIG)
	$(Q)$(MERGE_CONFIG) "$*"

.PHONY: menuconfig savedefconfig defconfig profile-defconfig

distclean: clean
	-@rm -rf $(rm-distclean) "$(BUILD_DIR)"

.PHONY: distclean

# ============================================================================
# 配置生成、Kconfig 契约与生成物依赖
# ============================================================================
# Kconfig 是唯一配置解析器：c_conf --syncconfig 直接生成
# include/generated/npc_config.json。C++ 构建期小工具再把 JSON 投影成
# 编译期模块 npc.config；运行时不解析 JSON，因为 XLen/数组大小/if
# constexpr 都必须是编译期常量。
$(NPC_CONFIG_JSON): .config $(CONF) $(NPC_CONFIG_STAMP) $(KCONFIG_GEN_STAMP) | kconfig-generated-check
	$(CONF) -s --syncconfig $(Kconfig)

.PHONY: npc-config-stamp-force
npc-config-stamp-force:
$(NPC_CONFIG_STAMP): npc-config-stamp-force
	@mkdir -p $(@D)
	@printf '%s\n' \
		"savable=$(VERILATOR_SAVABLE)" \
		"perf_build=$(PERF_BUILD_METADATA)" \
		"perf_synth=$(PERF_SYNTH)" \
		"overrides=$(NPC_CONFIG_OVERRIDE_ARGS)" \
		"config_sha256=$(shell sha256sum .config 2>/dev/null | cut -d' ' -f1)" > $@.tmp
	@if test -f $@ && cmp -s $@.tmp $@; then rm -f $@.tmp; else mv -f $@.tmp $@; fi

# 四个 staging 产物必须一起生成：模块给 C++，flat JSON 给脚本/C++ 构建，
# typed JSON 给 Scala elaboration，Scala 默认值给未加载配置的测试/旧调用。
# staging 是 config hash 的稳定输入；之后的投影规则按 hash 复制到
# build/<CONFIG_HASH>/config/，主构建消费投影副本。
$(NPC_CONFIG_MODULE_STAGE) $(NPC_CONFIG_EFFECTIVE_STAGE) $(NPC_CONFIG_TYPED_STAGE) $(NPC_CONFIG_SCALA_DEFAULTS) &: $(NPC_CONFIG_JSON) $(NPC_CONFIG_GEN_BIN) $(NPC_CONFIG_STAMP)
	@mkdir -p $(dir $(NPC_CONFIG_MODULE_STAGE)) $(dir $(NPC_CONFIG_SCALA_DEFAULTS))
	$(NPC_CONFIG_GEN_BIN) \
		--config-json $(NPC_CONFIG_JSON) \
		--effective-json $(NPC_CONFIG_EFFECTIVE_STAGE) \
		--typed-json $(NPC_CONFIG_TYPED_STAGE) \
		--scala-defaults $(NPC_CONFIG_SCALA_DEFAULTS) \
		--perf-build-metadata $(PERF_BUILD_METADATA) \
		--perf-synth $(PERF_SYNTH) \
		--savable $(VERILATOR_SAVABLE) \
		$(NPC_CONFIG_OVERRIDE_ARGS) \
		--output $(NPC_CONFIG_MODULE_STAGE)

.PHONY: config-projection-dir
config-projection-dir:
	@mkdir -p "$(CONFIG_PROJECTION_DIR)"

# 投影副本：内容相同时不刷新 mtime；hash/签名未知时 config-hash-require
# 会在复制前失败，不会把 staging 写进 unconfigured 目录。
$(CONFIG_PROJECTION_DIR)/npc_config.typed.json: $(NPC_CONFIG_TYPED_STAGE) | config-hash-require config-projection-dir
	@mkdir -p "$(@D)"
	@if test -f "$@" && cmp -s "$(NPC_CONFIG_TYPED_STAGE)" "$@"; then :; else cp -f "$(NPC_CONFIG_TYPED_STAGE)" "$@"; fi

$(CONFIG_PROJECTION_DIR)/npc_config.effective.json: $(NPC_CONFIG_EFFECTIVE_STAGE) | config-hash-require config-projection-dir
	@mkdir -p "$(@D)"
	@if test -f "$@" && cmp -s "$(NPC_CONFIG_EFFECTIVE_STAGE)" "$@"; then :; else cp -f "$(NPC_CONFIG_EFFECTIVE_STAGE)" "$@"; fi

$(CONFIG_PROJECTION_DIR)/npc_config.ixx: $(NPC_CONFIG_MODULE_STAGE) | config-hash-require config-projection-dir
	@mkdir -p "$(@D)"
	@if test -f "$@" && cmp -s "$(NPC_CONFIG_MODULE_STAGE)" "$@"; then :; else cp -f "$(NPC_CONFIG_MODULE_STAGE)" "$@"; fi

npc-config-json: $(NPC_CONFIG_JSON)
npc-config-tool: $(NPC_CONFIG_GEN_BIN)
npc-config: $(NPC_CONFIG_MODULE) $(NPC_CONFIG_EFFECTIVE_JSON) $(NPC_CONFIG_TYPED_JSON) $(NPC_CONFIG_SCALA_DEFAULTS) | config-hash-require kconfig-generated-check
	@set -eu; \
	test -f "$(CONFIG_HASH_SCHEMA)" || { \
		echo "错误：缺少 ParamGen schema：$(CONFIG_HASH_SCHEMA)" >&2; \
		exit 2; \
	}; \
	test -f "$(NPC_CONFIG_MODULE)" || { \
		echo "错误：配置投影缺少 $(NPC_CONFIG_MODULE)" >&2; \
		exit 2; \
	}; \
	echo "[npc-config] CONFIG_HASH=$(CONFIG_HASH)"; \
	echo "[npc-config] BUILD_SIGNATURE=$(BUILD_SIGNATURE)"; \
	echo "[npc-config] 配置投影：$(CONFIG_PROJECTION_DIR)"; \
	echo "[npc-config] 构建目录：$(CONFIG_BUILD_DIR)"; \
	echo "[npc-config] build/$(TOPNAME)_npc、build/$(TOPNAME)_gsim 等旧路径在构建成功后才更新 symlink。"

.PHONY: npc-config npc-config-json npc-config-tool

# direct-NPC的SoC AXI只允许32/64，CPU master与AXIRAM同宽直连
# 配置若被手工改成其他宽度，在启动Verilator前报错
.PHONY: npc-direct-soc-xlen-check
npc-direct-soc-xlen-check: $(NPC_CONFIG_EFFECTIVE_JSON) $(NPC_CONFIG_GEN_BIN)
	@set -eu; \
	soc_xlen="$$($(NPC_CONFIG_GEN_BIN) --config-json '$(NPC_CONFIG_EFFECTIVE_JSON)' --get SOC_XLEN_EFFECTIVE)"; \
	case "$$soc_xlen" in \
		32|64) ;; \
		*) \
			echo "错误：direct-NPC只支持32/64位SoC AXI，当前EffectiveSocXLEN=$$soc_xlen。" >&2; \
			exit 2 ;; \
	esac

# synth/yosys-sta在SIM_TOP!=soc时综合direct-NPC CPU RTL，沿用同一检查。
# SIM_TOP=soc的完整SoC保持Kconfig的SOC_XLEN。
ifneq ($(SIM_TOP),soc)
synth yosys-sta: npc-direct-soc-xlen-check
endif

# 配置契约：Kconfig 只能表达连续 range，这里补齐 XLEN/SOC_XLEN、
# PTE 大小、页大小 2 的幂以及 CUSTOM A/D 高位等离散约束。
# 校验器是纯 C++ 工具，配置阶段和回归都不再依赖 Python。
KCONFIG_CONTRACT_DIR := $(abspath tools/KconfigContract)
KCONFIG_CONTRACT_SOURCES := $(wildcard $(KCONFIG_CONTRACT_DIR)/*.cpp)
KCONFIG_CONTRACT_MODULE := $(KCONFIG_CONTRACT_DIR)/npc.kconfig_contract.ixx
KCONFIG_CONTRACT_CMAKE := $(KCONFIG_CONTRACT_DIR)/CMakeLists.txt
KCONFIG_CONTRACT_BUILD_DIR := $(abspath tools/build/KconfigContract)
KCONFIG_CONTRACT_STAMP := $(KCONFIG_CONTRACT_BUILD_DIR)/.built
KCONFIG_CONTRACT_BIN := $(abspath tools/build/kconfig_contract)
KCONFIG_CONTRACT_JOBS ?= $(shell nproc 2>/dev/null || echo 4)

$(KCONFIG_CONTRACT_STAMP): $(KCONFIG_CONTRACT_SOURCES) $(KCONFIG_CONTRACT_MODULE) $(KCONFIG_CONTRACT_CMAKE)
	@cmake -S "$(KCONFIG_CONTRACT_DIR)" -B "$(KCONFIG_CONTRACT_BUILD_DIR)" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$(CXX)"
	@cmake --build "$(KCONFIG_CONTRACT_BUILD_DIR)" -j $(KCONFIG_CONTRACT_JOBS)
	@touch $@

$(KCONFIG_CONTRACT_BIN): $(KCONFIG_CONTRACT_STAMP)
	@cp -f "$(KCONFIG_CONTRACT_BUILD_DIR)/kconfig_contract" "$@"

# 完整SoC构建时额外校验Kconfig PMA与ysyxSoC硬编码地址一致；
# direct-NPC不启用，避免误伤
KCONFIG_CONTRACT_SOC_FLAG := $(if $(filter soc,$(SIM_TOP)),--soc-rtl)
ifeq ($(NPC_SOC_CONTRACT),1)
KCONFIG_CONTRACT_SOC_FLAG := --soc-rtl
endif

# 契约检查同时消费 .config 与 npc_config_gen 的 typed JSON flat：
# .config 代表请求值，typed JSON 代表生成器实际交给 RTL/C++ 的生效值。
# typed JSON 是普通依赖，配置或生成器变化时会先更新它再检查。
# BIN/BIN_NPC/verilog/gsim/synth 用 order-only 依赖本目标：每次构建前都会
# 重新检查，但检查本身不会让这些目标被误判为需要重建。
KCONFIG_CONTRACT_TYPED_PREREQ := $(if $(wildcard .config),$(NPC_CONFIG_TYPED_JSON),)

kconfig-contract-check: $(KCONFIG_CONTRACT_BIN) $(KCONFIG_CONTRACT_TYPED_PREREQ)
	$(KCONFIG_CONTRACT_BIN) --defconfigs
	$(KCONFIG_CONTRACT_BIN) --self-test
	@if [ -f .config ]; then \
		$(KCONFIG_CONTRACT_BIN) --config .config \
			--typed-json '$(NPC_CONFIG_TYPED_JSON)' \
			$(KCONFIG_CONTRACT_SOC_FLAG) $(NPC_CONFIG_OVERRIDE_ARGS); \
	fi

kconfig-contract-tool: $(KCONFIG_CONTRACT_BIN)

.PHONY: kconfig-contract-check kconfig-contract-tool

# Kconfig 入口与生成树参与依赖：schema/生成器变化时 stamp 更新，强制重新
# syncconfig，不能继续使用旧的 npc_config.json/.config 投影。
$(NPC_CONFIG_JSON): $(NPC_HOME)/Kconfig $(KCONFIG_GENERATED)
npc-config: $(CONF)
