# software/ 聚合入口。
#
#   make test                 # 依次运行所有带 PASS/FAIL 检查的测试
#   make -C printf-test test  # 只运行一个测试
#   make char-test            # 只跑字符输出冒烟（输出一个 'A'）
#
# 每个测试的 test 目标由 common/test.mk 提供，失败会返回非零。

TESTS := \
	cpp-runtime-test \
	errno-test \
	exit-test \
	file-stream-test \
	locale-wide-test \
	malloc-stress \
	printf-test \
	qsort-test \
	scanf-test \
	strlen-test \
	strto-test

.PHONY: all test char-test $(TESTS)

all: test

test: $(TESTS)

$(TESTS):
	$(MAKE) -C $@ test

char-test:
	$(MAKE) -C char-test run
