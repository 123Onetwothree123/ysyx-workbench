# --- 1. 项目基本配置 ---
TOPNAME = decode24
NXDC_FILES = decode24.nxdc
BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
BIN = $(BUILD_DIR)/$(TOPNAME)

# --- 2. 工具配置 ---
VERILATOR = verilator
# 使用所有可用的 CPU 核心进行多线程编译
NPROC = $(shell nproc 2>/dev/null || echo 4)
VERILATOR_FLAGS += -MMD --build -cc -j $(NPROC) \
                   -O3 --x-assign fast --x-initial fast --noassert
VERILATOR_DEBUG_FLAGS += -MMD --build -cc -j $(NPROC) \
                           -g --x-assign fast --x-initial fast --noassert

# --- 3. 环境变量检查 ---
ifeq ($(NVBOARD_HOME),)
$(error NVBOARD_HOME is not set)
endif

# --- 4. 包含 NVBoard 的核心编译规则 ---
include $(NVBOARD_HOME)/scripts/nvboard.mk

# --- 5. 源文件路径配置 ---
VSRCS = $(shell find $(abspath ./vsrc) -name "*.v")
CSRCS = $(shell find $(abspath ./csrc) -name "*.cpp")
SRC_AUTO_BIND = $(BUILD_DIR)/auto_pin_bind.cpp
CSRCS += $(SRC_AUTO_BIND)

# --- 6. 编译选项 (头文件与链接库) ---
# 注意：这里我们增加了对 nvboard.a 的直接引用，解决了找不到 -lnvboard 的问题
INCFLAGS = $(addprefix -I, $(INC_PATH) $(abspath ./csrc) $(OBJ_DIR))

# 编译器优化选项（通用优化，不针对特定架构）
CXX_STD = -std=c++20
OPT_FLAGS = -O3 -DNDEBUG -fomit-frame-pointer -finline-functions -funswitch-loops -fpredictive-commoning $(CXX_STD)
LINK_OPT_FLAGS = -flto

# 强制修正 LIBS 逻辑
# 使用 -l:nvboard.a 告诉链接器寻找确切的文件名
FIXED_LIBS = -L$(NVBOARD_HOME)/build -l:nvboard.a -lSDL2 -lSDL2_image -lSDL2_ttf

# --- 7. 主要编译目标 ---

default: $(BIN)

# 调试版本编译目标
DEBUG_BIN = $(BUILD_DIR)/$(TOPNAME)_debug

debug: $(DEBUG_BIN)

$(DEBUG_BIN): $(VSRCS) $(CSRCS)
	@rm -rf $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_DEBUG_FLAGS) \
		--top-module $(TOPNAME) $^ \
		$(addprefix -CFLAGS , $(INCFLAGS)) \
		$(addprefix -LDFLAGS , $(FIXED_LIBS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(DEBUG_BIN))

$(SRC_AUTO_BIND): $(NXDC_FILES)
	@mkdir -p $(@D)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

$(BIN): $(VSRCS) $(CSRCS)
	@rm -rf $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) \
		--top-module $(TOPNAME) $^ \
		$(addprefix -CFLAGS , $(INCFLAGS) $(OPT_FLAGS)) \
		$(addprefix -LDFLAGS , $(FIXED_LIBS) $(LINK_OPT_FLAGS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(BIN))

# --- 8. 快捷指令 ---
all:default
run: $(BIN)
	@$(BIN)

# 使用 GDB 调试运行
gdb: $(DEBUG_BIN)
	gdb $(DEBUG_BIN)

# 使用 GDB 调试运行（带参数）
gdb-run: $(DEBUG_BIN)
	gdb --args $(DEBUG_BIN)

clean:
	rm -rf $(BUILD_DIR)
	rm -f $(SRC_AUTO_BIND) 2>/dev/null || true
	@echo "Clean completed."
sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by your self."

.PHONY: default run debug gdb gdb-run clean
include ../Makefile