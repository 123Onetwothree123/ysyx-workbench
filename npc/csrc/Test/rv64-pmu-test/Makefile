# RV64 架构 PMU 回归：mhpmevent/mhpmcounter、mcountinhibit、
# 用户别名、Sscofpmf 溢出与 LCOFI。
#
# 需要先用打开 PERF_RISCV_ARCH_PMU（以及 Zihpm/Sscofpmf）的配置编译
# direct-NPC，然后：
#   make -C npc/csrc/Test/rv64-pmu-test run
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
