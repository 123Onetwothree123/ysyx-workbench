# ============================================================================
# csrc/Test 注册表测试入口
# ============================================================================

test:
	mill -i test

# csrc/Test 的注册表测试入口，转发到 csrc/Test/Makefile。
# 与上面的 `test`（Scala 单测）和 `run`（跑一份镜像）互不冲突。
TEST_DIR := $(NPC_HOME)/csrc/Test

.PHONY: test-menuconfig test-list test-config test-run test-clean
test-menuconfig test-list test-config test-run test-clean:
	@$(MAKE) -C $(TEST_DIR) $(patsubst test-%,%,$@)
