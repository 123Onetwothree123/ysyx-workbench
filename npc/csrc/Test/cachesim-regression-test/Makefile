SHELL := /bin/bash

CACHESIM_DIR := $(abspath ../../tools/cachesim)
BUILD_DIR := $(CURDIR)/build
CACHESIM_BUILD_DIR := $(BUILD_DIR)/cachesim-build
CACHESIM_BIN := $(CACHESIM_BUILD_DIR)/cachesim
TRACE := $(BUILD_DIR)/mtrace.txt
BZ_TRACE := $(BUILD_DIR)/mtrace.txt.bz2

.PHONY: all test clean build-cachesim

all: test

build-cachesim:
	cmake -S $(CACHESIM_DIR) -B $(CACHESIM_BUILD_DIR)
	cmake --build $(CACHESIM_BUILD_DIR)

$(BUILD_DIR):
	mkdir -p $@

$(TRACE): | $(BUILD_DIR)
	printf '%s\n' \
	  'Memory Read: PC=0x80000000, Addr=0x10000000, Data=0x00000000, Width=4B' \
	  'Memory Write: PC=0x80000004, Addr=0x20000000, Data=0x00000000, Width=4B' \
	  'Memory Read: PC=0x80000008, Addr=0x30000000junk, Data=0x00000000, Width=4B' \
	  > $@

$(BZ_TRACE): $(TRACE)
	bzip2 -c $< > $@

test: build-cachesim $(TRACE) $(BZ_TRACE)
	$(CACHESIM_BIN) --block-size 4 --num-blocks 4 --ways 1 $(TRACE) \
	  > $(BUILD_DIR)/valid.log
	grep -Fq '总访问次数：2' $(BUILD_DIR)/valid.log
	grep -Fq '命中次数：0' $(BUILD_DIR)/valid.log
	grep -Fq '强制缺失：2 (100.00%)' $(BUILD_DIR)/valid.log
	@set +e; $(CACHESIM_BIN) --block-size 4junk $(TRACE) \
	  > $(BUILD_DIR)/partial-number.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq '无效的 --block-size 参数: 4junk' $(BUILD_DIR)/partial-number.log
	@set +e; $(CACHESIM_BIN) --block-size 0 $(TRACE) \
	  > $(BUILD_DIR)/zero-block.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq '块大小必须是大于 0 的 2 的幂' $(BUILD_DIR)/zero-block.log
	@set +e; $(CACHESIM_BIN) --num-blocks 3 $(TRACE) \
	  > $(BUILD_DIR)/non-power-blocks.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq 'num_blocks 必须是大于 0 的 2 的幂' $(BUILD_DIR)/non-power-blocks.log
	@set +e; $(CACHESIM_BIN) --dse --bus-width 0 $(TRACE) \
	  > $(BUILD_DIR)/zero-bus.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq 'bus_width 必须大于 0' $(BUILD_DIR)/zero-bus.log
	@set +e; $(CACHESIM_BIN) $(TRACE) --num-blocks \
	  > $(BUILD_DIR)/missing-value.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq -- '--num-blocks 缺少参数' $(BUILD_DIR)/missing-value.log
	$(CACHESIM_BIN) --block-size 4 --num-blocks 4 --ways 1 $(BZ_TRACE) \
	  > $(BUILD_DIR)/valid-bz2.log
	grep -Fq '总访问次数：2' $(BUILD_DIR)/valid-bz2.log
	@set +e; $(CACHESIM_BIN) $(BUILD_DIR)/missing.txt.bz2 \
	  > $(BUILD_DIR)/missing-bz2.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq 'bzip2 解压失败' $(BUILD_DIR)/missing-bz2.log
	@set +e; $(CACHESIM_BIN) --dse $(BUILD_DIR)/missing.txt.bz2 \
	  > $(BUILD_DIR)/missing-bz2-dse.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq 'bzip2 解压失败' $(BUILD_DIR)/missing-bz2-dse.log
	printf 'this is not a bzip2 stream\n' > $(BUILD_DIR)/corrupt.txt.bz2
	@set +e; $(CACHESIM_BIN) $(BUILD_DIR)/corrupt.txt.bz2 \
	  > $(BUILD_DIR)/corrupt-bz2.log 2>&1; status=$$?; set -e; test $$status -eq 1
	grep -Fq 'bzip2 解压失败' $(BUILD_DIR)/corrupt-bz2.log
	rm -f $(BUILD_DIR)/INJECTION_MARKER
	@set +e; $(CACHESIM_BIN) \
	  '$(BUILD_DIR)/missing.bz2; touch $(BUILD_DIR)/INJECTION_MARKER; #.bz2' \
	  > $(BUILD_DIR)/injection.log 2>&1; status=$$?; set -e; test $$status -eq 1
	test ! -e $(BUILD_DIR)/INJECTION_MARKER
	grep -Fq 'bzip2 解压失败' $(BUILD_DIR)/injection.log
	@echo '[PASS] cachesim MTRACE address and option validation regressions'

clean:
	rm -rf $(BUILD_DIR)
