TOPNAME = minirvcpu
BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
TEST_OBJ_DIR = $(BUILD_DIR)/obj_dir_test
BIN = $(BUILD_DIR)/$(TOPNAME)
TEST_BIN = $(BUILD_DIR)/$(TOPNAME)_test
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
# 普通模式递归搜索 csrc，但排除 csrc/gtest 目录
CSRCS = $(shell find $(abspath ./csrc) \
	-path "$(abspath ./csrc)/gtest" -prune -o \
	-name "*.cpp" -print)
# 测试模式只收集 csrc/gtest 下的 cpp
TEST_CSRCS = $(shell find $(abspath ./csrc/gtest) -name "*.cpp" 2>/dev/null)
# 普通模式
APP_CSRCS = $(CSRCS)
# 测试模式：
#排除普通 main.cpp
#加入 gtest 目录下的测试文件
TEST_APP_CSRCS = $(filter-out $(abspath ./csrc)/main.cpp $(abspath ./csrc)/memory.cpp, $(CSRCS)) $(TEST_CSRCS)
#编译部分了
COMMON_INCFLAGS = $(addprefix -I, $(INC_PATH) $(abspath ./csrc) $(OBJ_DIR))
TEST_INCFLAGS = $(COMMON_INCFLAGS) $(addprefix -I, $(abspath ./csrc/gtest) $(TEST_OBJ_DIR))
CXX_STD = -std=c++23 -fmodules
# 普通模式的编译参数
COMMON_CFLAGS = $(COMMON_INCFLAGS) $(CXX_STD)
# 测试模式的编译参数
TEST_CFLAGS = $(TEST_INCFLAGS) $(CXX_STD)
# 测试模式的库的链接
GTEST_LIBS = -L$(GTEST_DIR)/lib -lgtest -lpthread
GTEST_INC  = -I$(GTEST_DIR)/include

default: $(BIN)

$(OBJ_DIR)/.std_module_stamp:
	@rm -rf $(OBJ_DIR)
	@mkdir -p $(OBJ_DIR)
	@cd $(OBJ_DIR) && $(CXX) $(CXX_STD) -c $(STD_MODULE_SRC) -o std_module.o
	@touch $@

$(TEST_OBJ_DIR)/.std_module_stamp:
	@rm -rf $(TEST_OBJ_DIR)
	@mkdir -p $(TEST_OBJ_DIR)
	@cd $(TEST_OBJ_DIR) && $(CXX) $(CXX_STD) -c $(STD_MODULE_SRC) -o std_module.o
	@touch $@

#普通 Verilator 编译
$(BIN): $(OBJ_DIR)/.std_module_stamp $(VSRCS) $(APP_CSRCS)
	@mkdir -p $(BUILD_DIR)
	$(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-I$(abspath ./vsrc) \
		-O3 --x-assign fast --x-initial fast --noassert \
		--top-module $(TOPNAME) $(VSRCS) $(APP_CSRCS) \
		$(addprefix -CFLAGS , $(COMMON_CFLAGS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(BIN))

#GTest模式编译
test-build: $(TEST_OBJ_DIR)/.std_module_stamp $(VSRCS) $(TEST_APP_CSRCS)
	@mkdir -p $(BUILD_DIR)
	$(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-I$(abspath ./vsrc) \
		--top-module $(TOPNAME) $(VSRCS) $(TEST_APP_CSRCS) \
		-DTEST_MODE \
		$(addprefix -CFLAGS , $(TEST_CFLAGS) $(GTEST_INC) -DTEST_MODE) \
		$(addprefix -LDFLAGS , $(GTEST_LIBS)) \
		--Mdir $(TEST_OBJ_DIR) --exe -o $(abspath $(TEST_BIN))

all: default

run: $(BIN)
ifndef file
	$(error 请用 `make run file=/path/to/image` 指定镜像文件)
endif
	@$(BIN) $(file)

test: test-build
	@echo "--- RUNNING GTEST ---"
	@$(TEST_BIN)

clean:
	rm -rf $(BUILD_DIR)
	@echo "Clean completed."

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by your self."

.PHONY: default all run clean test test-build sim

include ../Makefile
