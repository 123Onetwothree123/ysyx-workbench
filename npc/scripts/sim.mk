# ============================================================================
# Verilator / GSIM 构建、运行与测试
# ============================================================================

.PHONY: compile-commands
compile-commands: $(SIM_GUARD)
	@if [ "$(CONFIG_CC_COMPILE_COMMANDS)" != "y" ]; then \
		echo "错误：请先在 menuconfig 中启用 CONFIG_CC_COMPILE_COMMANDS=y" >&2; \
		exit 2; \
	fi
	@mkdir -p $(CONFIG_BUILD_DIR)
	intercept-build --cdb $(BUILD_DIR)/compile_commands.json \
		$(MAKE) --no-print-directory git_commit= $(BIN_NPC)
clean:
	-@rm -rf "$(CONFIG_BUILD_DIR)" \
		"$(LEGACY_BIN)" "$(LEGACY_BIN_NPC)" "$(LEGACY_GSIM_BIN)" \
		"$(BUILD_DIR)/compile_commands.json" itrace.txt
	@if [ -n "$(CONFIG_HASH)" ] && [ -n "$(BUILD_SIGNATURE)" ]; then \
		echo "clean: 只删除当前配置+签名目录 $(CONFIG_BUILD_DIR)；其他 hash/signature 目录保留。"; \
		echo "       配置投影 $(CONFIG_PROJECTION_DIR) 保留给同一配置的其他签名。"; \
		echo "       如需删除整个 build（所有 hash/signature），请运行 make distclean。"; \
	else \
		echo "clean: 未能确定 CONFIG_HASH/BUILD_SIGNATURE，只删除旧路径 symlink。"; \
		echo "       如需删除整个 build（所有 hash/signature），请运行 make distclean。"; \
	fi

CACHESIM_DIR  := $(CSRC_DIR)/tools/cachesim
CACHESIM_BIN  := $(CACHESIM_DIR)/build/cachesim

$(CACHESIM_BIN):
	@cmake -B $(CACHESIM_DIR)/build -S $(CACHESIM_DIR) \
		$(if $(filter y,$(CONFIG_CC_COMPILE_COMMANDS)),-DCONFIG_CC_COMPILE_COMMANDS=y) \
		&& cmake --build $(CACHESIM_DIR)/build

.PHONY: cachesim
cachesim: $(SIM_GUARD) $(CACHESIM_BIN)
	@rm -f itrace.txt
	@$(MAKE) run CONFIG_TRACE=y CONFIG_ITRACE=y CONFIG_ITRACE_WRITE_FILE=y file=$(file)
	@echo ""
	@echo "cachesim 结果："
	@$(CACHESIM_BIN) itrace.txt

.PHONY: cachesim_dse
cachesim_dse: $(SIM_GUARD) $(CACHESIM_BIN)
	@rm -f itrace.txt
	@$(MAKE) run CONFIG_TRACE=y CONFIG_ITRACE=y CONFIG_ITRACE_WRITE_FILE=y file=$(file)
	@echo ""
	@echo "cachesim DSE 探索中..."
	@$(CACHESIM_BIN) --dse itrace.txt

.PHONY: test verilog help reformat checkformat clean synth perf

sim: $(SIM_GUARD)
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by yourself."

.PHONY: sim-disabled
sim-disabled:
	@echo "错误：当前配置为 FPGA 模式（NPC_MODE_FPGA_ONLY=y），Verilator 仿真构建已关闭。" >&2
	@echo "请切换为“一生一芯模式”或“FPGA + 一生一芯同时开启”。" >&2
	@exit 1
