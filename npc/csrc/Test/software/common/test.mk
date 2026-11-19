# software/* 的统一 PASS/FAIL 检查。
#
# 用法（在每个测试的 Makefile 中，先定义再 include）：
#   TEST_PASS_REGEX := PRINTF TEST PASS
#   include $(abspath $(dir $(firstword $(MAKEFILE_LIST)))/../common/test.mk)
#
# test 目标会正常走 AM 的 run（构建镜像并启动 direct-NPC），把完整输出
# 保存到 build/test.log，然后要求：
#   1) run 本身的退出码为 0（仿真 BAD trap/未触发 trap 时非 0）；
#   2) 日志中出现测试自己的 PASS 标记；
#   3) 日志中没有 FAIL/BAD TRAP 标记。
# 任何一条不满足都会让 make test 返回非零。

TEST_LOG ?= build/test.log
TEST_PASS_REGEX ?= PASS
TEST_FAIL_REGEX ?= FAIL|HIT BAD TRAP
TEST_NAME ?= $(notdir $(CURDIR))

.PHONY: test
test:
	@mkdir -p $(dir $(TEST_LOG))
	@echo "==> $(TEST_NAME): make run (log: $(TEST_LOG))"
	@bash -o pipefail -c '$(MAKE) --no-print-directory run 2>&1 | tee $(TEST_LOG)'
	@if ! grep -Eq -- '$(TEST_PASS_REGEX)' $(TEST_LOG); then \
		echo "FAIL: $(TEST_NAME): 日志中缺少 PASS 标记 '$(TEST_PASS_REGEX)'" >&2; \
		exit 1; \
	fi
	@if grep -Eq -- '$(TEST_FAIL_REGEX)' $(TEST_LOG); then \
		echo "FAIL: $(TEST_NAME): 日志中出现失败标记" >&2; \
		grep -En -- '$(TEST_FAIL_REGEX)' $(TEST_LOG) >&2 || true; \
		exit 1; \
	fi
	@echo "[PASS] $(TEST_NAME)"
