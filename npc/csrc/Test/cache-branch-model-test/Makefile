SHELL := /bin/bash

DCACHESIM_DIR := $(abspath ../../tools/dcachesim)
BRANCHSIM_DIR := $(abspath ../../tools/branchsim)
BUILD_DIR := $(CURDIR)/build
DCACHE_BUILD := $(BUILD_DIR)/dcachesim
BRANCH_BUILD := $(BUILD_DIR)/branchsim
DCACHESIM := $(DCACHE_BUILD)/dcachesim
BRANCHSIM := $(BRANCH_BUILD)/branchsim

.PHONY: all test clean build-dcachesim build-branchsim

all: test

build-dcachesim:
	cmake -S $(DCACHESIM_DIR) -B $(DCACHE_BUILD) -DCONFIG_RUN_MODE=0
	cmake --build $(DCACHE_BUILD) -j2

build-branchsim:
	cmake -S $(BRANCHSIM_DIR) -B $(BRANCH_BUILD) -DCONFIG_RUN_MODE=0
	cmake --build $(BRANCH_BUILD) -j2

test: build-dcachesim build-branchsim
	cd $(BUILD_DIR) && $(DCACHESIM) $(CURDIR)/dcache.trace > dcache.log
	grep -Eq '^FIFO[[:space:]]+6[[:space:]]+2[[:space:]]+4[[:space:]]+4[[:space:]]+1[[:space:]]+2[[:space:]]+1[[:space:]]+0' $(BUILD_DIR)/dcache.log
	cd $(BUILD_DIR) && $(BRANCHSIM) $(CURDIR)/indirect-call.trace > indirect-call.log
	grep -Eq '^BTFN\+SplitJal[[:space:]]+2[[:space:]]+1[[:space:]]+1' $(BUILD_DIR)/indirect-call.log
	grep -Eq '^BTFN\+SplitJal\+RAS[[:space:]]+2[[:space:]]+1[[:space:]]+1' $(BUILD_DIR)/indirect-call.log
	cd $(BUILD_DIR) && $(BRANCHSIM) $(CURDIR)/coroutine.trace > coroutine.log
	grep -Eq '^BTFN\+SplitJal\+RAS[[:space:]]+6[[:space:]]+1[[:space:]]+5' $(BUILD_DIR)/coroutine.log
	cd $(BUILD_DIR) && $(BRANCHSIM) $(CURDIR)/return-offset.trace > return-offset.log
	grep -Eq '^BTFN\+SplitJal\+RAS[[:space:]]+4[[:space:]]+1[[:space:]]+3' $(BUILD_DIR)/return-offset.log
	cd $(BUILD_DIR) && $(BRANCHSIM) $(CURDIR)/forward-branch.trace > forward-branch.log
	grep -Eq '^BTFN[[:space:]]+4[[:space:]]+3[[:space:]]+1' $(BUILD_DIR)/forward-branch.log
	grep -Eq '^BTFN\+SharedJal[[:space:]]+4[[:space:]]+3[[:space:]]+1' $(BUILD_DIR)/forward-branch.log
	grep -Eq '^BTFN\+SplitJal[[:space:]]+4[[:space:]]+3[[:space:]]+1' $(BUILD_DIR)/forward-branch.log
	grep -Eq '^BTFN\+SplitJal\+RAS[[:space:]]+4[[:space:]]+3[[:space:]]+1' $(BUILD_DIR)/forward-branch.log
	@echo '[PASS] DCache write-through/no-write-allocate and branch JAL BTB/RAS model regressions'

clean:
	rm -rf $(BUILD_DIR)
