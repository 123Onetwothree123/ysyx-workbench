ifndef VERILATOR_GENERATED_MK
$(error VERILATOR_GENERATED_MK is not set)
endif
ifndef NPC_CSRC_DIR
$(error NPC_CSRC_DIR is not set)
endif

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

NPC_IMPORT_HEADERS := $(shell grep -rhoP '(?<=import <)[^>]+' $(NPC_CSRC_DIR) --include='*.ixx' --include='*.cpp' 2>/dev/null | sort -u)

NPC_IXX_SRCS := \
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

.npc_build_signature: npc_build_signature_force
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
.npc_modules_built: $(NPC_HEADER_GCM) $(NPC_IXX_SRCS) $(STD_MODULE_SRC) .npc_top_header_signature .npc_build_signature
	@mkdir -p gcm.cache
	@echo "  CXX MODULE std"
	$(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) $(NPC_MODULE_DEP_FLAGS) -x c++ -c $(STD_MODULE_SRC) -o $(STD_MODULE_OBJ)
	@$(foreach src,$(NPC_IXX_SRCS),echo "  CXX MODULE $(notdir $(src))"; $(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) $(NPC_MODULE_DEP_FLAGS) -x c++ -c $(src) -o $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))) || exit 1;)
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

.npc_modules_built: $(STD_MODULE_OBJ) $(NPC_HEADER_PCM) $(NPC_IXX_SRCS) .npc_top_header_signature .npc_build_signature
	@$(foreach src,$(NPC_IXX_SRCS),\
		MOD_NAME=$$(grep -oP '(?<=export module )\S+(?=;)' $(src)); \
		echo "  CXX MODULE $(notdir $(src)) [$$MOD_NAME]"; \
		$(CXX) $(CPPFLAGS) $(NPC_STD_FLAG) $(NPC_CLANG_MODULE_FLAGS) -x c++-module --precompile $(src) -o $(NPC_PCM_DIR)/$$MOD_NAME.pcm || exit 1; \
		$(CXX) $(NPC_STD_FLAG) -c $(NPC_CLANG_MODULE_FLAGS) $(NPC_PCM_DIR)/$$MOD_NAME.pcm -o $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))) || exit 1; \
	)
	@touch $@

$(VK_USER_OBJS): .npc_modules_built
$(VK_USER_OBJS): private CPPFLAGS += $(NPC_STD_FLAG) $(NPC_CLANG_MODULE_FLAGS)

endif

LDFLAGS += $(NPC_MODULE_OBJS)
