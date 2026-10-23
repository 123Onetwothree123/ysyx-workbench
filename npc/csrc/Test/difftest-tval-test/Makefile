# RV64 trap tval/stval DiffTest 裸机回归。
# 页表以镜像数据预置，测试本身不执行 store。
#   make run BIN=/path/to/ysyx_26030103_npc REF_SO=/path/to/riscv64-nemu-ref.so
NPC_HOME := ../../..
CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
BIN ?= $(NPC_HOME)/build/ysyx_26030103_npc
REF_SO ?= $(NPC_HOME)/../nemu/build/riscv64-nemu-interpreter-so
HOST_CXX ?= g++
LEGACY_SHIM := build/legacy-no-tval.so

.PHONY: all run run-diff run-legacy clean
all: main.bin

main.elf: main.S main.ld
	$(CC) -march=rv64im_zicsr -mabi=lp64 -nostdlib -nostartfiles \
		-T main.ld -Wl,--no-relax -o $@ $<

main.bin: main.elf
	$(OBJCOPY) -O binary $< $@

run: main.bin
	$(BIN) main.bin --batch

run-diff: main.bin
	@test -f "$(REF_SO)" || { echo "REF 不存在: $(REF_SO)"; exit 1; }
	$(BIN) main.bin --diff "$(REF_SO)" --batch

$(LEGACY_SHIM): legacy_no_tval.cpp
	mkdir -p $(@D)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
		-o $@ $< -ldl

# 旧 REF 缺少 difftest_trap 时必须先告警，并在第一个非零 tval 的 trap
# 上明确失败（退出码非 0，且不出现 GOOD TRAP）。
run-legacy: main.bin $(LEGACY_SHIM)
	@test -f "$(REF_SO)" || { echo "REF 不存在: $(REF_SO)"; exit 1; }
	@set +e; \
	  DIFFTEST_REAL_REF="$(REF_SO)" $(BIN) main.bin \
	    --diff $(LEGACY_SHIM) --batch > build/legacy.log 2>&1; \
	  status=$$?; set -e; cat build/legacy.log; \
	  grep -Fq 'REF 缺少 difftest_trap' build/legacy.log || \
	    { echo '缺少旧 REF 降级告警'; exit 1; }; \
	  grep -Fq '无法注入' build/legacy.log || \
	    { echo '缺少非零 tval 的明确拒绝信息'; exit 1; }; \
	  test $$status -ne 0 || { echo '旧 REF 错误地继续运行'; exit 1; }; \
	  ! grep -Fq 'HIT GOOD TRAP' build/legacy.log || \
	    { echo '旧 REF 仍执行到 GOOD TRAP'; exit 1; }

clean:
	rm -rf build main.elf main.bin
