ifndef VERILATOR_GENERATED_MK
$(error VERILATOR_GENERATED_MK is not set)
endif
ifndef NPC_CSRC_DIR
$(error NPC_CSRC_DIR is not set)
endif

# 唯一 C++ 配置模块。它的内容也进入构建签名，切换 CONFIG_XLEN/SOC_XLEN
# 或修改派生类型时，模块图不会复用旧 BMI。模块由顶层 Makefile 从 .config
# 生成；这里只负责把它放在模块依赖链最前面。
NPC_CONFIG_MODULE := $(NPC_CSRC_DIR)/generated/npc_config.ixx
NPC_CONFIG_JSON := $(abspath $(NPC_CSRC_DIR)/../include/generated/npc_config.json)
# 顶层 Makefile 会把叠加了 Makefile/命令行覆盖的 effective JSON 路径传进来。
# 此时模块已经由顶层按同一份 JSON 生成，子 make 只复用，绝不再从原始
# Kconfig JSON 重新生成出与 Scala 不同的值。
NPC_CONFIG_EFFECTIVE_JSON ?=
ifneq ($(wildcard $(NPC_CONFIG_EFFECTIVE_JSON)),)
NPC_CONFIG_EXTERNAL_MODULE := 1
endif
NPC_CONFIG_GEN_SRC := $(abspath $(NPC_CSRC_DIR)/../tools/npc_config_gen.cpp)
NPC_CONFIG_GEN_BIN := $(abspath $(NPC_CSRC_DIR)/../tools/build/npc_config_gen)
NPC_SAVABLE ?= n
NPC_CONFIG_OVERRIDE_ARGS := $(foreach item,$(filter CONFIG_%,$(MAKEOVERRIDES)),--set '$(item)')

# Remember the wrapper itself before including Verilator's generated makefiles.
# Its content is part of the build signature, so changes to the custom module
# rules cannot silently reuse artifacts produced by an older version.
NPC_BUILD_WRAPPER := $(abspath $(firstword $(MAKEFILE_LIST)))

include $(VERILATOR_GENERATED_MK)

ifneq ($(filter clang% icpx%,$(notdir $(CXX))),)
NPC_COMPILER := clang
else
NPC_COMPILER := gcc
endif

# 拉 Kconfig 配置以选择标准库
NPC_AUTO_CONF := $(NPC_CSRC_DIR)/../include/config/auto.conf
-include $(NPC_AUTO_CONF)

# 标准库模块源: clang+libc++ → libc++ 自带 std.cppm; 其余 → libstdc++ 的 bits/std.cc
ifeq ($(NPC_COMPILER)-$(CONFIG_STDLIB_LIBCXX),clang-y)
NPC_STD_MODULE_SRC := /usr/share/libc++/v1/std.cppm
NPC_STDLIB_FLAG := -stdlib=libc++
else
NPC_STD_MODULE_SRC := $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))
NPC_STDLIB_FLAG :=
endif

STD_MODULE_SRC := $(NPC_STD_MODULE_SRC)

STD_MODULE_OBJ := std_module.o

# 顶层 Makefile 通常已经生成 JSON/模块；这些规则让直接进入 obj_dir 的
# 构建也能自举。Kconfig 的 c_conf 是唯一的 .config 解析器。
$(NPC_CONFIG_JSON): $(NPC_CSRC_DIR)/../.config $(NPC_CSRC_DIR)/../tools/build/c_conf
	cd $(NPC_CSRC_DIR)/.. && ./tools/build/c_conf -s --syncconfig Kconfig

$(NPC_CONFIG_GEN_BIN): $(NPC_CONFIG_GEN_SRC)
	@mkdir -p $(@D)
	$(CXX) -std=c++23 -O2 -Wall -Wextra -o $@ $<

ifeq ($(NPC_CONFIG_EXTERNAL_MODULE),1)
$(NPC_CONFIG_MODULE): $(NPC_CONFIG_EFFECTIVE_JSON)
	@test -f $@ || { \
		echo "错误：顶层 Makefile 未生成 $(NPC_CONFIG_MODULE)" >&2; \
		exit 1; \
	}
else
$(NPC_CONFIG_MODULE): $(NPC_CONFIG_JSON) $(NPC_CONFIG_GEN_BIN)
	@mkdir -p $(@D)
	$(NPC_CONFIG_GEN_BIN) \
		--config-json $(NPC_CONFIG_JSON) \
		--savable $(NPC_SAVABLE) \
		$(NPC_CONFIG_OVERRIDE_ARGS) \
		--output $@
