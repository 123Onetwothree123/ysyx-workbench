NPC_HOME := $(CURDIR)
SHELL := /bin/bash
BUILD_DIR = ./build
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
VERILATOR=verilator
remove_quote = $(patsubst "%",%,$(1))
# Quote one make value as one POSIX-shell word.  This is used for generated
# performance metadata, where configuration strings may contain commas.
perf_shell_quote = '$(subst ','"'"',$(1))'
comma := ,
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

# 物理地址宽度：RV64 默认 64，RV32 默认 32；可在命令行显式覆盖。
ADDRESS_WIDTH ?= $(if $(filter 64,$(or $(CONFIG_XLEN),32)),64,32)
# ysyxSoC CPU 侧接口位宽独立于 CPU XLEN。FOLLOW_CPU=y 时跟随 CPU；
# 否则使用 SOC_XLEN（仅允许 32/64，由 elaboration 校验）。
SOC_XLEN_FOLLOW_CPU ?= $(or $(CONFIG_SOC_XLEN_FOLLOW_CPU),n)
SOC_XLEN ?= $(or $(CONFIG_SOC_XLEN),32)
SOC_XLEN_EFFECTIVE := $(if $(filter y,$(SOC_XLEN_FOLLOW_CPU)),$(or $(CONFIG_XLEN),32),$(SOC_XLEN))
CACHE_BLOCK_SIZE_LOG2 ?= $(or $(CONFIG_CACHE_BLOCK_SIZE_LOG2),4)
CACHE_INDEX_BITS ?= $(or $(CONFIG_CACHE_INDEX_BITS),5)
# 每级缓存可单独配置几何；未设置时跟随共享值（既有脚本零改动）
ICACHE_BLOCK_SIZE_LOG2 ?= $(or $(CONFIG_ICACHE_BLOCK_SIZE_LOG2),$(CACHE_BLOCK_SIZE_LOG2))
ICACHE_INDEX_BITS ?= $(or $(CONFIG_ICACHE_INDEX_BITS),$(CACHE_INDEX_BITS))
DCACHE_BLOCK_SIZE_LOG2 ?= $(or $(CONFIG_DCACHE_BLOCK_SIZE_LOG2),$(CACHE_BLOCK_SIZE_LOG2))
DCACHE_INDEX_BITS ?= $(or $(CONFIG_DCACHE_INDEX_BITS),$(CACHE_INDEX_BITS))
BTB_BITS ?= $(or $(CONFIG_BTB_BITS),4)
BTB_WAYS ?= $(or $(CONFIG_BTB_WAYS),1)
JAL_BTB_BITS ?= $(or $(CONFIG_JAL_BTB_BITS),4)
JAL_BTB_WAYS ?= $(or $(CONFIG_JAL_BTB_WAYS),1)
RAS_BITS ?= $(or $(CONFIG_RAS_BITS),4)
CACHE_PADDING ?= $(or $(CONFIG_CACHE_PADDING),0)
CHIPLINK ?= n
export CHIPLINK
# FPU 配置：Kconfig 是唯一真源，命令行同名变量可覆盖用于短测试。
# 未生成的 .config 保持 F/D=n、backend=none，与 Kconfig 默认一致。
FPU_IMPL ?= $(or $(call remove_quote,$(CONFIG_FPU_IMPL)),none)
FPU_FLEN ?= $(or $(CONFIG_FPU_FLEN),64)
FPU_PIPELINE ?= $(or $(CONFIG_FPU_PIPELINE),0)
FPU_FMA ?= $(or $(CONFIG_FPU_FMA),y)
FPU_DIVSQRT ?= $(or $(CONFIG_FPU_DIVSQRT),y)
FPU_ROUNDING ?= $(or $(CONFIG_FPU_ROUNDING),y)
FPU_FLAGS ?= $(or $(CONFIG_FPU_FLAGS),y)
FPU_HARDFLOAT_RECODE ?= $(or $(CONFIG_FPU_HARDFLOAT_RECODE),y)
FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING ?= $(or $(CONFIG_FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING),n)
FPU_FUDIAN_SHARED_DIVSQRT ?= $(or $(CONFIG_FPU_FUDIAN_SHARED_DIVSQRT),y)

# MMU/TLB profile：.config 是唯一真源，生成 JSON/C header/env 三个派生产物。
# RTL 尚未接入该 profile，本阶段只做配置与生成器。
MMU_PROFILE_JSON   := $(abspath $(BUILD_DIR)/generated/mmu_profile.json)
MMU_PROFILE_HEADER := $(abspath include/generated/mmu_profile.h)
MMU_PROFILE_ENV    := $(abspath $(BUILD_DIR)/generated/mmu_profile.env)
MMU_PROFILE_SCRIPT := $(abspath scripts/gen_mmu_profile.py)
MMU_PROFILE_DEPS   := .config $(MMU_PROFILE_SCRIPT)

$(MMU_PROFILE_JSON) $(MMU_PROFILE_HEADER) $(MMU_PROFILE_ENV): $(MMU_PROFILE_DEPS)
	@mkdir -p $(BUILD_DIR)/generated include/generated
	python3 scripts/check_kconfig_contract.py --config .config
	python3 $(MMU_PROFILE_SCRIPT) \
		--config .config \
		--json $(MMU_PROFILE_JSON) \
		--header $(MMU_PROFILE_HEADER) \
		--env $(MMU_PROFILE_ENV)

mmu-profile: $(MMU_PROFILE_JSON) $(MMU_PROFILE_HEADER) $(MMU_PROFILE_ENV)
	@cat $(MMU_PROFILE_ENV)

mmu-profile-check:
	python3 scripts/test_mmu_profile.py

