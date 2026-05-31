TOPNAME = RV32E32Reg
BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
TEST_OBJ_DIR = $(BUILD_DIR)/obj_dir_test
BIN = $(BUILD_DIR)/$(TOPNAME)
TEST_BIN = $(BUILD_DIR)/$(TOPNAME)_test
CONFIG_STAMP = $(OBJ_DIR)/.config_stamp
VERILATOR = verilator
CXX = g++
NPROC = $(shell nproc 2>/dev/null || echo 4)
GXX_VERSION = $(shell $(CXX) -dumpfullversion)
STD_MODULE_SRC = /usr/include/c++/$(GXX_VERSION)/bits/std.cc
#VCPKG路径
VCPKG_TRIPLET ?= x64-linux
GTEST_DIR = $(VCPKG_ROOT)/installed/$(VCPKG_TRIPLET)
ifeq ($(VCPKG_ROOT),)
$(warning VCPKG_ROOT没有设置，如果需要Gtest的话，test-build就会失败)
endif
#源文件
VSRCS = $(shell find $(abspath ./vsrc) -name "*.v")
CSRC_DIR = $(abspath ./csrc)
TRACE_SRC_DIR = $(CSRC_DIR)/trace
SDB_SRC_DIR = $(CSRC_DIR)/sdb
GTEST_SRC_DIR = $(CSRC_DIR)/gtest
# 普通模式递归搜索 csrc，但绕开 csrc/gtest 目录
CSRCS = $(shell find $(CSRC_DIR) \
	-path "$(GTEST_SRC_DIR)" -prune -o \
	-name "*.cpp" -print)
# 测试模式只收集 csrc/gtest 下的 cpp
TEST_CSRCS = $(shell find $(GTEST_SRC_DIR) -name "*.cpp" 2>/dev/null)
# 测试模式额外纳入可非交互单测的 SDB/C++ 组件；NPC_SDB.cpp 和命令主循环继续排除，
# 避免把 readline 交互和普通 memory.cpp 的 DPI 入口混进 gtest。
TEST_SDB_CSRCS = \
	$(SDB_SRC_DIR)/SDBDPI.cpp \
	$(SDB_SRC_DIR)/SDBMemory.cpp \
	$(SDB_SRC_DIR)/NPCEvaluationContext.cpp \
	$(SDB_SRC_DIR)/command/SDBCommandUtils.cpp \
	$(SDB_SRC_DIR)/command/Watchpoint.cpp \
	$(SDB_SRC_DIR)/command/WatchpointPool.cpp
# 普通模式
APP_CSRCS = $(CSRCS)
#itrace部分
#capstone配置
CAPSTONE_DIR=$(abspath ../nemu/tools/capstone/repo)
CAPSTONE_INC = -I$(CAPSTONE_DIR)/include
CAPSTONE_LIB = -L$(CAPSTONE_DIR) -lcapstone
# trace和sdb开关，默认关闭，make CONFIG_XXX=y开启
CONFIG_ITRACE ?= n
CONFIG_MTRACE ?= n
CONFIG_FTRACE ?= n
CONFIG_SDB    ?= n
CONFIG_DIFFTEST ?= n
ifeq ($(CONFIG_ITRACE),y)
COMMON_CFLAGS += -DCONFIG_ITRACE
endif
ifeq ($(CONFIG_MTRACE),y)
COMMON_CFLAGS += -DCONFIG_MTRACE
endif
ifeq ($(CONFIG_FTRACE),y)
COMMON_CFLAGS += -DCONFIG_FTRACE
endif
ifeq ($(CONFIG_SDB),y)
COMMON_CFLAGS += -DCONFIG_SDB
endif
ifeq ($(CONFIG_DIFFTEST),y)
COMMON_CFLAGS += -DCONFIG_DIFFTEST
COMMON_LDFLAGS += -ldl
endif
CONFIG_FLAGS := CONFIG_ITRACE=$(CONFIG_ITRACE) CONFIG_MTRACE=$(CONFIG_MTRACE) CONFIG_FTRACE=$(CONFIG_FTRACE) CONFIG_SDB=$(CONFIG_SDB) CONFIG_DIFFTEST=$(CONFIG_DIFFTEST)
# 测试模式：
#排除普通 main.cpp
#加入 gtest 目录下的测试文件
TEST_APP_CSRCS = $(filter-out $(CSRC_DIR)/main.cpp $(CSRC_DIR)/NPCTrap.cpp $(SDB_SRC_DIR)/%.cpp $(CSRC_DIR)/memory.cpp, $(CSRCS)) $(TEST_SDB_CSRCS) $(TEST_CSRCS)
#编译部分了
COMMON_INCFLAGS = $(addprefix -I, $(INC_PATH) $(CSRC_DIR) $(TRACE_SRC_DIR) $(SDB_SRC_DIR) $(OBJ_DIR))
TEST_INCFLAGS = $(COMMON_INCFLAGS) $(addprefix -I, $(GTEST_SRC_DIR) $(TEST_OBJ_DIR))
CXX_STD = -std=c++23 -fmodules
# 普通模式的编译参数
COMMON_CFLAGS += $(COMMON_INCFLAGS) $(CXX_STD) $(CAPSTONE_INC) -O3
# 测试模式的编译参数
TEST_CFLAGS = $(TEST_INCFLAGS) $(CXX_STD) $(CAPSTONE_INC)
# 测试模式的库的链接
GTEST_LIBS = -L$(GTEST_DIR)/lib -lgtest -lpthread
GTEST_INC  = -I$(GTEST_DIR)/include