endif

NPC_IMPORT_HEADERS := $(shell grep -rhoP '(?<=import <)[^>]+' $(NPC_CSRC_DIR) --include='*.ixx' --include='*.cpp' 2>/dev/null | sort -u)

NPC_IXX_SRCS := \
  $(NPC_CONFIG_MODULE) \
  $(NPC_CSRC_DIR)/log/log.ixx \
  $(NPC_CSRC_DIR)/unicode.ixx \
  $(NPC_CSRC_DIR)/PerfStats.ixx \
  $(NPC_CSRC_DIR)/NPCTrap.ixx \
  $(NPC_CSRC_DIR)/CLIOptions.ixx \
  $(NPC_CSRC_DIR)/SoCMemoryMap/AddressRange.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommandResult.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommandUsage.ixx \
  $(NPC_CSRC_DIR)/sdb/EvaluationContext.ixx \
  $(NPC_CSRC_DIR)/sdb/RegisterName.ixx \
  $(NPC_CSRC_DIR)/sdb/TablePrinter.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommandUtils.ixx \
  $(NPC_CSRC_DIR)/trace/RecordInstruction.ixx \
  $(NPC_CSRC_DIR)/trace/ElfFunctionSymbol.ixx \
  $(NPC_CSRC_DIR)/trace/FtraceEvent.ixx \
  $(NPC_CSRC_DIR)/trace/FtraceFrame.ixx \
  $(NPC_CSRC_DIR)/trace/ReadelfFunction.ixx \
  $(NPC_CSRC_DIR)/trace/mtrace.ixx \
  $(NPC_CSRC_DIR)/sdb/readline.ixx \
  $(NPC_CSRC_DIR)/trace/capstone.ixx \
  $(NPC_CSRC_DIR)/trace/disasm.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/token.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/ExpressionError.ixx \
  $(NPC_CSRC_DIR)/ysyxSoC/ysyxSoC.ixx \
  $(NPC_CSRC_DIR)/sdb/command/Watchpoint.ixx \
  $(NPC_CSRC_DIR)/SoCMemoryMap/SoCMemoryMap.ixx \
  $(NPC_CSRC_DIR)/trace/iringbuf.ixx \
  $(NPC_CSRC_DIR)/trace/readelf.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/ASTNode.ixx \
  $(NPC_CSRC_DIR)/ImageLoader.ixx \
  $(NPC_CSRC_DIR)/DUT.ixx \
  $(NPC_CSRC_DIR)/difftest/DifftestCPUState.ixx \
  $(NPC_CSRC_DIR)/trace/itrace.ixx \
  $(NPC_CSRC_DIR)/trace/ftrace.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/NumberNode.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/BinaryOpNode.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/DereferenceNode.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/RegisterNode.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/UnaryMinusNode.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/ParenthesizedNode.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/lexer.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommandContext.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/NPCEvaluationContext.ixx \
  $(NPC_CSRC_DIR)/difftest/difftest.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/parser.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/expressions.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommandRegistry.ixx \
  $(NPC_CSRC_DIR)/sdb/command/WatchpointPool.ixx \
  $(NPC_CSRC_DIR)/sdb/command/helpCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/historyCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/cCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/clearCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/dCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/ftraceCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/infoCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/iringbufCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/pCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/qCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/readelfCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/siCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/wCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/xCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/sdb.ixx \
  $(NPC_CSRC_DIR)/NPCSimResult/NPCSimResult.ixx \
  $(NPC_CSRC_DIR)/npc.ixx

NPC_MODULE_OBJS := $(STD_MODULE_OBJ) $(foreach src,$(NPC_IXX_SRCS),$(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))))

# Compiler options are command-line inputs rather than ordinary makefile
# prerequisites.  Without a content stamp, switching Kconfig options,
# compiler versions, language standards, sanitizers, PGO/LTO modes, or link
# options can silently reuse incompatible objects and BMIs.  Re-evaluate this
# target on every invocation, but preserve its mtime when every input is
# unchanged.  Downstream targets therefore rebuild exactly when the signature
# content changes instead of on every sub-make.
.PHONY: npc_build_signature_force
npc_build_signature_force:

# Quote arbitrary make-variable text as one POSIX-shell argument.  In
# particular, this keeps macro values such as -DNAME=\"value\" literal and
# avoids parse-time writes (so `make -n` remains side-effect free).
npc_shell_quote = '$(subst ','"'"',$(1))'