# 配置契约：Kconfig 只能表达连续 range，这里补齐 XLEN/SOC_XLEN、
# PTE 大小、页大小 2 的幂以及 CUSTOM A/D 高位等离散约束。
KCONFIG_CONTRACT_SCRIPT := $(abspath scripts/check_kconfig_contract.py)
kconfig-contract-check:
	python3 $(KCONFIG_CONTRACT_SCRIPT) --defconfigs
	python3 scripts/test_kconfig_contract.py
	@if [ -f .config ]; then python3 $(KCONFIG_CONTRACT_SCRIPT) --config .config; fi

.PHONY: mmu-profile mmu-profile-check kconfig-contract-check
# npc 独立仿真路径的复位 PC：0x80000000 + CACHE_PADDING（镜像在内存中整体后移 CACHE_PADDING 字节）
NPC_RESET_PC := $(shell printf '0x%x' $$((0x80000000 + $(CACHE_PADDING))))
CACHE_PADDING_CFLAGS = -DCONFIG_CACHE_PADDING=$(CACHE_PADDING)

VERILOG_ENV = env \
	CACHE_BLOCK_SIZE_LOG2=$(CACHE_BLOCK_SIZE_LOG2) \
	CACHE_INDEX_BITS=$(CACHE_INDEX_BITS) \
	ICACHE_BLOCK_SIZE_LOG2=$(ICACHE_BLOCK_SIZE_LOG2) \
	ICACHE_INDEX_BITS=$(ICACHE_INDEX_BITS) \
	DCACHE_BLOCK_SIZE_LOG2=$(DCACHE_BLOCK_SIZE_LOG2) \
	DCACHE_INDEX_BITS=$(DCACHE_INDEX_BITS) \
	ICACHE=$(CONFIG_ICACHE) \
	DCACHE=$(CONFIG_DCACHE) \
	ICACHE_COHERENCE=$(or $(call remove_quote,$(CONFIG_ICACHE_COHERENCE)),fence_i) \
	DMA_COHERENCE=$(or $(call remove_quote,$(CONFIG_DMA_COHERENCE)),software_flush) \
	ICACHE_STORAGE=$(or $(call remove_quote,$(CONFIG_ICACHE_STORAGE)),reg) \
	DCACHE_STORAGE=$(or $(call remove_quote,$(CONFIG_DCACHE_STORAGE)),reg) \
	WBUF_DEPTH=$(CONFIG_WBUF_DEPTH) \
	CACHE_PADDING=$(CACHE_PADDING) \
	CHIPLINK=$(CHIPLINK) \
	XLEN=$(or $(CONFIG_XLEN),32) \
	SOC_XLEN_FOLLOW_CPU=$(SOC_XLEN_FOLLOW_CPU) \
	SOC_XLEN=$(SOC_XLEN) \
	ADDRESS_WIDTH=$(ADDRESS_WIDTH) \
	MMU_ENABLED=$(if $(filter y,$(CONFIG_HAS_MMU)),1,0) \
	MMU_TLB_ENABLED=$(if $(and $(filter y,$(CONFIG_HAS_TLB)),$(filter y,$(CONFIG_HAS_MMU))),1,0) \
	MMU_GEOMETRY_MODE=$(or $(call remove_quote,$(CONFIG_MMU_GEOMETRY_MODE)),standard) \
	MMU_CUSTOM_SATP_MODE=$(or $(CONFIG_MMU_CUSTOM_SATP_MODE),10) \
	MMU_PAGE_SIZE_BYTES=$(or $(CONFIG_MMU_PAGE_SIZE_BYTES),4096) \
	MMU_LEVELS=$(or $(CONFIG_MMU_LEVELS),2) \
	MMU_PTE_SIZE_BYTES=$(or $(CONFIG_MMU_PTE_SIZE_BYTES),4) \
	MMU_PAGE_TABLE_SIZE_BYTES=$(or $(CONFIG_MMU_PAGE_TABLE_SIZE_BYTES),4096) \
	MMU_PTE_FORMAT=$(or $(call remove_quote,$(CONFIG_MMU_PTE_FORMAT)),riscv32) \
	MMU_SUPERPAGE=$(or $(CONFIG_MMU_SUPERPAGE),n) \
	MMU_AD_MODE=$(or $(call remove_quote,$(CONFIG_MMU_AD_MODE)),fault) \
	MMU_CACHE_MODE=$(or $(call remove_quote,$(CONFIG_MMU_CACHE_MODE)),pipt) \
	MMU_CACHE_VIPT_ALIAS_SAFE=$(or $(CONFIG_MMU_CACHE_VIPT_ALIAS_SAFE),n) \
	MMU_CACHE_VIPT_ALIAS_SCAN_MODE=$(or $(call remove_quote,$(CONFIG_MMU_CACHE_VIPT_ALIAS_SCAN_MODE)),all_sets) \
	MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH=$(or $(CONFIG_MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH),4) \
	MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES=$(or $(CONFIG_MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES),0) \
	MMU_PTW_PATH=$(or $(call remove_quote,$(CONFIG_MMU_PTW_PATH)),dedicated) \
	MMU_PTW_ARCH=$(or $(call remove_quote,$(CONFIG_MMU_PTW_ARCH)),independent) \
	MMU_PTW_OUTSTANDING=$(or $(CONFIG_MMU_PTW_OUTSTANDING),2) \
	MMU_PTW_ORDER=$(or $(call remove_quote,$(CONFIG_MMU_PTW_ORDER)),in_order) \
	MMU_PTW_CACHE=$(or $(CONFIG_MMU_PTW_CACHE),n) \
	MMU_PTW_CACHE_ENTRIES=$(or $(CONFIG_MMU_PTW_CACHE_ENTRIES),16) \
	MMU_PTW_CACHE_WAYS=$(or $(CONFIG_MMU_PTW_CACHE_WAYS),2) \
	MMU_TLB_ORGANIZATION=$(or $(call remove_quote,$(CONFIG_TLB_ORGANIZATION)),split) \
	MMU_TLB_STORAGE=$(or $(call remove_quote,$(CONFIG_TLB_STORAGE)),reg) \
	MMU_TLB_REPLACEMENT=$(or $(call remove_quote,$(CONFIG_TLB_REPLACEMENT)),plru) \
	MMU_TLB_ASID_BITS=$(or $(CONFIG_TLB_ASID_BITS),9) \
	MMU_TLB_GLOBAL=$(or $(CONFIG_TLB_GLOBAL),y) \
	MMU_ITLB_ENTRIES=$(or $(CONFIG_ITLB_ENTRIES),16) \
	MMU_ITLB_WAYS=$(or $(CONFIG_ITLB_WAYS),2) \
	MMU_DTLB_ENTRIES=$(or $(CONFIG_DTLB_ENTRIES),32) \
	MMU_DTLB_WAYS=$(or $(CONFIG_DTLB_WAYS),4) \
	MMU_UTLB_ENTRIES=$(or $(CONFIG_UTLB_ENTRIES),32) \
	MMU_UTLB_WAYS=$(or $(CONFIG_UTLB_WAYS),4) \
	MMU_TLB_SUPERPAGE=$(or $(CONFIG_TLB_SUPERPAGE),n) \
	MMU_TLB_MISS_QUEUE_MODE=$(or $(call remove_quote,$(CONFIG_TLB_MISS_QUEUE_MODE)),follow_tlb) \
	MMU_ITLB_MISS_QUEUE_ENTRIES=$(or $(CONFIG_ITLB_MISS_QUEUE_ENTRIES),4) \
	MMU_DTLB_MISS_QUEUE_ENTRIES=$(or $(CONFIG_DTLB_MISS_QUEUE_ENTRIES),4) \
	MMU_UTLB_MISS_QUEUE_ENTRIES=$(or $(CONFIG_UTLB_MISS_QUEUE_ENTRIES),8) \
	MMU_GLOBAL_MISS_QUEUE_ENTRIES=$(or $(CONFIG_GLOBAL_MISS_QUEUE_ENTRIES),8) \
	MMU_TLB_REFILL_PORTS=$(or $(CONFIG_TLB_REFILL_PORTS),1) \
	$(shell cat $(MMU_PROFILE_ENV)) \
	BTB_BITS=$(BTB_BITS) \
	BTB_WAYS=$(BTB_WAYS) \
	JAL_BTB_BITS=$(JAL_BTB_BITS) \
	JAL_BTB_WAYS=$(JAL_BTB_WAYS) \
	RAS_BITS=$(RAS_BITS) \
	SDB=$(or $(CONFIG_SDB),n) \
	DIFFTEST=$(or $(CONFIG_DIFFTEST),n) \
	MTRACE=$(or $(CONFIG_MTRACE),n) \
	ITRACE=$(if $(filter y,$(CONFIG_ITRACE) $(CONFIG_ITRACE_WRITE_FILE)),y,n) \
	FTRACE=$(or $(CONFIG_FTRACE),n) \
	IRINGBUF=$(or $(CONFIG_IRINGBUF),n) \
	PERF_STATS=$(or $(CONFIG_PERF_STATS),n) \
	$(PERF_COUNTER_ENVS) \
	RISCV_M=$(or $(CONFIG_RISCV_M),$(RISCV_M),n) \
	RISCV_A=$(or $(CONFIG_RISCV_A),$(RISCV_A),n) \
	RISCV_C=$(or $(CONFIG_RISCV_C),$(RISCV_C),n) \
	RISCV_F=$(or $(CONFIG_RISCV_F),$(RISCV_F),n) \
	RISCV_D=$(or $(CONFIG_RISCV_D),$(RISCV_D),n) \
	FPU_IMPL=$(FPU_IMPL) \
	FPU_FLEN=$(FPU_FLEN) \
	FPU_PIPELINE=$(FPU_PIPELINE) \
	FPU_FMA=$(FPU_FMA) \
	FPU_DIVSQRT=$(FPU_DIVSQRT) \
	FPU_ROUNDING=$(FPU_ROUNDING) \
	FPU_FLAGS=$(FPU_FLAGS) \
	FPU_HARDFLOAT_RECODE=$(FPU_HARDFLOAT_RECODE) \
	FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING=$(FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING) \
	FPU_FUDIAN_SHARED_DIVSQRT=$(FPU_FUDIAN_SHARED_DIVSQRT) \
	MUL_IMPL=$(or $(call remove_quote,$(CONFIG_MUL_IMPL)),shift_add) \
	MUL_ENCODING=$(or $(call remove_quote,$(CONFIG_MUL_ENCODING)),plain) \
	MUL_RADIX=$(or $(CONFIG_MUL_RADIX),4) \
	MUL_WIDTH=$(or $(CONFIG_MUL_WIDTH),32) \
	MUL_COMPRESSOR_INPUTS=$(or $(CONFIG_MUL_COMPRESSOR_INPUTS),3) \
	MUL_COMPRESSOR_OUTPUTS=$(or $(CONFIG_MUL_COMPRESSOR_OUTPUTS),2) \
	MUL_COMPRESSOR_OUTPUT_OFFSETS=$(or $(call remove_quote,$(CONFIG_MUL_COMPRESSOR_OUTPUT_OFFSETS)),0$(comma)1) \
	MUL_COMPRESSOR_HAS_CIN=$(CONFIG_MUL_COMPRESSOR_HAS_CIN) \
	MUL_COMPRESSOR_HAS_COUT=$(CONFIG_MUL_COMPRESSOR_HAS_COUT) \
	MUL_COMPRESSOR_COUT_OFFSET=$(or $(CONFIG_MUL_COMPRESSOR_COUT_OFFSET),1) \
	MUL_COMPRESSOR_ALLOW_REDUNDANT=$(CONFIG_MUL_COMPRESSOR_ALLOW_REDUNDANT) \
	DIV_IMPL=$(or $(call remove_quote,$(CONFIG_DIV_IMPL)),restoring) \
	DIV_RADIX=$(or $(CONFIG_DIV_RADIX),2) \
	DIV_ITER_BITS=$(or $(CONFIG_DIV_ITER_BITS),1) \
	DIV_EARLY_OUT=$(CONFIG_DIV_EARLY_OUT) \
	MUL_ITER_BITS=$(or $(CONFIG_MUL_ITER_BITS),1) \
	MUL_PIPELINE=$(or $(CONFIG_MUL_PIPELINE),0) \
	MUL_SPLIT=$(or $(CONFIG_MUL_SPLIT),1) \
	MUL_EARLY_OUT=$(CONFIG_MUL_EARLY_OUT) \
	APB_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
	APB_DEVICE_FREQ_MHZ=$(APB_DEVICE_FREQ) \
	APB_DELAY_SCALE_FACTOR=$(APB_DELAY_SCALE) \
	AXI_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
	AXI_DEVICE_FREQ_MHZ=$(AXI_DEVICE_FREQ) \
	AXI_DELAY_SCALE_FACTOR=$(AXI_DELAY_SCALE)

