# --- 1. 基本配置 ---
TOPNAME = SevenSegLEDNixietubeKeyboardASCII
NXDC_FILES = SevenSegLEDNixietubeKeyboardASCII.nxdc
BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
BIN = $(BUILD_DIR)/$(TOPNAME)
TEST_BIN = $(BUILD_DIR)/$(TOPNAME)_test

# --- 2. 工具与 GTest 路径 ---
VERILATOR = verilator
NPROC = $(shell nproc 2>/dev/null || echo 4)

# VCPKG 路径 (确保普通用户有权限访问)
VCPKG_TRIPLET ?= x64-linux
GTEST_DIR = $(VCPKG_ROOT)/installed/$(VCPKG_TRIPLET)

# --- 3. 环境变量检查 ---
ifeq ($(NVBOARD_HOME),)
$(error NVBOARD_HOME is not set)
endif

# 包含 NVBoard 规则
include $(NVBOARD_HOME)/scripts/nvboard.mk

# --- 4. 源文件 ---
VSRCS = $(shell find $(abspath ./vsrc) -name "*.v")
CSRCS = $(shell find $(abspath ./csrc) -name "*.cpp")
SRC_AUTO_BIND = $(BUILD_DIR)/auto_pin_bind.cpp
# NVBoard 模式需要的完整源文件
APP_CSRCS = $(CSRCS) $(SRC_AUTO_BIND)

# --- 5. 编译选项 ---
INCFLAGS = $(addprefix -I, $(INC_PATH) $(abspath ./csrc) $(OBJ_DIR))
CXX_STD = -std=c++20

# 普通模式库链接
FIXED_LIBS = -L$(NVBOARD_HOME)/build -l:nvboard.a -lSDL2 -lSDL2_image -lSDL2_ttf
# 测试模式库链接 (注意：不要链接 -lgtest_main，因为我们 main.cpp 里自己写了 main)
GTEST_LIBS = -L$(GTEST_DIR)/lib -lgtest -lpthread
GTEST_INC  = -I$(GTEST_DIR)/include

# --- 6. 编译目标 ---

default: $(BIN)

$(SRC_AUTO_BIND): $(NXDC_FILES)
	@mkdir -p $(@D)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

# 正常 NVBoard 编译
$(BIN): $(VSRCS) $(APP_CSRCS)
	@rm -rf $(OBJ_DIR)
	$(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-I$(abspath ./vsrc) \
		-O3 --x-assign fast --x-initial fast --noassert \
		--top-module $(TOPNAME) $^ \
		$(addprefix -CFLAGS , $(INCFLAGS) $(CXX_STD)) \
		$(addprefix -LDFLAGS , $(FIXED_LIBS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(BIN))

# GTest 模式编译
# 关键点：增加 -DTEST_MODE 和 GTEST_INC / GTEST_LIBS
test-build: $(VSRCS) $(CSRCS)
	@mkdir -p $(BUILD_DIR)
	@rm -rf $(OBJ_DIR)_test
	$(VERILATOR) -MMD --build -cc -j $(NPROC) \
		-I$(abspath ./vsrc) \
		--top-module $(TOPNAME) $^ \
		-DTEST_MODE \
		$(addprefix -CFLAGS , $(INCFLAGS) $(GTEST_INC) $(CXX_STD) -DTEST_MODE) \
		$(addprefix -LDFLAGS , $(GTEST_LIBS)) \
		--Mdir $(OBJ_DIR)_test --exe -o $(abspath $(TEST_BIN))

# --- 7. 快捷指令 ---
all:default
run: $(BIN)
	@$(BIN)

test: test-build
	@echo "--- RUNNING GTEST ---"
	@$(TEST_BIN)

clean:
	rm -rf $(BUILD_DIR)
	rm -f $(SRC_AUTO_BIND) 2>/dev/null || true
	@echo "Clean completed."
sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by your self."

.PHONY: default run clean test test-build
include ../Makefile