.npc_build_signature: $(NPC_CONFIG_MODULE) npc_build_signature_force
	@set -eu; \
	 tmp=$@.tmp; \
	 cxx_frontend=$$(command -v '$(firstword $(CXX))' 2>/dev/null || printf '%s' unavailable); \
	 cxx_driver=$$(command -v '$(lastword $(CXX))' 2>/dev/null || printf '%s' unavailable); \
	 link_frontend=$$(command -v '$(firstword $(LINK))' 2>/dev/null || printf '%s' unavailable); \
	 link_driver=$$(command -v '$(lastword $(LINK))' 2>/dev/null || printf '%s' unavailable); \
	 { \
	   printf '%s\n' \
	     $(call npc_shell_quote,format=2) \
	     $(call npc_shell_quote,CXX=$(strip $(CXX))) \
	     $(call npc_shell_quote,LINK=$(strip $(LINK))) \
	     $(call npc_shell_quote,OBJCACHE=$(strip $(OBJCACHE))) \
	     $(call npc_shell_quote,CPPFLAGS=$(strip $(CPPFLAGS))) \
	     $(call npc_shell_quote,CXXFLAGS=$(strip $(CXXFLAGS))) \
	     $(call npc_shell_quote,NPC_USER_CXXFLAGS=$(strip $(NPC_USER_CXXFLAGS))) \
	     $(call npc_shell_quote,OPT=$(strip $(OPT))) \
	     $(call npc_shell_quote,OPT_FAST=$(strip $(OPT_FAST))) \
	     $(call npc_shell_quote,OPT_SLOW=$(strip $(OPT_SLOW))) \
	     $(call npc_shell_quote,CFG_CXXFLAGS_STD=$(strip $(CFG_CXXFLAGS_STD))) \
	     $(call npc_shell_quote,NPC_COMPILER=$(strip $(NPC_COMPILER))) \
	     $(call npc_shell_quote,NPC_STD_MODULE_SRC=$(strip $(NPC_STD_MODULE_SRC))) \
	     $(call npc_shell_quote,NPC_STDLIB_FLAG=$(strip $(NPC_STDLIB_FLAG))) \
	     $(call npc_shell_quote,NPC_MODULE_DEP_FLAGS=$(strip $(NPC_MODULE_DEP_FLAGS))) \
	     $(call npc_shell_quote,CONFIG_STDLIB_LIBCXX=$(strip $(CONFIG_STDLIB_LIBCXX))) \
	     $(call npc_shell_quote,VM_USER_CFLAGS=$(strip $(VM_USER_CFLAGS))) \
	     $(call npc_shell_quote,LDFLAGS=$(strip $(LDFLAGS))) \
	     $(call npc_shell_quote,LOADLIBES=$(strip $(LOADLIBES))) \
	     $(call npc_shell_quote,LDLIBS=$(strip $(LDLIBS))) \
	     $(call npc_shell_quote,LIBS=$(strip $(LIBS))) \
	     $(call npc_shell_quote,SC_LIBS=$(strip $(SC_LIBS))) \
	     $(call npc_shell_quote,VM_USER_LDFLAGS=$(strip $(VM_USER_LDFLAGS))) \
	     $(call npc_shell_quote,VM_USER_LDLIBS=$(strip $(VM_USER_LDLIBS))); \
	   printf 'CXX_FRONTEND=%s\nCXX_DRIVER=%s\n' "$$cxx_frontend" "$$cxx_driver"; \
	   printf '%s\n' 'CXX_VERSION_BEGIN'; \
	   $(CXX) --version 2>&1; \
	   printf '%s\n' 'CXX_VERSION_END'; \
	   printf 'LINK_FRONTEND=%s\nLINK_DRIVER=%s\n' "$$link_frontend" "$$link_driver"; \
	   printf '%s\n' 'LINK_VERSION_BEGIN'; \
	   $(LINK) --version 2>&1; \
	   printf '%s\n' 'LINK_VERSION_END'; \
	   printf 'WRAPPER_SHA256='; sha256sum '$(NPC_BUILD_WRAPPER)' | cut -d' ' -f1; \
	   printf 'CONFIG_MODULE_SHA256='; sha256sum '$(NPC_CONFIG_MODULE)' | cut -d' ' -f1; \
	   printf 'AUTO_CONF_SHA256='; \
	   if test -f '$(NPC_AUTO_CONF)'; then sha256sum '$(NPC_AUTO_CONF)' | cut -d' ' -f1; else printf '%s\n' missing; fi; \
	 } > "$$tmp"; \
	 if test -f $@ && cmp -s "$$tmp" $@; then \
	   rm -f "$$tmp"; \
	 else \
	   mv -f "$$tmp" $@; \
	 fi

