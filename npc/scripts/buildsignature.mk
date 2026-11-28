# ============================================================================
# 构建签名：在 effective config hash 之外隔离工具链、编译选项和调试开关
# ============================================================================
# CONFIG_HASH 只投影硬件有效参数；同一份硬件配置仍可能因为编译器版本、
# -O 级别、sanitizer、SDB/trace/difftest/perf 开关不同而生成不兼容的对象。
# BUILD_SIGNATURE 把这些构建输入规范化成一份稳定文本再取 SHA256：
#   build/<CONFIG_HASH>/<BUILD_SIGNATURE>/
#
# 签名文件本身也是 make 的 included makefile。make 在读取 Makefile 后先
# 重建它，再重启并展开正确的 CONFIG_BUILD_DIR，所以一次 make 不会把新
# 配置的对象写进旧签名目录。

BUILD_SIGNATURE_DOMAIN := npc-build-signature-v1
BUILD_SIGNATURE_MK := $(abspath $(BUILD_DIR)/generated/build_signature.mk)

# 单引号 shell 转义：签名输入里的字符串值可能包含空格/引号/逗号。
npc_signature_shq = '$(subst ','"'"',$(1))'

# 规范化的 y/n 视图；Kconfig 字符串在 .config 里带引号，这里只处理布尔值。
npc_signature_bool = $(if $(filter y,$(strip $(1))),y,n)

# 版本行只保留第一行，并去掉绝对路径；不写入时间戳、主机名或工作目录。
# 这样同一编译器/仿真器版本在多次 make 之间保持稳定。
npc_signature_first_version = \
	LC_ALL=C $(1) --version 2>/dev/null | sed -n '1p' | \
	sed -E 's~(^|[[:space:]])/[^[:space:]]+~\1<path>~g' | \
	sed -E 's/[[:space:]]+$$//'

.PHONY: build-signature-force config-buildsignature-require
build-signature-force:

# 真正构建时先更新配置/哈希；clean 走快速路径，不重建 NPCConfigGEN 或
# 配置投影，只消费已有的 config_hash.mk 或 $(CONFIG_HASH)。
NPC_SIGNATURE_CONFIG_DEPS := $(if $(NPC_NEEDS_HASH),$(CONFIG_HASH_MK) $(NPC_CONFIG_TYPED_STAGE) $(NPC_CONFIG_MODULE_STAGE),)
$(BUILD_SIGNATURE_MK): $(if $(wildcard .config),.config,) $(NPC_SIGNATURE_CONFIG_DEPS) \
	build-signature-force
	@set -eu; \
	mkdir -p "$(@D)"; \
	inputs="$(@D)/.build-signature-inputs.$$$$"; \
	trap 'rm -f "$$inputs"' EXIT HUP INT TERM; \
	if test "$(strip $(NPC_NEEDS_HASH))" != "1" && test -n "$(strip $(CONFIG_HASH))"; then \
		config_hash='$(strip $(CONFIG_HASH))'; \
	else \
		config_hash="$$(sed -n 's/^CONFIG_HASH := //p' '$(CONFIG_HASH_MK)' 2>/dev/null | tail -n1)"; \
	fi; \
	if test -z "$$config_hash"; then config_hash="$(strip $(CONFIG_HASH))"; fi; \
	test -n "$$config_hash" || { \
		echo "错误：无法确定 CONFIG_HASH，拒绝写入 build signature。" >&2; \
		echo "      缺少 $(CONFIG_HASH_MK)；请先运行 make npc-config。" >&2; \
		exit 2; \
	}; \
	case "$$config_hash" in \
		*[!0-9a-f]*) \
			echo "错误：CONFIG_HASH 不是十六进制字符串：$$config_hash" >&2; \
			exit 2 ;; \
	esac; \
	cc_version="$$($(call npc_signature_first_version,$(CC)) || true)"; \
	test -n "$$cc_version" || cc_version=unavailable; \
	cxx_version="$$($(call npc_signature_first_version,$(CXX)) || true)"; \
	test -n "$$cxx_version" || cxx_version=unavailable; \
	verilator_version="$$($(call npc_signature_first_version,$(VERILATOR)) || true)"; \
	test -n "$$verilator_version" || verilator_version=unavailable; \
	gsim_version="$$($(call npc_signature_first_version,$(GSIM_COMMAND)) || true)"; \
	test -n "$$gsim_version" || gsim_version=unavailable; \
	{ \
		printf '%s\n' 'domain=$(BUILD_SIGNATURE_DOMAIN)'; \
		printf 'CONFIG_CC=%s\n' $(call npc_signature_shq,$(strip $(CONFIG_CC))); \
		printf 'CONFIG_CXX=%s\n' $(call npc_signature_shq,$(strip $(CONFIG_CXX))); \
		printf 'CC=%s\n' $(call npc_signature_shq,$(strip $(CC))); \
		printf 'CXX=%s\n' $(call npc_signature_shq,$(strip $(CXX))); \
		printf 'CC_OPT=%s\n' $(call npc_signature_shq,$(strip $(CC_OPT))); \
		printf 'USER_CXX_STD=%s\n' $(call npc_signature_shq,$(strip $(USER_CXX_STD))); \
		printf 'VERILATOR_CXX_STD=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_CXX_STD))); \
		printf 'STDLIB_FLAG=%s\n' $(call npc_signature_shq,$(strip $(STDLIB_FLAG))); \
		printf 'LD_FLAG=%s\n' $(call npc_signature_shq,$(strip $(LD_FLAG))); \
		printf 'HARDENING_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(HARDENING_CFLAGS))); \
		printf 'LTO_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(LTO_CFLAGS))); \
		printf 'PGO_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(PGO_CFLAGS))); \
		printf 'DEBUG_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(DEBUG_CFLAGS))); \
		printf 'ASAN_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(ASAN_CFLAGS))); \
		printf 'UBSAN_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(UBSAN_CFLAGS))); \
		printf 'TSAN_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(TSAN_CFLAGS))); \
		printf 'COVERAGE_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(COVERAGE_CFLAGS))); \
		printf 'GPROF_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(GPROF_CFLAGS))); \
		printf 'WALL_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(WALL_CFLAGS))); \
		printf 'WERROR_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(WERROR_CFLAGS))); \
		printf 'PEDANTIC_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(PEDANTIC_CFLAGS))); \
		printf 'WCONVERSION_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(WCONVERSION_CFLAGS))); \
		printf 'COLOR_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(COLOR_CFLAGS))); \
		printf 'PIPE_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(PIPE_CFLAGS))); \
		printf 'SPLIT_DWARF_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(SPLIT_DWARF_CFLAGS))); \
		printf 'GC_SECTIONS_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(GC_SECTIONS_CFLAGS))); \
		printf 'GC_SECTIONS_LDFLAGS=%s\n' $(call npc_signature_shq,$(strip $(GC_SECTIONS_LDFLAGS))); \
		printf 'VISIBILITY_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(VISIBILITY_CFLAGS))); \
		printf 'MARCH_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(MARCH_CFLAGS))); \
		printf 'IPA_PTA_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(IPA_PTA_CFLAGS))); \
		printf 'PREFETCH_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(PREFETCH_CFLAGS))); \
		printf 'TIME_REPORT_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(TIME_REPORT_CFLAGS))); \
		printf 'NO_OMIT_FP_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(NO_OMIT_FP_CFLAGS))); \
		printf 'ICPX_NOSTDINCXX_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(ICPX_NOSTDINCXX_CFLAGS))); \
		printf 'MODULE_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(MODULE_CFLAGS))); \
		printf 'CONFIG_BUILD_TIMESTAMP=%s\n' '$(call npc_signature_bool,$(CONFIG_BUILD_TIMESTAMP))'; \
		printf 'CONFIG_CC_COMPILE_COMMANDS=%s\n' '$(call npc_signature_bool,$(CONFIG_CC_COMPILE_COMMANDS))'; \
		printf 'CONFIG_NVBOARD=%s\n' '$(call npc_signature_bool,$(CONFIG_NVBOARD))'; \
		printf 'NVBOARD_CFLAGS=%s\n' $(call npc_signature_shq,$(strip $(NVBOARD_CFLAGS))); \
		printf 'NVBOARD_LDFLAGS=%s\n' $(call npc_signature_shq,$(strip $(NVBOARD_LDFLAGS))); \
		printf 'CONFIG_SDB=%s\n' '$(call npc_signature_bool,$(CONFIG_SDB))'; \
		printf 'CONFIG_TRACE=%s\n' '$(call npc_signature_bool,$(CONFIG_TRACE))'; \
		printf 'CONFIG_ITRACE=%s\n' '$(call npc_signature_bool,$(CONFIG_ITRACE))'; \
		printf 'CONFIG_ITRACE_WRITE_FILE=%s\n' '$(call npc_signature_bool,$(CONFIG_ITRACE_WRITE_FILE))'; \
		printf 'CONFIG_MTRACE=%s\n' '$(call npc_signature_bool,$(CONFIG_MTRACE))'; \
		printf 'CONFIG_FTRACE=%s\n' '$(call npc_signature_bool,$(CONFIG_FTRACE))'; \
		printf 'CONFIG_IRINGBUF=%s\n' '$(call npc_signature_bool,$(CONFIG_IRINGBUF))'; \
		printf 'CONFIG_WATCHPOINT=%s\n' '$(call npc_signature_bool,$(CONFIG_WATCHPOINT))'; \
		printf 'CONFIG_DIFFTEST=%s\n' '$(call npc_signature_bool,$(CONFIG_DIFFTEST))'; \
		printf 'CONFIG_PERF_SAVE=%s\n' '$(call npc_signature_bool,$(CONFIG_PERF_SAVE))'; \
		printf 'CONFIG_PERF_DIC_ARCH_COUNTER=%s\n' '$(call npc_signature_bool,$(CONFIG_PERF_DIC_ARCH_COUNTER))'; \
		printf 'CONFIG_PERF_RISCV_ARCH_PMU=%s\n' '$(call npc_signature_bool,$(CONFIG_PERF_RISCV_ARCH_PMU))'; \
		printf 'CONFIG_PERF_RISCV_ARCH_PMU_ZIHPM=%s\n' '$(call npc_signature_bool,$(CONFIG_PERF_RISCV_ARCH_PMU_ZIHPM))'; \
		printf 'CONFIG_PERF_RISCV_ARCH_PMU_SSCOFPMF=%s\n' '$(call npc_signature_bool,$(CONFIG_PERF_RISCV_ARCH_PMU_SSCOFPMF))'; \
		$(foreach v,$(sort $(filter CONFIG_PERF_DIC_ARCH_COUNTER% CONFIG_LOG_%,$(.VARIABLES))), \
			printf '%s=%s\n' '$(v)' $(call npc_signature_shq,$(strip $($(v))));) \
		printf 'CONFIG_SIM_BACKEND_VERILATOR=%s\n' '$(call npc_signature_bool,$(CONFIG_SIM_BACKEND_VERILATOR))'; \
		printf 'CONFIG_SIM_BACKEND_GSIM=%s\n' '$(call npc_signature_bool,$(CONFIG_SIM_BACKEND_GSIM))'; \
		printf 'CONFIG_NPC_MODE_FPGA_ONLY=%s\n' '$(call npc_signature_bool,$(CONFIG_NPC_MODE_FPGA_ONLY))'; \
		printf 'VERILATOR_OPT_FLAG=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_OPT_FLAG))); \
		printf 'VERILATOR_COMPILER=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_COMPILER))); \
		printf 'VERILATOR_WARN_FLAGS=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_WARN_FLAGS))); \
		printf 'VERILATOR_TRACE=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_TRACE))); \
		printf 'VERILATOR_PROF=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_PROF))); \
		printf 'VERILATOR_AUTOFLUSH=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_AUTOFLUSH))); \
		printf 'VERILATOR_ASSERT=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_ASSERT))); \
		printf 'VERILATOR_X_ASSIGN=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_X_ASSIGN))); \
		printf 'VERILATOR_X_INITIAL=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_X_INITIAL))); \
		printf 'VERILATOR_COVERAGE=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_COVERAGE))); \
		printf 'VERILATOR_OUTPUT_SPLIT=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_OUTPUT_SPLIT))); \
		printf 'VERILATOR_SAVABLE=%s\n' $(call npc_signature_shq,$(strip $(SAVABLE_VERILATOR_FLAG))); \
		printf 'VERILATOR_FLASH_XIP_DEFINE=%s\n' $(call npc_signature_shq,$(strip $(VERILATOR_FLASH_XIP_DEFINE))); \
		printf 'GSIM_CXX=%s\n' $(call npc_signature_shq,$(strip $(GSIM_CXX))); \
		printf 'GSIM_CXX_STD=%s\n' $(call npc_signature_shq,$(strip $(GSIM_CXX_STD))); \
		printf 'GSIM_OPT_FLAG=%s\n' $(call npc_signature_shq,$(strip $(GSIM_OPT_FLAG))); \
		printf 'GSIM_STDLIB_FLAG=%s\n' $(call npc_signature_shq,$(strip $(GSIM_STDLIB_FLAG))); \
		printf 'GSIM_LD_FLAG=%s\n' $(call npc_signature_shq,$(strip $(GSIM_LD_FLAG))); \
		printf 'GSIM_RUNTIME_FLAGS=%s\n' $(call npc_signature_shq,$(strip $(GSIM_RUNTIME_FLAGS))); \
		printf 'GSIM_WARN_FLAGS=%s\n' $(call npc_signature_shq,$(strip $(GSIM_WARN_FLAGS))); \
		printf 'GSIM_DEFINES=%s\n' $(call npc_signature_shq,$(strip $(GSIM_DEFINES))); \
		printf 'GSIM_COMMAND=%s\n' $(call npc_signature_shq,$(strip $(GSIM_COMMAND))); \
		printf 'GSIM_EXTRA_ARGS=%s\n' $(call npc_signature_shq,$(strip $(GSIM_EXTRA_ARGS))); \
		printf 'GSIM_CACHE_PADDING=%s\n' $(call npc_signature_shq,$(strip $(GSIM_CACHE_PADDING))); \
		printf 'GSIM_RAM_BYTES=%s\n' $(call npc_signature_shq,$(strip $(GSIM_RAM_BYTES))); \
		printf 'cc_version=%s\n' "$$cc_version"; \
		printf 'cxx_version=%s\n' "$$cxx_version"; \
		printf 'verilator_version=%s\n' "$$verilator_version"; \
		printf 'gsim_version=%s\n' "$$gsim_version"; \
	} > "$$inputs"; \
	sig="$$(LC_ALL=C sha256sum "$$inputs" | cut -d' ' -f1)"; \
	case "$$sig" in \
		''|*[!0-9a-f]*) \
			echo "错误：build signature 计算失败：$$sig" >&2; \
			exit 2 ;; \
	esac; \
	sig_dir="$(abspath $(BUILD_DIR))/$$config_hash/$$sig"; \
	mkdir -p "$$sig_dir"; \
	if test -f "$$sig_dir/build-signature.txt" && cmp -s "$$inputs" "$$sig_dir/build-signature.txt"; then \
		rm -f "$$inputs"; \
	else \
		mv -f "$$inputs" "$$sig_dir/build-signature.txt"; \
	fi; \
	trap - EXIT HUP INT TERM; \
	printf '%s\n' "$$sig" > "$$sig_dir/build-signature.hash"; \
	{ \
		printf '# generated from build inputs; do not edit\n'; \
		printf 'BUILD_SIGNATURE := %s\n' "$$sig"; \
	} > $@.tmp; \
	if test -f "$@" && cmp -s $@.tmp $@; then \
		rm -f $@.tmp; \
	else \
		mv -f $@.tmp $@; \
	fi

# 所有会写构建产物的目标都通过 config-hash-require 间接依赖本检查；如果
# included makefile 没有生成或内容非法，明确失败，绝不回退到固定 build/ 根。
config-buildsignature-require:
	@if [ -z "$(strip $(BUILD_SIGNATURE))" ]; then \
		echo "错误：无法确定 build signature。" >&2; \
		echo "      缺少或无法生成 $(BUILD_SIGNATURE_MK)。" >&2; \
		echo "      请检查编译器/Verilator/GSIM 是否可执行，或先运行 make npc-config。" >&2; \
		exit 2; \
	fi; \
	case "$(strip $(BUILD_SIGNATURE))" in \
		*[!0-9a-f]*) \
			echo "错误：build signature 不是十六进制字符串：$(strip $(BUILD_SIGNATURE))" >&2; \
			exit 2 ;; \
	esac