# Keep the effective hardware knobs in one stable, human-readable record.
# The hash of this exact string identifies configurations even when two builds
# come from the same git commit.  Target selection is recorded separately:
# one elaboration emits both the direct-NPC and SoC CPU wrappers.
# 性能计数器：组 → 组内单计数器键。键名与Kconfig符号后缀、RTL端口后缀
# 以及C++宏名一一对应；单计数器同时受所属组总开关与PERF_STATS约束。
PERF_COUNTER_GROUPS := BASIC OPCLASS MDU IFU_STALL ICACHE DCACHE EXU_STALL LSU IDU_STALL TRAP
PERF_COUNTERS_BASIC := IFU_FETCH EXU_DONE LSU_LOAD LSU_STORE
PERF_COUNTERS_OPCLASS := ALU_OP MEM_OP CSR_OP BRANCH_OP JAL_OP JALR_OP EXECUTION_ACTIVE
PERF_COUNTERS_MDU := MDU_REQ MDU_DONE MDU_OP MDU_ACTIVE MDU_WAIT
PERF_COUNTERS_IFU_STALL := IFU_STALL_PIPELINE IFU_STALL_AXI IFU_STALL_AR IFU_STALL_R IFU_STALL_REDIRECT IFU_STALL_IDLE
PERF_COUNTERS_ICACHE := ICACHE_HIT ICACHE_MISS
PERF_COUNTERS_DCACHE := DCACHE_HIT DCACHE_MISS DCACHE_REFILL_REQ DCACHE_REFILL_RESP
PERF_COUNTERS_EXU_STALL := EXU_STALL_LSU EXU_IDLE_NOINPUT MEM_WAITSLOT
PERF_COUNTERS_LSU := LSU_ACTIVE LSU_LOAD_ACTIVE LSU_STORE_ACTIVE LSU_STALL_READ_AR LSU_STALL_READ_R LSU_STALL_WRITE_REQ LSU_STALL_WRITE_B
PERF_COUNTERS_IDU_STALL := IDU_STALL_RAW IDU_STALL_RAW_LOADUSE IDU_STALL_RAW_ALU
PERF_COUNTERS_TRAP := TRAP_COUNT
PERF_COUNTER_KEYS := $(foreach g,$(PERF_COUNTER_GROUPS),$(PERF_COUNTERS_$(g)))
# 组总开关是否打开（缺省按y，兼容未重新生成配置的旧工作区）。
perf_group_on = $(filter y,$(or $(CONFIG_PERF_COUNTER_$(1)),y))
# 单计数器是否生效：所属组与子开关都必须为y。
perf_counter_on = $(and $(call perf_group_on,$(2)),$(filter y,$(or $(CONFIG_PERF_COUNTER_$(1)),y)))
perf_counter_flag = $(if $(call perf_counter_on,$(1),$(2)),-DCONFIG_PERF_COUNTER_$(1),)
perf_counter_env = $(if $(call perf_counter_on,$(1),$(2)),y,n)
_perf_counter_empty :=
_perf_counter_space := $(_perf_counter_empty) $(_perf_counter_empty)
# 组开关与单计数器开关一起传给 elaboration。
PERF_COUNTER_ENVS = $(foreach g,$(PERF_COUNTER_GROUPS),PERF_COUNTER_$(g)=$(or $(CONFIG_PERF_COUNTER_$(g)),y)) $(foreach g,$(PERF_COUNTER_GROUPS),$(foreach k,$(PERF_COUNTERS_$(g)),PERF_COUNTER_$(k)=$(call perf_counter_env,$(k),$(g))))
# 构建身份摘要用的启用清单；主开关关闭为off，全部子计数器关闭为none。
PERF_COUNTER_KEYS_ENABLED = $(foreach g,$(PERF_COUNTER_GROUPS),$(foreach k,$(PERF_COUNTERS_$(g)),$(if $(call perf_counter_on,$(k),$(g)),$(k) ,)))
PERF_COUNTERS_SUMMARY = $(if $(filter y,$(or $(CONFIG_PERF_STATS),n)),$(if $(strip $(PERF_COUNTER_KEYS_ENABLED)),$(subst $(_perf_counter_space),+,$(strip $(PERF_COUNTER_KEYS_ENABLED))),none),off)
PERF_CONFIG_SUMMARY = ICACHE=$(or $(CONFIG_ICACHE),n);DCACHE=$(or $(CONFIG_DCACHE),n);CACHE_BLOCK_SIZE_LOG2=$(CACHE_BLOCK_SIZE_LOG2);CACHE_INDEX_BITS=$(CACHE_INDEX_BITS);ICACHE_BLOCK_SIZE_LOG2=$(ICACHE_BLOCK_SIZE_LOG2);ICACHE_INDEX_BITS=$(ICACHE_INDEX_BITS);DCACHE_BLOCK_SIZE_LOG2=$(DCACHE_BLOCK_SIZE_LOG2);DCACHE_INDEX_BITS=$(DCACHE_INDEX_BITS);ICACHE_STORAGE=$(or $(call remove_quote,$(CONFIG_ICACHE_STORAGE)),reg);DCACHE_STORAGE=$(or $(call remove_quote,$(CONFIG_DCACHE_STORAGE)),reg);ICACHE_COHERENCE=$(or $(call remove_quote,$(CONFIG_ICACHE_COHERENCE)),fence_i);DMA_COHERENCE=$(or $(call remove_quote,$(CONFIG_DMA_COHERENCE)),software_flush);WBUF_DEPTH=$(or $(CONFIG_WBUF_DEPTH),4);CACHE_PADDING=$(CACHE_PADDING);BTB_BITS=$(BTB_BITS);BTB_WAYS=$(BTB_WAYS);JAL_BTB_BITS=$(JAL_BTB_BITS);JAL_BTB_WAYS=$(JAL_BTB_WAYS);RAS_BITS=$(RAS_BITS);SDB=$(or $(CONFIG_SDB),n);DIFFTEST=$(or $(CONFIG_DIFFTEST),n);MTRACE=$(or $(CONFIG_MTRACE),n);ITRACE=$(or $(CONFIG_ITRACE),n);ITRACE_WRITE_FILE=$(or $(CONFIG_ITRACE_WRITE_FILE),n);FTRACE=$(or $(CONFIG_FTRACE),n);IRINGBUF=$(or $(CONFIG_IRINGBUF),n);PERF_STATS=$(or $(CONFIG_PERF_STATS),n);RISCV_M=$(or $(CONFIG_RISCV_M),$(RISCV_M),n);RISCV_A=$(or $(CONFIG_RISCV_A),$(RISCV_A),n);RISCV_C=$(or $(CONFIG_RISCV_C),$(RISCV_C),n);RISCV_F=$(or $(CONFIG_RISCV_F),n);RISCV_D=$(or $(CONFIG_RISCV_D),n);FPU_IMPL=$(FPU_IMPL);FPU_FLEN=$(FPU_FLEN);FPU_PIPELINE=$(FPU_PIPELINE);FPU_FMA=$(FPU_FMA);FPU_DIVSQRT=$(FPU_DIVSQRT);FPU_ROUNDING=$(FPU_ROUNDING);FPU_FLAGS=$(FPU_FLAGS);FPU_HARDFLOAT_RECODE=$(FPU_HARDFLOAT_RECODE);FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING=$(FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING);FPU_FUDIAN_SHARED_DIVSQRT=$(FPU_FUDIAN_SHARED_DIVSQRT);MUL_IMPL=$(or $(call remove_quote,$(CONFIG_MUL_IMPL)),shift_add);MUL_ENCODING=$(or $(call remove_quote,$(CONFIG_MUL_ENCODING)),plain);MUL_RADIX=$(or $(CONFIG_MUL_RADIX),4);MUL_WIDTH=$(or $(CONFIG_MUL_WIDTH),32);MUL_COMPRESSOR_INPUTS=$(or $(CONFIG_MUL_COMPRESSOR_INPUTS),3);MUL_COMPRESSOR_OUTPUTS=$(or $(CONFIG_MUL_COMPRESSOR_OUTPUTS),2);MUL_COMPRESSOR_OUTPUT_OFFSETS=$(or $(call remove_quote,$(CONFIG_MUL_COMPRESSOR_OUTPUT_OFFSETS)),0$(comma)1);MUL_COMPRESSOR_HAS_CIN=$(or $(CONFIG_MUL_COMPRESSOR_HAS_CIN),n);MUL_COMPRESSOR_HAS_COUT=$(or $(CONFIG_MUL_COMPRESSOR_HAS_COUT),n);MUL_COMPRESSOR_COUT_OFFSET=$(or $(CONFIG_MUL_COMPRESSOR_COUT_OFFSET),1);MUL_COMPRESSOR_ALLOW_REDUNDANT=$(or $(CONFIG_MUL_COMPRESSOR_ALLOW_REDUNDANT),n);DIV_IMPL=$(or $(call remove_quote,$(CONFIG_DIV_IMPL)),restoring);DIV_RADIX=$(or $(CONFIG_DIV_RADIX),2);DIV_ITER_BITS=$(or $(CONFIG_DIV_ITER_BITS),1);DIV_EARLY_OUT=$(or $(CONFIG_DIV_EARLY_OUT),n);MUL_ITER_BITS=$(or $(CONFIG_MUL_ITER_BITS),1);MUL_PIPELINE=$(or $(CONFIG_MUL_PIPELINE),0);MUL_SPLIT=$(or $(CONFIG_MUL_SPLIT),1);MUL_EARLY_OUT=$(or $(CONFIG_MUL_EARLY_OUT),n);CHIPLINK=$(or $(CHIPLINK),n);SOC_XLEN_FOLLOW_CPU=$(SOC_XLEN_FOLLOW_CPU);SOC_XLEN=$(SOC_XLEN);SOC_XLEN_EFFECTIVE=$(SOC_XLEN_EFFECTIVE);ADDRESS_WIDTH=$(ADDRESS_WIDTH);APB_CPU_FREQ_MHZ=$(SYNTH_FREQ);APB_DEVICE_FREQ_MHZ=$(APB_DEVICE_FREQ);APB_DELAY_SCALE_FACTOR=$(APB_DELAY_SCALE);AXI_CPU_FREQ_MHZ=$(SYNTH_FREQ);AXI_DEVICE_FREQ_MHZ=$(AXI_DEVICE_FREQ);AXI_DELAY_SCALE_FACTOR=$(AXI_DELAY_SCALE);SYNTH_STRATEGY_TYPE=$(SYNTH_STRATEGY_TYPE);SYNTH_STRATEGY_IDX=$(SYNTH_STRATEGY_IDX);SYNTH_NOMAP=$(SYNTH_NOMAP);SYNTH_NOABC=$(SYNTH_NOABC);PERF_COUNTERS=$(PERF_COUNTERS_SUMMARY)