# Verilator rewrites its public header on every invocation even when its
# contents are unchanged.  Track a content signature so that the expensive
# C++ module graph is invalidated only when the actual top-level API changes.
.npc_top_header_signature: $(VM_PREFIX).h
	@new_sig=$$(sha256sum $< | cut -d' ' -f1); \
	 old_sig=$$(test -f $@ && sed -n '1p' $@ || true); \
	 if test "$$new_sig" != "$$old_sig"; then printf '%s\n' "$$new_sig" > $@; fi

# ============ GCC module compilation ============
ifeq ($(NPC_COMPILER),gcc)

# GCC's default module dependency output introduces phony `*.c++-module`
# aliases.  Since those aliases never exist as files, every importer is then
# considered out of date on every make.  The module stamp below is the real,
# content-aware dependency, so retain normal header dependencies while
# suppressing those always-dirty module aliases.
NPC_MODULE_DEP_FLAGS := -Mno-modules

# One-time compatibility for dependency files produced before
# -Mno-modules was enabled.  Their imported named-module aliases may no
# longer be defined by freshly regenerated interface .d files; defining the
# accumulated aliases here lets make rebuild those importers once, after
# which their dependency files no longer contain the aliases.
ifneq ($(strip $(CXX_IMPORTS)),)
.PHONY: $(CXX_IMPORTS)
$(CXX_IMPORTS):
endif

# Compile import <xxx>; headers as header units
NPC_HEADER_GCM := $(foreach h,$(NPC_IMPORT_HEADERS),gcm.cache/$(h).gcm)
$(NPC_HEADER_GCM): gcm.cache/%.gcm: .npc_build_signature
	@mkdir -p gcm.cache
	@echo "  CXX HEADER <$*>"
	$(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) $(NPC_MODULE_DEP_FLAGS) -x c++-system-header $*

# DUT.ixx imports the generated top class.  Rebuild the complete module graph
# whenever Verilator changes that public header; otherwise GCC can silently
# reuse an old npc.DUT BMI and report newly-added RTL ports as nonexistent.
#
# 每个模块对象使用独立规则编译。原先整张清单展开进一条shell命令，编译标志
# 增多（例如按需性能计数器宏）后整行会超过单个参数128KiB上限，触发
# "Argument list too long"。逐对象规则同时天然获得增量编译；顺序用前置依赖
# 链保持：GCC的模块可见性要求导入者晚于被导入者构建。
npc_module_obj = $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(1)))
define npc_module_chain
$(if $(wordlist 2,$(words $(1)),$(1)),$(word 2,$(1)): $(word 1,$(1))
$(call npc_module_chain,$(wordlist 2,$(words $(1)),$(1))))
endef
NPC_MODULE_OBJS_IXX := $(foreach src,$(NPC_IXX_SRCS),$(call npc_module_obj,$(src)))
$(eval $(call npc_module_chain,$(STD_MODULE_OBJ) $(NPC_MODULE_OBJS_IXX)))

$(STD_MODULE_OBJ): $(STD_MODULE_SRC) .npc_build_signature
	@mkdir -p gcm.cache
	@echo "  CXX MODULE std"
	$(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) $(NPC_MODULE_DEP_FLAGS) -x c++ -c $< -o $@

define NPC_GCC_MODULE_RULE
$(call npc_module_obj,$(1)): $(1) $(NPC_CONFIG_MODULE) .npc_build_signature .npc_top_header_signature $(NPC_HEADER_GCM)
	@echo "  CXX MODULE $(notdir $(1))"
	$(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) $(NPC_MODULE_DEP_FLAGS) -x c++ -c $(1) -o $(call npc_module_obj,$(1))
endef
$(foreach src,$(NPC_IXX_SRCS),$(eval $(call NPC_GCC_MODULE_RULE,$(src))))

.npc_modules_built: $(STD_MODULE_OBJ) $(NPC_MODULE_OBJS_IXX) $(NPC_HEADER_GCM) $(NPC_IXX_SRCS) $(NPC_CONFIG_MODULE) $(STD_MODULE_SRC) .npc_top_header_signature .npc_build_signature
	@mkdir -p gcm.cache
	@touch $@

