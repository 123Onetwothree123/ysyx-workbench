ifndef VERILATOR_GENERATED_MK
$(error VERILATOR_GENERATED_MK is not set)
endif
ifndef NPC_CSRC_DIR
$(error NPC_CSRC_DIR is not set)
endif

include $(VERILATOR_GENERATED_MK)

ifneq ($(filter clang% icpx%,$(notdir $(CXX))),)
NPC_COMPILER := clang
else
NPC_COMPILER := gcc
endif

# 拉 Kconfig 配置以选择标准库
-include $(NPC_CSRC_DIR)/../include/config/auto.conf

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

# ============ GCC module compilation ============
ifeq ($(NPC_COMPILER),gcc)

# Compile import <xxx>; headers as header units
NPC_HEADER_GCM := $(foreach h,$(NPC_IMPORT_HEADERS),gcm.cache/$(h).gcm)
$(NPC_HEADER_GCM): gcm.cache/%.gcm:
	@mkdir -p gcm.cache
	@echo "  CXX HEADER <$*>"
	$(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) -x c++-system-header $*

.npc_modules_built: $(NPC_HEADER_GCM) $(NPC_IXX_SRCS)
	@mkdir -p gcm.cache
	@echo "  CXX MODULE std"
	$(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) -x c++ -c $(STD_MODULE_SRC) -o $(STD_MODULE_OBJ)
	@$(foreach src,$(NPC_IXX_SRCS),echo "  CXX MODULE $(notdir $(src))"; $(CXX) $(CPPFLAGS) $(NPC_USER_CXXFLAGS) -x c++ -c $(src) -o $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))) || exit 1;)
	@touch $@

$(VK_USER_OBJS): | .npc_modules_built
$(VK_USER_OBJS): private CPPFLAGS += $(NPC_USER_CXXFLAGS)

endif

# ============ Clang module compilation ============
ifeq ($(NPC_COMPILER),clang)

NPC_PCM_DIR := $(CURDIR)/pcm_cache
NPC_STD_FLAG := $(lastword $(filter -std=%,$(CPPFLAGS) $(NPC_USER_CXXFLAGS)))
NPC_CLANG_MODULE_FLAGS := -fprebuilt-module-path=$(NPC_PCM_DIR) -Wno-reserved-module-identifier

$(NPC_PCM_DIR)/std.pcm: $(STD_MODULE_SRC)
	@mkdir -p $(NPC_PCM_DIR)
	$(CXX) $(CPPFLAGS) $(NPC_STD_FLAG) $(NPC_STDLIB_FLAG) -x c++-module --precompile $< -o $@

$(STD_MODULE_OBJ): $(NPC_PCM_DIR)/std.pcm
	@$(CXX) $(NPC_STD_FLAG) $(NPC_STDLIB_FLAG) -c $< -o $@

# Compile import <xxx>; headers as header units
NPC_HEADER_PCM := $(foreach h,$(NPC_IMPORT_HEADERS),$(NPC_PCM_DIR)/$(subst /,_,$(h)).pcm)
$(NPC_HEADER_PCM): $(NPC_PCM_DIR)/%.pcm:
	@mkdir -p $(NPC_PCM_DIR)
	@echo "  CXX HEADER <$(subst _,/,$*)>"
	$(CXX) $(NPC_STD_FLAG) -x c++-system-header $(subst _,/,$*) --precompile -o $@

.npc_modules_built: $(STD_MODULE_OBJ) $(NPC_HEADER_PCM) $(NPC_IXX_SRCS)
	@$(foreach src,$(NPC_IXX_SRCS),\
		MOD_NAME=$$(grep -oP '(?<=export module )\S+(?=;)' $(src)); \
		echo "  CXX MODULE $(notdir $(src)) [$$MOD_NAME]"; \
		$(CXX) $(CPPFLAGS) $(NPC_STD_FLAG) $(NPC_CLANG_MODULE_FLAGS) -x c++-module --precompile $(src) -o $(NPC_PCM_DIR)/$$MOD_NAME.pcm || exit 1; \
		$(CXX) $(NPC_STD_FLAG) -c $(NPC_CLANG_MODULE_FLAGS) $(NPC_PCM_DIR)/$$MOD_NAME.pcm -o $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))) || exit 1; \
	)
	@touch $@

$(VK_USER_OBJS): | .npc_modules_built
$(VK_USER_OBJS): private CPPFLAGS += $(NPC_STD_FLAG) $(NPC_CLANG_MODULE_FLAGS)

endif

LDFLAGS += $(NPC_MODULE_OBJS)
