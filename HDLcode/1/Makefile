# ---------------- 配置部分 ----------------

# 1. 你的 Verilog 顶层模块名 (不带 .v)
TOPNAME = top

# 2. 你的约束文件
NXDC_FILES = top.nxdc

# 3. 你的 C++ 驱动文件
CPP_SRC = sim_main.cpp

# 4. 自动生成的绑定文件 (不用改)
SRC_AUTO_BIND = auto_bind.cpp

# ---------------- 环境检查 ----------------

ifndef NVBOARD_HOME
$(error NVBOARD_HOME is not set. Please export NVBOARD_HOME=/path/to/nvboard)
endif

# ---------------- 编译参数 (关键修正) ----------------

# 1. 获取 SDL2 的编译和链接参数
SDL_CFLAGS  = $(shell sdl2-config --cflags)
SDL_LDFLAGS = $(shell sdl2-config --libs)

# 2. 头文件路径 (CRITICAL FIX)
# 你发现正确的 API 定义在 usr/include 下，而不是 include 下
INCLUDE_FLAGS = -I$(NVBOARD_HOME)/usr/include $(SDL_CFLAGS)

# 3. 链接库
# 同时搜索 build 和 usr/lib 目录，确保找到 libnvboard.a
LINK_FLAGS = -L$(NVBOARD_HOME)/build -L$(NVBOARD_HOME)/usr/lib -lnvboard $(SDL_LDFLAGS) -lSDL2_image -lSDL2_ttf

# 4. Verilator 参数
# --build: 自动 make
# --exe: 生成可执行文件
# -j 8: 多线程
# -Wall: 开启警告
# -Wno-EOFNEWLINE: 忽略换行符警告
VERILATOR_FLAGS = --cc --exe --build -j 8 -Wall -Wno-EOFNEWLINE

# ---------------- 编译规则 ----------------

# 默认目标
all: run

# 1. 生成 C++ 绑定代码 (调用 Python)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

# 2. 运行 Verilator
# 注意：C++ 的参数通过 -CFLAGS 和 -LDFLAGS 透传
V$(TOPNAME): $(SRC_AUTO_BIND) $(CPP_SRC) $(TOPNAME).v
	verilator $(VERILATOR_FLAGS) \
		-CFLAGS "$(INCLUDE_FLAGS)" \
		-LDFLAGS "$(LINK_FLAGS)" \
		$(TOPNAME).v $(CPP_SRC) $(SRC_AUTO_BIND)

# 3. 运行仿真
run: V$(TOPNAME)
	./obj_dir/V$(TOPNAME)

# 清理
clean:
	rm -rf obj_dir $(SRC_AUTO_BIND)

.PHONY: all run clean