$(VK_USER_OBJS): .npc_modules_built
$(VK_USER_OBJS): private CPPFLAGS += $(NPC_USER_CXXFLAGS) $(NPC_MODULE_DEP_FLAGS)

endif

# ============ Clang module compilation ============
ifeq ($(NPC_COMPILER),clang)

NPC_PCM_DIR := $(CURDIR)/pcm_cache
NPC_STD_FLAG := $(lastword $(filter -std=%,$(CPPFLAGS) $(NPC_USER_CXXFLAGS)))
NPC_CLANG_MODULE_FLAGS := -fprebuilt-module-path=$(NPC_PCM_DIR) -Wno-reserved-module-identifier

$(NPC_PCM_DIR)/std.pcm: $(STD_MODULE_SRC) .npc_build_signature
	@mkdir -p $(NPC_PCM_DIR)
	$(CXX) $(CPPFLAGS) $(NPC_STD_FLAG) $(NPC_STDLIB_FLAG) -x c++-module --precompile $< -o $@

$(STD_MODULE_OBJ): $(NPC_PCM_DIR)/std.pcm
	@$(CXX) $(NPC_STD_FLAG) $(NPC_STDLIB_FLAG) -c $< -o $@

# Compile import <xxx>; headers as header units
NPC_HEADER_PCM := $(foreach h,$(NPC_IMPORT_HEADERS),$(NPC_PCM_DIR)/$(subst /,_,$(h)).pcm)
$(NPC_HEADER_PCM): $(NPC_PCM_DIR)/%.pcm: .npc_build_signature
	@mkdir -p $(NPC_PCM_DIR)
	@echo "  CXX HEADER <$(subst _,/,$*)>"
	$(CXX) $(NPC_STD_FLAG) -x c++-system-header $(subst _,/,$*) --precompile -o $@

# 与GCC分支同理：逐模块规则，避免把整张清单塞进一条shell命令。
# clang的pcm文件名由源码路径唯一决定，使用显式规则，不会与上面的
# 头文件pcm模式规则冲突。
npc_module_obj = $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(1)))
npc_module_pcm = $(NPC_PCM_DIR)/$(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.pcm,$(1)))
define npc_module_chain
$(if $(wordlist 2,$(words $(1)),$(1)),$(word 2,$(1)): $(word 1,$(1))
$(call npc_module_chain,$(wordlist 2,$(words $(1)),$(1))))
endef
NPC_MODULE_OBJS_IXX := $(foreach src,$(NPC_IXX_SRCS),$(call npc_module_obj,$(src)))
NPC_MODULE_PCMS := $(foreach src,$(NPC_IXX_SRCS),$(call npc_module_pcm,$(src)))
$(eval $(call npc_module_chain,$(NPC_MODULE_PCMS)))
$(eval $(call npc_module_chain,$(NPC_MODULE_OBJS_IXX)))

define NPC_CLANG_MODULE_RULE
$(call npc_module_pcm,$(1)): $(1) .npc_build_signature
	@mkdir -p $(NPC_PCM_DIR)
	@echo "  CXX MODULE $(notdir $(1))"
	$(CXX) $(CPPFLAGS) $(NPC_STD_FLAG) $(NPC_CLANG_MODULE_FLAGS) -x c++-module --precompile $(1) -o $(call npc_module_pcm,$(1))

$(call npc_module_obj,$(1)): $(call npc_module_pcm,$(1))
	@echo "  CXX MODULE OBJECT $(notdir $(1))"
	$(CXX) $(NPC_STD_FLAG) -c $(NPC_CLANG_MODULE_FLAGS) $(call npc_module_pcm,$(1)) -o $(call npc_module_obj,$(1))
endef
$(foreach src,$(NPC_IXX_SRCS),$(eval $(call NPC_CLANG_MODULE_RULE,$(src))))

.npc_modules_built: $(STD_MODULE_OBJ) $(NPC_MODULE_PCMS) $(NPC_MODULE_OBJS_IXX) $(NPC_HEADER_PCM) $(NPC_IXX_SRCS) .npc_top_header_signature .npc_build_signature
	@touch $@

$(VK_USER_OBJS): .npc_modules_built
$(VK_USER_OBJS): private CPPFLAGS += $(NPC_STD_FLAG) $(NPC_CLANG_MODULE_FLAGS)

endif

LDFLAGS += $(NPC_MODULE_OBJS)
