# bench/common/bench.mk
#
# bench/cache-*-test 的公共 AM 样板：镜像名取自目录名，主源文件固定为 main.cpp，
# 并允许引用 common/ 下的共享头（如 cache_bench.hpp）。
#
# 用法（目录位于 npc/csrc/Test/bench/<name>/）：
#   include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common/bench.mk)

BENCH_COMMON_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))

# ISA 位宽跟随 Kconfig 的 CONFIG_XLEN；路径为 bench/common/ -> Test/xlen.mk。
include $(BENCH_COMMON_DIR)/../../xlen.mk
ARCH ?= $(XLEN_AM_ARCH)
export ARCH

NAME := $(notdir $(CURDIR))
SRCS := main.cpp
CFLAGS += -I$(BENCH_COMMON_DIR)

include $(AM_HOME)/Makefile

# 显式固定默认目标：xlen.mk 中的 xlen-show 不是构建入口。
.DEFAULT_GOAL := image
all: image

.PHONY: test
test: image
	$(MAKE) --no-print-directory run

# 交叉检查：ARCH 必须来自 Kconfig 的 CONFIG_XLEN（或调用者显式覆盖）。
.PHONY: all xlen-check
xlen-check:
	@printf 'bench %s: ARCH=%s XLEN=%s\n' '$(NAME)' '$(ARCH)' '$(NPC_XLEN)'
