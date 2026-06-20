ifndef VERILATOR_GENERATED_MK
$(error VERILATOR_GENERATED_MK is not set)
endif
ifndef NPC_CSRC_DIR
$(error NPC_CSRC_DIR is not set)
endif

include $(VERILATOR_GENERATED_MK)

NPC_MODULE_CXXFLAGS := $(NPC_USER_CXXFLAGS)

STD_MODULE_SRC := $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))
STD_MODULE_OBJ := std_module.o

gcm.cache/std.gcm: $(STD_MODULE_SRC)
	@mkdir -p gcm.cache
	$(CXX) $(CPPFLAGS) $(NPC_MODULE_CXXFLAGS) -x c++ -c $< -o $(STD_MODULE_OBJ)

NPC_IXX_SRCS := \
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
  $(NPC_CSRC_DIR)/trace/disasm.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/token.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/ExpressionError.ixx \
  $(NPC_CSRC_DIR)/ysyxSoC/ysyxSoC.ixx \
  $(NPC_CSRC_DIR)/sdb/command/Watchpoint.ixx \
  $(NPC_CSRC_DIR)/SoCMemoryMap/SoCMemoryMap.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommand.ixx \
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
  $(NPC_CSRC_DIR)/sdb/NPCEvaluationContext.ixx \
  $(NPC_CSRC_DIR)/difftest/difftest.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/parser.ixx \
  $(NPC_CSRC_DIR)/tools/expressions/expressions.ixx \
  $(NPC_CSRC_DIR)/sdb/SDBCommandRegistry.ixx \
  $(NPC_CSRC_DIR)/sdb/command/WatchpointPool.ixx \
  $(NPC_CSRC_DIR)/sdb/command/cCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/clearCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/dCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/ftraceCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/helpCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/historyCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/infoCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/iringbufCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/pCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/qCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/readelfCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/siCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/wCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/command/xCommand.ixx \
  $(NPC_CSRC_DIR)/sdb/sdb.ixx

NPC_MODULE_OBJS := $(STD_MODULE_OBJ) $(foreach src,$(NPC_IXX_SRCS),$(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))))

.npc_modules_built: gcm.cache/std.gcm $(NPC_IXX_SRCS)
	@$(foreach src,$(NPC_IXX_SRCS),echo "  CXX MODULE $(notdir $(src))"; $(CXX) $(CPPFLAGS) $(NPC_MODULE_CXXFLAGS) -x c++ -c $(src) -o $(subst /,__,$(patsubst $(NPC_CSRC_DIR)/%.ixx,%.ixx.o,$(src))) || exit 1;)
	@touch $@

$(VK_USER_OBJS): | .npc_modules_built
$(VK_USER_OBJS): private CPPFLAGS += $(NPC_USER_CXXFLAGS)
VK_USER_OBJS += $(NPC_MODULE_OBJS)
