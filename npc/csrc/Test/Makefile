# NPC 测试入口。
#
#   make menuconfig   编辑 Test/.config
#   make list         列出当前选择
#   make run          执行测试
#   make clean        清理测试产物、日志和 runner
#   make distclean    额外删除 .config
#
# 透传变量：
#   make run TEST_TIER=performance TEST_TAG=scala TEST_NAME=cache
#   make run ARCH=riscv64gc-npc TEST_RETRY=1
# TEST_TIER/TEST_TAG/TEST_RETRY 也接受不带 TEST_ 前缀的写法。

TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_DIR ?= $(abspath $(TEST_DIR)/../..)
TESTREG_DIR := $(TEST_DIR)/tools/testreg
TESTREG_BUILD := $(TESTREG_DIR)/build
TESTREG := $(TESTREG_BUILD)/testreg
MCONF := $(NPC_DIR)/tools/build/c_mconf

CMAKE ?= cmake
NPROC := $(shell nproc 2>/dev/null || echo 1)

TEST_TIER := $(or $(TEST_TIER),$(TIER))
TEST_TAG := $(or $(TEST_TAG),$(TAG))
TEST_RETRY := $(or $(TEST_RETRY),$(RETRY))
TEST_ARCH := $(or $(TEST_ARCH),$(ARCH))
# NAME 在环境里常被占用（如主机名），只接受命令行传入。
TEST_NAME := $(if $(filter command line,$(origin NAME)),$(NAME),$(TEST_NAME))

TESTREG_ARGS := \
	$(if $(TEST_TIER),--tier $(TEST_TIER)) \
	$(if $(TEST_TAG),--tag $(TEST_TAG)) \
	$(if $(TEST_NAME),--name $(TEST_NAME)) \
	$(if $(TEST_ARCH),--arch $(TEST_ARCH)) \
	$(if $(TEST_RETRY),--retry $(TEST_RETRY))

.PHONY: all help menuconfig menu list config run test clean clean-logs clean-runner clean-tests distclean

all: help

help:
	@printf '%s\n' \
		'  make menuconfig   编辑 Test/.config' \
		'  make list         列出当前选择' \
		'  make config       打印生效的 tier/arch/tag/name' \
		'  make run          执行测试' \
		'  make clean        清理测试产物、日志和 runner' \
		'  make distclean    清空 .config' \
		'' \
		'  变量: TIER= TAG= NAME= ARCH= RETRY=  (或 TEST_ 前缀)'

menuconfig menu: $(TESTREG) $(MCONF)
	@$(TESTREG) menu

list: $(TESTREG)
	@$(TESTREG) list $(TESTREG_ARGS)

config: $(TESTREG)
	@$(TESTREG) config $(TESTREG_ARGS)

run test: $(TESTREG)
	@$(TESTREG) run $(TESTREG_ARGS)

$(TESTREG): $(wildcard $(TESTREG_DIR)/*.cpp $(TESTREG_DIR)/*.hpp) $(TESTREG_DIR)/CMakeLists.txt
	@$(CMAKE) -S $(TESTREG_DIR) -B $(TESTREG_BUILD) -G Ninja -DCMAKE_BUILD_TYPE=Release
	@$(CMAKE) --build $(TESTREG_BUILD) -j $(NPROC)

$(MCONF):
	@$(MAKE) -C $(NPC_DIR) tools/build/c_mconf

clean: clean-logs clean-runner clean-tests
clean-logs:
	@rm -rf $(TEST_DIR)/build/test-logs
clean-runner:
	@rm -rf $(TESTREG_BUILD)
clean-tests:
	@bash $(TEST_DIR)/tools/clean-tests.sh $(TEST_DIR)
	@echo "cleaned test builds"

distclean: clean
	@rm -f $(TEST_DIR)/.config $(TEST_DIR)/.config.old
