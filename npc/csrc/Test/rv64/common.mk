# rv64 裸机测试的公共构建/运行规则。
#
# 各测试目录的 Makefile 在自己的说明注释之后 include 本文件，并且可以在
# include 之前设置 MARCH（默认 rv64im_zicsr）来覆盖指令集。
#
#   MARCH := rv64im
#   include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../common.mk)
#
# NPC_HOME 保持在测试目录下解析，BIN 默认指向当前工作树的 direct-NPC。
NPC_HOME := ../../../..
CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
BIN ?= $(NPC_HOME)/build/ysyx_26030103_npc
MARCH ?= rv64im_zicsr

.PHONY: all run clean
all: main.bin

main.elf: main.S
	$(CC) -march=$(MARCH) -mabi=lp64 -nostdlib -nostartfiles \
		-Ttext=0x80000000 -Wl,--no-relax -o $@ $<

main.bin: main.elf
	$(OBJCOPY) -O binary $< $@

# .config 默认打开 SDB，非交互运行时送入一次 continue；SDB=n 时该输入被忽略。
run: main.bin
	printf 'c\n' | $(BIN) main.bin

clean:
	rm -f main.elf main.bin
