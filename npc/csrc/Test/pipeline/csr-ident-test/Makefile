NAME = csr-ident-test
SRCS = main.c

# 把当前配置的 S/U 可用性传给裸机断言，避免测试对 Kconfig 做错误假设。
# 变量用递归展开：CONFIG_HAS_S/U 由 am-test.mk 引入的 xlen.mk 提供。
NPC_HAS_S = $(or $(CONFIG_HAS_S),n)
NPC_HAS_U = $(or $(CONFIG_HAS_U),n)
AM_TEST_CFLAGS_AFTER += \
	-DNPC_HAS_S=$(if $(filter y,$(NPC_HAS_S)),1,0) \
	-DNPC_HAS_U=$(if $(filter y,$(NPC_HAS_U)),1,0)

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
