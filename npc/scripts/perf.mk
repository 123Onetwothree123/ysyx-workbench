# ============================================================================
# 性能元数据、综合与 perf
# ============================================================================

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
	npc_rtl="$(abspath $(CONFIG_BUILD_DIR)/ysyx_26030103_npc.sv)"; \
	soc_rtl="$(abspath $(CONFIG_BUILD_DIR)/ysyx_26030103.sv)"; \
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
SYNTH_RTL_FILES = $(abspath $(CONFIG_BUILD_DIR)/ysyx_26030103.sv)
else
SYNTH_DESIGN = ysyx_26030103
SYNTH_RTL_FILES = $(abspath $(CONFIG_BUILD_DIR)/ysyx_26030103_npc.sv)
endif

# The identity directory is deliberately reusable.  Publish an invalid
# manifest and delete the exact old area report before each run, so a failed
# synthesis or a missing new report cannot inherit a valid old measurement.
synth: verilog perf-build-metadata | kconfig-contract-check
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
perf: $(SIM_GUARD) synth
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
perf: $(SIM_GUARD) synth
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