verilog: mmu-profile
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	$(VERILOG_ENV) mill -i runMain ysyx_26030103_Elaborate --target-dir $(BUILD_DIR)
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
NVBOARD_CFLAGS = -DCONFIG_NVBOARD -DCONFIG_NVBOARD_UPDATE_INTERVAL=$(call remove_quote,$(CONFIG_NVBOARD_UPDATE_INTERVAL)) -I$(NVBOARD_HOME)/usr/include $(shell sdl2-config --cflags 2>/dev/null)
NVBOARD_LDADD = $(NVBOARD_ARCHIVE) $(shell sdl2-config --libs 2>/dev/null) -lSDL2_image -lSDL2_ttf
NVBOARD_LDFLAGS = $(addprefix -LDFLAGS ,$(NVBOARD_LDADD))
NVBOARD_DIRECT_GUARD = nvboard-direct-unsupported

.PHONY: nvboard-direct-unsupported
nvboard-direct-unsupported:
	@echo "错误：NVBoard 只能绑定 ysyxSoCFull 的外设引脚；direct-NPC 顶层没有可绑定的 GPIO/VGA/UART 引脚。请使用 SIM_TOP=soc。" >&2
	@exit 2
endif
ifeq ($(CONFIG_SDB),y)
SDB_CFLAGS = -DCONFIG_SDB
endif
ifeq ($(CONFIG_TRACE),y)
TRACE_CFLAGS = -DCONFIG_TRACE
endif
ifneq ($(CONFIG_TRACE_DEPTH),)
TRACE_CFLAGS += -DCONFIG_TRACE_DEPTH=$(call remove_quote,$(CONFIG_TRACE_DEPTH))
endif
ifeq ($(CONFIG_ITRACE),y)
ITRACE_CFLAGS = -DCONFIG_ITRACE
endif
ifeq ($(CONFIG_ITRACE_WRITE_FILE),y)
ITRACE_WRITE_FILE_CFLAGS = -DCONFIG_ITRACE_WRITE_FILE
endif
ifeq ($(CONFIG_MTRACE),y)
MTRACE_CFLAGS = -DCONFIG_MTRACE
endif
ifeq ($(CONFIG_FTRACE),y)
FTRACE_CFLAGS = -DCONFIG_FTRACE
endif
ifeq ($(CONFIG_IRINGBUF),y)
IRINGBUF_CFLAGS = -DCONFIG_IRINGBUF
ifneq ($(CONFIG_IRINGBUF_SIZE),)
IRINGBUF_CFLAGS += -DCONFIG_IRINGBUF_SIZE=$(call remove_quote,$(CONFIG_IRINGBUF_SIZE))
endif
endif
ifeq ($(CONFIG_WATCHPOINT),y)
WATCHPOINT_CFLAGS = -DCONFIG_WATCHPOINT
ifneq ($(CONFIG_WATCHPOINT_NUM),)
WATCHPOINT_CFLAGS += -DCONFIG_WATCHPOINT_NUM=$(call remove_quote,$(CONFIG_WATCHPOINT_NUM))
endif
endif
ifeq ($(CONFIG_DIFFTEST),y)
DIFFTEST_CFLAGS = -DCONFIG_DIFFTEST
endif
# 性能计数器：与Kconfig默认值一致，缺失的符号按y处理，尚未重新生成配置的
# 旧工作区保持与原行为一致。组宏与单计数器宏都会注入，单计数器宏要求组为y。
ifeq ($(CONFIG_PERF_STATS),y)
PERF_STATS_CFLAGS = -DCONFIG_PERF_STATS \
	$(foreach g,$(PERF_COUNTER_GROUPS),$(call perf_counter_flag,$(g),$(g))) \
	$(foreach g,$(PERF_COUNTER_GROUPS),$(foreach k,$(PERF_COUNTERS_$(g)),$(call perf_counter_flag,$(k),$(g))))
