# RV64 裸机地址/Cache 回归。
# 需要先用 XLEN=64 / ADDRESS_WIDTH=64 / ICACHE=y / DCACHE=y 生成并编译
# direct-NPC（见 npc/ADDRESSING.md），然后：
#   make -C npc/csrc/Test/rv64-address-test run
NPC_HOME := ../../..
CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
BIN ?= $(NPC_HOME)/build/ysyx_26030103_npc

.PHONY: all run clean
all: main.bin

main.elf: main.S
	$(CC) -march=rv64im -mabi=lp64 -nostdlib -nostartfiles \
		-Ttext=0x80000000 -Wl,--no-relax -o $@ $<

main.bin: main.elf
	$(OBJCOPY) -O binary $< $@

run: main.bin
	$(BIN) main.bin

clean:
	rm -f main.elf main.bin
