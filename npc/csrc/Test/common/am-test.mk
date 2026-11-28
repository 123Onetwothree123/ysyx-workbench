# common/am-test.mk
#
# 短 AM（AbstractMachine）裸机测试的公共样板：
#   - 从 Test/xlen.mk 读取 CONFIG_XLEN，并推导默认 ARCH；
#   - NAME 默认取目录名，SRCS 默认 main.cpp；
#   - 统一 include $(AM_HOME)/Makefile，并固定默认目标为 image。
#
# 用法（目录位于 npc/csrc/Test/<category>/<name>/）：
#   NAME = my-test                  # 可选，默认 $(notdir $(CURDIR))
#   SRCS = main.cpp helper.cpp      # 可选，默认 main.cpp
#   AM_TEST_ARCH ?= $(XLEN_SOC_ARCH) # 可选，默认 $(XLEN_AM_ARCH)
#   AM_TEST_CFLAGS += -I...          # 可选，在 AM include 之前追加
#   AM_TEST_CXXFLAGS += -std=...     # 可选，在 AM include 之后 override 追加
#   AM_TEST_REQUIRE_RV32 := 1        # 可选，RV64 配置下直接报错
#   include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
#
# 需要覆盖 COMMON_CFLAGS/LDFLAGS 的测试，仍在 include 本文件之前使用
# override 赋值；本文件不会重置这些变量。
#
# 注意：override CXXFLAGS 必须放在 $(AM_HOME)/Makefile 之后，否则会被 AM
# 的普通赋值覆盖，因此公共文件把 AM_TEST_CXXFLAGS 统一放到 include 之后。

AM_TEST_COMMON_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))

include $(AM_TEST_COMMON_DIR)/../xlen.mk

NAME ?= $(notdir $(CURDIR))
SRCS ?= main.cpp

# 默认跟随 Kconfig 的 CPU XLEN；SoC 测试可显式改成 $(XLEN_SOC_ARCH)。
AM_TEST_ARCH ?= $(XLEN_AM_ARCH)
ARCH ?= $(AM_TEST_ARCH)
export ARCH

ifneq ($(AM_TEST_REQUIRE_RV32),)
ifeq ($(XLEN_IS_64),64)
$(error $(or $(AM_TEST_RV32_NAME),$(NAME)) 是 RV32 专用裸机测试；请在 XLEN=32 配置下运行)
endif
endif

CFLAGS += $(AM_TEST_CFLAGS)
LDFLAGS += $(AM_TEST_LDFLAGS)

include $(AM_HOME)/Makefile

override CXXFLAGS += $(AM_TEST_CXXFLAGS)
override CFLAGS += $(AM_TEST_CFLAGS_AFTER)

# xlen.mk 的 xlen-show 是第一个目标；显式恢复构建入口。
.DEFAULT_GOAL := image
all: image
