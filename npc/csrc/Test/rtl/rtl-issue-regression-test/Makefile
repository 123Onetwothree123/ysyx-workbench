SHELL := /bin/bash

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
MILL ?= mill

.PHONY: all test run config-test clean

# 直接绑定内部 RTL 模块/端口的 C++ 探针已删除；这里只保留不实例化任何
# RTL 的公开配置校验。
all: config-test

test run: config-test

config-test:
	cd $(TEST_DIR) && MILL_WORKSPACE_ROOT=$(TEST_DIR) $(MILL) --no-server runMain ysyx_26030103.test.ConfigGuardTest

clean:
	rm -rf $(TEST_DIR)/build $(TEST_DIR)/out
