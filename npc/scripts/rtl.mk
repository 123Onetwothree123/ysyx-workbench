# ============================================================================
# Chisel/FIRRTL/Verilog 生成与 ysyxSoC RTL
# ============================================================================

# npc 独立仿真路径的复位 PC：0x80000000 + Kconfig 的 CACHE_PADDING。

# Keep the effective hardware knobs in one stable, human-readable record.
# The hash of this exact string identifies configurations even when two builds
# come from the same git commit.  Target selection is recorded separately:
# one elaboration emits both the direct-NPC and SoC CPU wrappers.
# 性能计数器：组 → 组内单计数器键。键名与Kconfig符号后缀、RTL端口后缀
# 以及C++宏名一一对应；单计数器同时受所属组总开关与PERF_DIC_ARCH_COUNTER约束。

verilog: config-hash-require $(NPC_CONFIG_MODULE) $(NPC_CONFIG_TYPED_JSON) | kconfig-contract-check
	$(call git_commit, "generate verilog")
	mkdir -p $(CONFIG_BUILD_DIR)
	env NPC_CONFIG_JSON='$(NPC_CONFIG_TYPED_JSON)' \
		mill -i runMain ysyx_26030103_Elaborate --target-dir $(CONFIG_BUILD_DIR)
	ln -sf layers-ysyx_26030103-Verification.sv $(CONFIG_BUILD_DIR)/layers-riscv32e_npc_AXIRAM-Verification.sv

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
$(GSIM_CHIRRTL): $(GSIM_SCALA_SOURCES) $(NPC_CONFIG_MODULE) $(NPC_CONFIG_TYPED_JSON)
	@mkdir -p $(CONFIG_BUILD_DIR)
	env NPC_CONFIG_JSON='$(NPC_CONFIG_TYPED_JSON)' \
		mill -i runMain ysyx_26030103_Elaborate --target-dir $(CONFIG_BUILD_DIR) --emit-chirrtl
# Always enter the SoC sub-build.  Its own config stamp and Scala dependencies
# decide whether regeneration is necessary; checking only for the final file
# here would otherwise reuse stale RTL after a CPU port or CHIPLINK change.
.PHONY: ysyxsoc-verilog-force
ysyxSoC-verilog-force:

$(YSYXSOC_FULL_V): ysyxsoc-verilog-force $(NPC_CONFIG_TYPED_JSON)
	@echo "[perf] (re)building ysyxSoC ..."
	@$(MAKE) -C $(YSYXSOC_DIR) \
		NPC_CONFIG_JSON='$(NPC_CONFIG_TYPED_JSON)' \
		$(YSYXSOC_FLASH_XIP_ENV) \
		verilog
