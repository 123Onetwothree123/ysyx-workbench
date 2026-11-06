# direct-NPC SoC 位宽契约回归。
#
# riscv32e_npc_SimTop/AXIRAM 固定 32 位 AXI；构建 direct-NPC 时必须把
# Kconfig 的 SOC_XLEN/FOLLOW_CPU 投影为 EffectiveSocXLEN=32。本测试
# 故意从命令行传入 64 位配置，验证 Makefile 的 target-specific 覆盖和
# npc-direct-soc-xlen-check 仍然生效，不需要启动 Verilator。
SHELL := /bin/bash
NPC_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../..)
NPC_CONFIG_GEN := $(NPC_DIR)/tools/build/npc_config_gen
EFFECTIVE_JSON := $(NPC_DIR)/build/generated/npc_config.effective.json

.PHONY: test test-internal clean

test:
	@test -f '$(NPC_DIR)/.config' || { \
		echo "错误：先配置 npc（例如 make rv64_linux_defconfig）" >&2; \
		exit 2; \
	}
	$(MAKE) -C '$(NPC_DIR)' npc-direct-soc-xlen-check \
		CONFIG_SOC_XLEN_FOLLOW_CPU=n CONFIG_SOC_XLEN=64
	$(MAKE) -C '$(NPC_DIR)' npc-direct-soc-xlen-check \
		CONFIG_XLEN=64 CONFIG_SOC_XLEN_FOLLOW_CPU=y
	@echo "[PASS] direct-NPC EffectiveSocXLEN 被强制为 32"

# 内部 fabric 宽度：FOLLOW=y 时必须等于 XLEN；FOLLOW=n 时必须使用显式值。
test-internal:
	@test -f '$(NPC_DIR)/.config' || { \
		echo "错误：先配置 npc（例如 make rv64_linux_defconfig）" >&2; \
		exit 2; \
	}
	@$(MAKE) -s -C '$(NPC_DIR)' npc-config \
		CONFIG_INTERNAL_BUS_WIDTH_FOLLOW_CPU=y >/dev/null
	@xlen=$$($(NPC_CONFIG_GEN) --config-json $(EFFECTIVE_JSON) --get XLEN); \
	width=$$($(NPC_CONFIG_GEN) --config-json $(EFFECTIVE_JSON) \
		--get INTERNAL_BUS_WIDTH_EFFECTIVE); \
	test "$$width" = "$$xlen" || { \
		echo "错误：FOLLOW=y 时 INTERNAL_BUS_WIDTH_EFFECTIVE=$$width，期望 $$xlen" >&2; \
		exit 1; \
	}
	@$(MAKE) -s -C '$(NPC_DIR)' npc-config \
		CONFIG_INTERNAL_BUS_WIDTH_FOLLOW_CPU=n \
		CONFIG_INTERNAL_BUS_WIDTH=32 >/dev/null
	@test "$$($(NPC_CONFIG_GEN) --config-json $(EFFECTIVE_JSON) \
		--get INTERNAL_BUS_WIDTH_EFFECTIVE)" = 32
	@$(MAKE) -s -C '$(NPC_DIR)' npc-config \
		CONFIG_INTERNAL_BUS_WIDTH_FOLLOW_CPU=n \
		CONFIG_INTERNAL_BUS_WIDTH=64 >/dev/null
	@test "$$($(NPC_CONFIG_GEN) --config-json $(EFFECTIVE_JSON) \
		--get INTERNAL_BUS_WIDTH_EFFECTIVE)" = 64
	@echo "[PASS] INTERNAL_BUS_WIDTH/FOLLOW_CPU 投影正确"

clean:
	@:
