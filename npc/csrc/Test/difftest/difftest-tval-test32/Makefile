# RV32 trap tval DiffTest 裸机回归（M 模式 illegal/非对齐/ecall）。
#   make run-diff BIN=/path/to/rv32-npc REF_SO=/path/to/riscv32-nemu-ref.so
NPC_HOME := ../../../..
CROSS ?= riscv32-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
BIN ?= $(NPC_HOME)/build/ysyx_26030103_npc
REF_SO ?= $(NPC_HOME)/../nemu/build/riscv32-nemu-interpreter-so
TEST_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := $(abspath $(TEST_DIR)/../../../../..)
NEMU_DIR := $(ROOT_DIR)/nemu
REF_OBJ_DIR := $(TEST_DIR)/build/nemu-ref-obj

include $(TEST_DIR)/../common/nemu_ref.mk

.PHONY: all run run-diff clean
all: main.bin

main.elf: main.S
	$(CC) -march=rv32im_zicsr -mabi=ilp32 -nostdlib -nostartfiles \
		-Ttext=0x80000000 -Wl,--no-relax -o $@ $<

main.bin: main.elf
	$(OBJCOPY) -O binary $< $@

run: main.bin
	$(BIN) main.bin --batch

run-diff: main.bin $(REF_SO)
	@test -f "$(REF_SO)" || { echo "REF 不存在: $(REF_SO)"; exit 1; }
	$(BIN) main.bin --diff "$(REF_SO)" --batch

$(eval $(call nemu_ref_rule,$(REF_SO),riscv32,$(REF_OBJ_DIR),share))

clean:
	rm -f main.elf main.bin