endif
ifeq ($(CONFIG_PERF_SAVE),y)
PERF_SAVE_CFLAGS = -DCONFIG_PERF_SAVE
PERF_SAVE_CFLAGS += -DCONFIG_PERF_BUILD_METADATA_FILE='\"$(PERF_BUILD_METADATA)\"'
PERF_SAVE_CFLAGS += -DCONFIG_PERF_SYNTH_FILE='\"$(PERF_SYNTH)\"'
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

.PHONY: perf-build-metadata
perf-build-metadata: verilog
	@set -eu; \
	mkdir -p "$(dir $(PERF_BUILD_METADATA))"; \
	tmp="$(PERF_BUILD_METADATA).tmp.$$$$"; \
	summary=$(call perf_shell_quote,$(PERF_CONFIG_SUMMARY)); \
	config_sha256=$$(printf '%s' "$$summary" | sha256sum | cut -d' ' -f1); \
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
# 向 C++ 侧传递唯一的 XLEN 配置：CPU XLEN 来自 Kconfig，SoC XLEN 是
# 已解析 FOLLOW_CPU 的有效值。csrc/Config.h 只消费这两个宏，不再生成
# 任何独立的 32/64 位第二开关。
NPC_XLEN := $(or $(CONFIG_XLEN),32)
NPC_SOC_XLEN := $(or $(SOC_XLEN_EFFECTIVE),32)
XLEN_CFLAGS = -DCONFIG_XLEN=$(NPC_XLEN) \
              -DCONFIG_SOC_XLEN=$(NPC_SOC_XLEN)
