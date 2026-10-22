# 本地 RV64UI/RV64UM 定向小子集（不是外部 riscv-arch-test）。
# 使用与 rv64-address-test 相同的 RV64 direct-NPC：
#   make -C npc/csrc/Test/rv64-ui-um-test run
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
	printf 'c\n' | $(BIN) main.bin

clean:
	rm -f main.elf main.bin
