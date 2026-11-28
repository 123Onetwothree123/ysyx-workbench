NAME = char-test
SRCS = main.cpp

# 标准 AM 裸机目标：链接地址、入口和运行方式都由 AM 的 npc 平台提供，
# 不再在本目录写死 0x30000000。ARCH 跟随 Kconfig 的 CONFIG_XLEN，
# run 走 direct-NPC（SIM_TOP 默认 npc）。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)

.PHONY: test
test: run