# 向C++侧传递M扩展开关
ifeq ($(or $(CONFIG_RISCV_M),$(RISCV_M)),y)
RISCM_CFLAGS = -DCONFIG_RISCV_M
endif
# 向C++侧传递指令缓存开关
ifeq ($(CONFIG_ICACHE),y)
ICACHE_CFLAGS = -DCONFIG_ICACHE
endif
# 向C++侧传递数据缓存开关
ifeq ($(CONFIG_DCACHE),y)
DCACHE_CFLAGS = -DCONFIG_DCACHE
endif
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
ifneq ($(CONFIG_LOG_LEVEL),)
LOG_CFLAGS = -DCONFIG_LOG_LEVEL=$(call remove_quote,$(CONFIG_LOG_LEVEL))
endif
ifeq ($(CONFIG_LOG_TO_FILE),y)
LOG_CFLAGS += -DCONFIG_LOG_TO_FILE
endif
ifneq ($(CONFIG_LOG_DIR),)
# 字符串字面值需要穿过两层 shell（make → verilator → 子 make → g++），故用 '\"..\"' 形式
LOG_CFLAGS += -DCONFIG_LOG_DIR='\"$(call remove_quote,$(CONFIG_LOG_DIR))\"'
endif
ifeq ($(CONFIG_LOG_TIMESTAMP),y)
LOG_CFLAGS += -DCONFIG_LOG_TIMESTAMP
endif
ifeq ($(CONFIG_LOG_SOURCE_LOCATION),y)
LOG_CFLAGS += -DCONFIG_LOG_SOURCE_LOCATION
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
SOC_MEMORY_CFLAGS = -DCONFIG_MBASE=0x30000000 -DCONFIG_MSIZE=0x10000000 -DCONFIG_RESET_PC=0x30000000
COMMON_INCFLAGS += $(addprefix -I, $(abspath $(CSRC_DIR)/difftest))
SHARED_CFLAGS += $(COMMON_INCFLAGS) $(XLEN_CFLAGS) $(SDB_CFLAGS) $(TRACE_CFLAGS) $(ITRACE_CFLAGS) $(ITRACE_WRITE_FILE_CFLAGS) $(MTRACE_CFLAGS) $(FTRACE_CFLAGS) $(IRINGBUF_CFLAGS) $(WATCHPOINT_CFLAGS) $(DIFFTEST_CFLAGS) $(PERF_STATS_CFLAGS) $(PERF_SAVE_CFLAGS) $(RISCM_CFLAGS) $(ICACHE_CFLAGS) $(DCACHE_CFLAGS) $(SOC_MEMORY_CFLAGS) $(PDK_CFLAGS) $(DEBUG_CFLAGS) $(LTO_CFLAGS) $(PGO_CFLAGS) $(ASAN_CFLAGS) $(UBSAN_CFLAGS) $(TSAN_CFLAGS) $(COVERAGE_CFLAGS) $(COLOR_CFLAGS) $(PIPE_CFLAGS) $(SPLIT_DWARF_CFLAGS) $(GC_SECTIONS_CFLAGS) $(VISIBILITY_CFLAGS) $(MARCH_CFLAGS) $(IPA_PTA_CFLAGS) $(PREFETCH_CFLAGS) $(GPROF_CFLAGS) $(TIME_REPORT_CFLAGS) $(TIMESTAMP_CFLAGS) $(LOG_CFLAGS) $(NO_OMIT_FP_CFLAGS) $(TRACE_VCD_CFLAGS) $(TRACE_FST_CFLAGS) $(TRACE_FILE_CFLAGS) $(CACHE_PADDING_CFLAGS) $(ICPX_NOSTDINCXX_CFLAGS) $(STDLIB_FLAG) $(HARDENING_CFLAGS) $(CC_OPT) $(NVBOARD_CFLAGS)
VERILATOR_CFLAGS = $(SHARED_CFLAGS) $(VERILATOR_CXX_STD_FLAG)
USER_CXXFLAGS = $(USER_CXX_STD_FLAG) $(MODULE_CFLAGS) $(WALL_CFLAGS) $(WERROR_CFLAGS) $(PEDANTIC_CFLAGS) $(WCONVERSION_CFLAGS)
#verilator的部分
VERILATOR_OPT_FLAG ?= -O3
# NPC 顶层比 ysyxSoC 的 CPU 黑盒多出直接仿真别名（如 perf_trap_count）；
# 这些别名在 SoC 实例中故意不连接，PINMISSING 是预期行为，不应让整个
# SoC 仿真构建失败。RV64 调试 `_hi` 端口已由 ysyxSoC 黑盒显式连接。
VERILATOR_WARN_FLAGS ?= -Wno-PINMISSING
ifneq ($(CONFIG_VERILATOR_OPT),)
VERILATOR_OPT_FLAG = $(call remove_quote,$(CONFIG_VERILATOR_OPT))
endif
ifeq ($(CONFIG_TRACE_VCD),y)
VERILATOR_TRACE = --trace
TRACE_VCD_CFLAGS = -DCONFIG_TRACE_VCD
endif
ifeq ($(CONFIG_TRACE_FST),y)
VERILATOR_TRACE = --trace-fst
TRACE_FST_CFLAGS = -DCONFIG_TRACE_FST
endif
ifneq ($(CONFIG_TRACE_DEPTH),)
TRACE_DEPTH_VAL := $(call remove_quote,$(CONFIG_TRACE_DEPTH))
ifneq ($(TRACE_DEPTH_VAL),0)
VERILATOR_TRACE += --trace-depth $(TRACE_DEPTH_VAL)
endif
endif
ifneq ($(CONFIG_TRACE_FILE),)
TRACE_FILE_CFLAGS = -DCONFIG_TRACE_FILE='\"$(call remove_quote,$(CONFIG_TRACE_FILE))\"'
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