# 只在这些值真正变化时更新stamp，避免每次make都重编harness
.PHONY: gsim-config-stamp-force
gsim-config-stamp-force:
$(GSIM_CONFIG_STAMP): gsim-config-stamp-force $(NPC_CONFIG_EFFECTIVE_JSON)
	@mkdir -p $(@D)
	@printf '%s\n' \
		"cxx=$(GSIM_CXX)" \
		"std=$(GSIM_CXX_STD)" \
		"opt=$(GSIM_OPT_FLAG)" \
		"warn=$(GSIM_WARN_FLAGS)" \
		"runtime=$(GSIM_RUNTIME_FLAGS)" \
		"stdlib=$(GSIM_STDLIB_FLAG)" \
		"ld=$(GSIM_LD_FLAG)" \
		"cache_padding=$(GSIM_CACHE_PADDING)" \
		"ram_bytes=$(GSIM_RAM_BYTES)" > $@.tmp
	@if test -f $@ && cmp -s $@.tmp $@; then rm -f $@.tmp; else mv -f $@.tmp $@; fi

# GSIM不实现Verilator harness里的SDB/追踪/差分测试/性能存档/日志功能；
# 正常menuconfig会隐藏这些选项，这里兜底提示命令行或旧配置留下的值
GSIM_UNSUPPORTED_CONFIGS := $(strip \
	$(if $(filter y,$(CONFIG_SDB)),SDB) \
	$(if $(filter y,$(CONFIG_TRACE)),TRACE) \
	$(if $(filter y,$(CONFIG_ITRACE)),ITRACE) \
	$(if $(filter y,$(CONFIG_ITRACE_WRITE_FILE)),ITRACE_WRITE_FILE) \
	$(if $(filter y,$(CONFIG_MTRACE)),MTRACE) \
	$(if $(filter y,$(CONFIG_FTRACE)),FTRACE) \
	$(if $(filter y,$(CONFIG_IRINGBUF)),IRINGBUF) \
	$(if $(filter y,$(CONFIG_WATCHPOINT)),WATCHPOINT) \
	$(if $(filter y,$(CONFIG_DIFFTEST)),DIFFTEST) \
	$(if $(filter y,$(CONFIG_PERF_DIC_ARCH_COUNTER)),PERF_DIC_ARCH_COUNTER) \
	$(if $(filter y,$(CONFIG_PERF_SAVE)),PERF_SAVE) \
	$(if $(filter y,$(CONFIG_LOG_ERROR) $(CONFIG_LOG_WARN) $(CONFIG_LOG_INFO) $(CONFIG_LOG_DEBUG) $(CONFIG_LOG_TO_FILE) $(CONFIG_LOG_TIMESTAMP) $(CONFIG_LOG_SOURCE_LOCATION)),LOG_*))

.PHONY: gsim-config-check
gsim-config-check:
	@if [ -n "$(GSIM_UNSUPPORTED_CONFIGS)" ]; then \
		echo "警告：GSIM后端不支持以下已启用选项，本次构建不会让它们生效：" >&2; \
		echo "      $(GSIM_UNSUPPORTED_CONFIGS)" >&2; \
		echo "      请在menuconfig中切换到Verilator后端或关闭这些选项" >&2; \
	fi

# GSIM与Verilator共用direct-NPC SimTop，沿用同一32/64位SoC AXI检查
gsim gsim-run: $(SIM_GUARD) npc-direct-soc-xlen-check gsim-config-check | kconfig-contract-check
$(GSIM_MODEL): $(GSIM_CHIRRTL)
	@rm -rf $(GSIM_DIR)
	@mkdir -p $(GSIM_DIR)
	$(GSIM_COMMAND) --dir $(GSIM_DIR) $(GSIM_THREADS_FLAG) $(GSIM_EXTRA_ARGS) $(GSIM_CHIRRTL)

