# RV64 Sv48 独立裸机回归。
# 需要先用 configs/rv64_sv48_defconfig 生成并编译 direct-NPC（XLEN=64、
# HAS_MMU=y、Sv48、TLB=y），且构建目录与 Sv39 镜像分开，例如：
#   make BUILD_DIR=build/rv64-sv48 rv64_sv48_defconfig
#   make BUILD_DIR=build/rv64-sv48 -j$(nproc) build/rv64-sv48/ysyx_26030103_npc
#   make -C npc/csrc/Test/rv64-sv48-test run \
#        BIN=../../build/rv64-sv48/ysyx_26030103_npc
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

# .config 默认打开 SDB，非交互运行时送入一次 continue。
run: main.bin
	printf 'c\n' | $(BIN) main.bin

clean:
	rm -f main.elf main.bin