simtest: $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) $(NVBOARD_AUTOBIND) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) $(NVBOARD_LDFLAGS) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'

# Always enter the SoC sub-build.  Its own config stamp and Scala dependencies
# decide whether regeneration is necessary; checking only for the final file
# here would otherwise reuse stale RTL after a CPU port or CHIPLINK change.
.PHONY: ysyxsoc-verilog-force
ysyxSoC-verilog-force:

$(YSYXSOC_FULL_V): ysyxsoc-verilog-force
	@echo "[perf] (re)building ysyxSoC ..."
	@$(MAKE) -C $(YSYXSOC_DIR) \
		CHIPLINK=$(CHIPLINK) \
		SOC_XLEN=$(SOC_XLEN_EFFECTIVE) \
		APB_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
		APB_DEVICE_FREQ_MHZ=$(APB_DEVICE_FREQ) \
		APB_DELAY_SCALE_FACTOR=$(APB_DELAY_SCALE) \
		AXI_CPU_FREQ_MHZ=$(SYNTH_FREQ) \
		AXI_DEVICE_FREQ_MHZ=$(AXI_DEVICE_FREQ) \
		AXI_DELAY_SCALE_FACTOR=$(AXI_DELAY_SCALE) \
		verilog

$(BIN): $(NPC_VERILOG_PREREQ) $(CSRCS) $(VERILATOR_BUILD_WRAPPER) $(NVBOARD_AUTOBIND) $(YSYXSOC_FULL_V)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(VINCDIRS)) \
		--top-module $(SOC_TOPNAME) \
		$(VSRCS) $(CSRCS) $(NVBOARD_AUTOBIND) \
		$(addprefix -CFLAGS , $(VERILATOR_CFLAGS)) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) $(NVBOARD_LDFLAGS) \
		--Mdir $(OBJ_DIR) \
		--exe -o $(abspath $(BIN))
	$(MAKE) -C $(OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(VERILATOR_GENERATED_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'
$(BIN_NPC): $(NVBOARD_DIRECT_GUARD) $(NPC_VERILOG_PREREQ) $(CSRCS) $(VERILATOR_BUILD_WRAPPER)
	$(VERILATOR) $(VERILATOR_COMPILER) -MMD -cc $(VERILATOR_THREADS) $(VERILATOR_OUTPUT_SPLIT) \
		$(VERILATOR_OPT_FLAG) $(VERILATOR_WARN_FLAGS) $(VERILATOR_X_ASSIGN) $(VERILATOR_X_INITIAL) $(VERILATOR_ASSERT) \
		--timescale "1ns/1ns" --no-timing \
		$(VERILATOR_TRACE) $(VERILATOR_PROF) $(VERILATOR_AUTOFLUSH) $(VERILATOR_COVERAGE) \
		$(addprefix -I,$(NPC_VINCDIRS)) \
		--top-module riscv32e_npc_SimTop \
		$(NPC_VSRCS) $(CSRCS) \
		$(addprefix -CFLAGS , $(filter-out -DCONFIG_MBASE=% -DCONFIG_MSIZE=% -DCONFIG_RESET_PC=%,$(VERILATOR_CFLAGS)) -DVRISCV32E_NPC -DCONFIG_RESET_PC=$(NPC_RESET_PC) -DCONFIG_MBASE=0x80000000 -DCONFIG_MSIZE=0x4000000) \
		-LDFLAGS -lcapstone -LDFLAGS -lreadline $(if $(DIFFTEST_CFLAGS),-LDFLAGS -ldl,) $(if $(LTO_CFLAGS),-LDFLAGS $(LTO_CFLAGS),) $(if $(PGO_CFLAGS),-LDFLAGS $(PGO_CFLAGS),) $(if $(ASAN_CFLAGS),-LDFLAGS $(ASAN_CFLAGS),) $(if $(UBSAN_CFLAGS),-LDFLAGS $(UBSAN_CFLAGS),) $(if $(TSAN_CFLAGS),-LDFLAGS $(TSAN_CFLAGS),) $(if $(COVERAGE_CFLAGS),-LDFLAGS $(COVERAGE_CFLAGS),) $(if $(GPROF_CFLAGS),-LDFLAGS $(GPROF_CFLAGS),) $(if $(STDLIB_FLAG),-LDFLAGS $(STDLIB_FLAG),) $(if $(CONFIG_STDLIB_LIBCXX),-LDFLAGS -lc++ -LDFLAGS -lc++abi,) $(if $(LD_FLAG),-LDFLAGS $(LD_FLAG),) $(if $(GC_SECTIONS_LDFLAGS),-LDFLAGS $(GC_SECTIONS_LDFLAGS),) $(if $(CONFIG_CC_STRIP),-LDFLAGS -s,) \
		--Mdir $(NPC_OBJ_DIR) \
		--exe -o $(abspath $(BIN_NPC))
	$(MAKE) -C $(NPC_OBJ_DIR) -f $(VERILATOR_BUILD_WRAPPER) -j $(NPROC) \
		VERILATOR_GENERATED_MK=$(NPC_VERILATOR_MK) \
		CXX=$(CXX) LINK=$(CXX) \
		NPC_USER_CXXFLAGS='$(USER_CXXFLAGS)' \
		NPC_CSRC_DIR='$(abspath $(CSRC_DIR))'

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
ifneq ($(CONFIG_SYNTH_FREQ),)
SYNTH_FREQ = $(call remove_quote,$(CONFIG_SYNTH_FREQ))
else
SYNTH_FREQ ?= 450
endif
ifneq ($(CONFIG_PDK),)
PDK_VALUE = $(call remove_quote,$(CONFIG_PDK))
else
PDK_VALUE = icsprout55
endif
PDK_CFLAGS = -DCONFIG_PDK=$(PDK_VALUE)

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

ifneq ($(CONFIG_APB_DEVICE_FREQ),)
APB_DEVICE_FREQ = $(call remove_quote,$(CONFIG_APB_DEVICE_FREQ))
else
APB_DEVICE_FREQ = 100
endif
ifneq ($(CONFIG_APB_DELAY_SCALE),)
APB_DELAY_SCALE = $(call remove_quote,$(CONFIG_APB_DELAY_SCALE))
else
APB_DELAY_SCALE = 64
endif
ifneq ($(CONFIG_AXI_DEVICE_FREQ),)
AXI_DEVICE_FREQ = $(call remove_quote,$(CONFIG_AXI_DEVICE_FREQ))
else
AXI_DEVICE_FREQ = 100
endif
ifneq ($(CONFIG_AXI_DELAY_SCALE),)
AXI_DELAY_SCALE = $(call remove_quote,$(CONFIG_AXI_DELAY_SCALE))
else
AXI_DELAY_SCALE = 64
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