default: $(BIN)

$(OBJ_DIR)/.std_module_stamp:
	@rm -rf $(OBJ_DIR)
	@mkdir -p $(OBJ_DIR)
	@cd $(OBJ_DIR) && $(CXX) $(CXX_STD) -c $(STD_MODULE_SRC) -o std_module.o
	@touch $@

$(CONFIG_STAMP): FORCE | $(OBJ_DIR)/.std_module_stamp
	@if [ ! -f $@ ] || [ "$$(cat $@)" != "$(CONFIG_FLAGS)" ]; then \
		echo "$(CONFIG_FLAGS)" > $@; \
	fi

$(TEST_OBJ_DIR)/.std_module_stamp:
	@rm -rf $(TEST_OBJ_DIR)
	@mkdir -p $(TEST_OBJ_DIR)
	@cd $(TEST_OBJ_DIR) && $(CXX) $(CXX_STD) -c $(STD_MODULE_SRC) -o std_module.o
	@touch $@

#普通 Verilator 编译
$(BIN): $(OBJ_DIR)/.std_module_stamp $(CONFIG_STAMP) $(VSRCS) $(APP_CSRCS)
	@mkdir -p $(BUILD_DIR)
	$(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-I$(abspath ./vsrc) \
		-O3 --x-assign fast --x-initial fast --noassert \
		--top-module $(TOPNAME) $(VSRCS) $(APP_CSRCS) \
		$(addprefix -CFLAGS , $(COMMON_CFLAGS)) \
		$(addprefix -LDFLAGS , $(CAPSTONE_LIB) -lreadline $(COMMON_LDFLAGS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(BIN))

#GTest模式编译
test-build: $(TEST_OBJ_DIR)/.std_module_stamp $(VSRCS) $(TEST_APP_CSRCS)
	@mkdir -p $(BUILD_DIR)
	$(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-I$(abspath ./vsrc) \
		--top-module $(TOPNAME) $(VSRCS) $(TEST_APP_CSRCS) \
		-DTEST_MODE \
		$(addprefix -CFLAGS , $(TEST_CFLAGS) $(GTEST_INC) -DTEST_MODE) \
		$(addprefix -LDFLAGS , $(GTEST_LIBS) $(CAPSTONE_LIB)) \
		--Mdir $(TEST_OBJ_DIR) --exe -o $(abspath $(TEST_BIN))

all: default

run: $(BIN)
	@$(BIN) $(file) $(if $(elf),--elf $(elf),) $(if $(diff),--diff $(diff),)

GTEST_FILTER ?= *
GTEST_REPEAT ?= 1
GTEST_SEED ?= 20260502

test: test-build
	@echo "--- RUNNING GTEST ---"
	@$(TEST_BIN) --gtest_filter='$(GTEST_FILTER)'

test-list: test-build
	@$(TEST_BIN) --gtest_list_tests

test-super: test-build
	@echo "--- RUNNING GTEST SUPER SUITE ---"
	@$(TEST_BIN) --gtest_filter='$(GTEST_FILTER)' --gtest_shuffle --gtest_repeat=$(GTEST_REPEAT) --gtest_random_seed=$(GTEST_SEED)

clean:
	rm -rf $(BUILD_DIR)
	@echo "Clean completed."

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by your self."

.PHONY: default all run clean test test-list test-super test-build sim FORCE

include ../Makefile
