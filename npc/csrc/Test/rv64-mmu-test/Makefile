# RV64 Sv39 MMU 裸机回归。
# 需要先用 configs/rv64_mmu_defconfig 生成并编译 direct-NPC（XLEN=64、
# HAS_MMU=y、Sv39、TLB=y），然后：
#   make -C npc/csrc/Test/rv64-mmu-test run
NPC_HOME := ../../..
CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
BIN ?= $(NPC_HOME)/build/ysyx_26030103_npc

.PHONY: all run clean
all: main.bin

main.elf: main.S
	$(CC) -march=rv64im_zicsr -mabi=lp64 -nostdlib -nostartfiles \
		-Ttext=0x80000000 -Wl,--no-relax -o $@ $<

main.bin: main.elf
	$(OBJCOPY) -O binary $< $@

# .config 默认打开 SDB，非交互运行时送入一次 continue；SDB=n 时该输入被忽略。
run: main.bin
	printf 'c\n' | $(BIN) main.bin

clean:
	rm -f main.elf main.bin