$(GSIM_BIN): $(GSIM_MODEL) $(GSIM_HARNESS) $(GSIM_CONFIG_STAMP) | config-hash-require
	@mkdir -p $(GSIM_OBJ_DIR)
	@echo "GSIM: 使用 $(GSIM_CXX) 编译（CONFIG_CC=$(or $(call unquote_config,$(CONFIG_CC)),未设置) 不适用于 GSIM）"
	@echo "GSIM: harness CXXFLAGS=$(GSIM_HARNESS_CXXFLAGS)"
	@echo "GSIM: model CXXFLAGS=$(GSIM_MODEL_CXXFLAGS)"
	@echo "GSIM: 逐文件并行编译，线程数=$(NPROC)"
	@set -eu; \
		rm -f "$(GSIM_OBJ_DIR)"/*.o; \
		{ printf '%s\n' "$(GSIM_HARNESS)"; \
		  find "$(GSIM_DIR)" -maxdepth 1 -type f -name '*.cpp' -print | sort; \
		} | xargs -r -d '\n' -P $(NPROC) -n 1 sh -c '\
			src="$$1"; \
			if [ "$$src" = "$(GSIM_HARNESS)" ]; then \
				$(GSIM_CXX) $(GSIM_HARNESS_CXXFLAGS) -c "$$src" -o "$(GSIM_OBJ_DIR)/npc_gsim_harness.o"; \
			else \
				base="$$(basename "$$src" .cpp)"; \
				$(GSIM_CXX) $(GSIM_MODEL_CXXFLAGS) -c "$$src" -o "$(GSIM_OBJ_DIR)/model_$$base.o"; \
			fi \
		' sh; \
		$(GSIM_CXX) $(GSIM_LDFLAGS) "$(GSIM_OBJ_DIR)"/*.o -o "$(GSIM_BIN)"

.PHONY: gsim gsim-run
# 旧路径 build/ysyx_26030103_gsim 在 GSIM 构建成功后更新为 symlink；
# 构建失败时保留原链接，不会指向半成品。
$(LEGACY_GSIM_BIN): $(GSIM_BIN) build-links-force
	@set -eu; \
	test -x "$(GSIM_BIN)" || { echo "错误：构建产物不存在：$(GSIM_BIN)" >&2; exit 2; }; \
	ln -sfn "$(CONFIG_HASH)/$(BUILD_SIGNATURE)/$(notdir $(GSIM_BIN))" "$@"

gsim: $(GSIM_BIN) $(LEGACY_GSIM_BIN)
	@if [ -n "$(strip $(file))" ]; then \
		$(GSIM_BIN) $(file); \
	else \
		echo "GSIM 仿真器已生成: $(GSIM_BIN)"; \
		echo "运行: make gsim file=<image.bin>"; \
	fi

gsim-run: $(GSIM_BIN)
	@test -n "$(strip $(file))" || { \
		echo "错误：请用 file=<image.bin> 指定镜像。" >&2; \
		exit 2; \
	}
	$(GSIM_BIN) $(file)
simtest: $(SIM_GUARD) $(NPC_CONFIG_MODULE) $(NPC_VERILOG_PREREQ) $(YSYXSOC_FULL_V) $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND) | config-hash-require
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) $(VERILATOR_FLASH_XIP_DEFINE) \
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
		NPC_CONFIG_MODULE='$(abspath $(NPC_CONFIG_MODULE))' \
		NPC_SAVABLE='$(VERILATOR_SAVABLE)' \
		NPC_CONFIG_EFFECTIVE_JSON='$(abspath $(NPC_CONFIG_EFFECTIVE_JSON))' \
		default
$(BIN): $(SIM_GUARD) $(NPC_CONFIG_MODULE) $(NPC_VERILOG_PREREQ) $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND) $(YSYXSOC_FULL_V) | kconfig-contract-check config-hash-require
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) $(VERILATOR_FLASH_XIP_DEFINE) \
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
		NPC_CONFIG_MODULE='$(abspath $(NPC_CONFIG_MODULE))' \
		NPC_SAVABLE='$(VERILATOR_SAVABLE)' \
		NPC_CONFIG_EFFECTIVE_JSON='$(abspath $(NPC_CONFIG_EFFECTIVE_JSON))' \
		default
$(BIN_NPC): $(SIM_GUARD) $(NVBOARD_DIRECT_GUARD) npc-direct-soc-xlen-check $(NPC_CONFIG_MODULE) $(NPC_VERILOG_PREREQ) $(CSRCS) $(VERILATOR_BUILD_WRAPPER) | kconfig-contract-check config-hash-require
	@set -eu; \
	soc_xlen="$$($(NPC_CONFIG_GEN_BIN) --config-json '$(NPC_CONFIG_EFFECTIVE_JSON)' --get SOC_XLEN_EFFECTIVE)"; \
	addr_msb=$$((soc_xlen - 1)); \
	strb_msb=$$((soc_xlen / 8 - 1)); \
	npc_rtl="$(CONFIG_BUILD_DIR)/ysyx_26030103_npc.sv"; \
	ram_rtl="$(CONFIG_BUILD_DIR)/riscv32e_npc_AXIRAM.sv"; \
	if ! grep -Eq "output[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_master_awaddr" "$$npc_rtl" || \
	   ! grep -Eq "output[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_master_araddr" "$$npc_rtl" || \
	   ! grep -Eq "output[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_master_wdata" "$$npc_rtl" || \
	   ! grep -Eq "input[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_master_rdata" "$$npc_rtl" || \
	   ! grep -Eq "output[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$strb_msb:0\\][[:space:]]+io_master_wstrb" "$$npc_rtl"; then \
		echo "错误：$$npc_rtl 的 master AXI 宽度与 EffectiveSocXLEN=$$soc_xlen 不一致。" >&2; \
		echo "      请重新运行 make build/ysyx_26030103_npc，不要复用其他配置的 RTL。" >&2; \
		exit 2; \
	fi; \
	if ! grep -Eq "input[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_AXI_AW_AWADDR" "$$ram_rtl" || \
	   ! grep -Eq "input[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_AXI_W_WDATA" "$$ram_rtl" || \
	   ! grep -Eq "input[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$strb_msb:0\\][[:space:]]+io_AXI_W_WSTRB" "$$ram_rtl" || \
	   ! grep -Eq "output[[:space:]]+(logic[[:space:]]+|wire[[:space:]]+)?\\[$$addr_msb:0\\][[:space:]]+io_AXI_R_RDATA" "$$ram_rtl"; then \
		echo "错误：$$ram_rtl 的 AXIRAM 下游 AXI 宽度与 EffectiveSocXLEN=$$soc_xlen 不一致。" >&2; \
		echo "      AWADDR/WDATA/RDATA 应为 $$soc_xlen 位，WSTRB 应为 $$((soc_xlen / 8)) 位。" >&2; \
		exit 2; \
	fi
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(SAVABLE_VERILATOR_FLAG) $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) $(VERILATOR_FLASH_XIP_DEFINE) \
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
		NPC_CONFIG_MODULE='$(abspath $(NPC_CONFIG_MODULE))' \
		NPC_SAVABLE='$(VERILATOR_SAVABLE)' \
		NPC_CONFIG_EFFECTIVE_JSON='$(abspath $(NPC_CONFIG_EFFECTIVE_JSON))' \
		default
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

ifeq ($(CONFIG_SIM_BACKEND_GSIM),y)
# GSIM后端直接复用gsim-run；不再进入Verilator的BIN/BIN_NPC依赖链
run: $(SIM_GUARD) gsim-run
else ifeq ($(CONFIG_PGO_AUTO),y)
# PGO_AUTO（自动一步到位）：make run 等价于 make pgo file=镜像
# 内部完成 插桩编译→收集profile→优化编译→运行最终程序
ifeq ($(SIM_TOP),soc)
run: $(SIM_GUARD) pgo
else
run: $(SIM_GUARD) $(BIN_NPC)
	@echo "警告: PGO_AUTO 目前仅支持 SIM_TOP=soc，本次执行普通运行"
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) $(NPC_BATCH_FLAG) 2>&1
endif
else ifeq ($(SIM_TOP),soc)
run: $(SIM_GUARD) $(BIN)
	$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) $(NPC_BATCH_FLAG) 2>&1
else
run: $(SIM_GUARD) $(BIN_NPC)
	$(BIN_NPC) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),) $(NPC_BATCH_FLAG) 2>&1
endif

.PHONY: pgo
pgo: $(SIM_GUARD) verilog
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
