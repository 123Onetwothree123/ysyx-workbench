# F/D 硬浮点裸机回归。
#   make all      生成 rv64.elf/rv64.bin 与 rv32.elf/rv32.bin
#   make run64    用 RV64IMAFD 镜像运行 direct-NPC
#   make run32    用 RV32IMAF 镜像运行 direct-NPC
#   make run-diff64/run-diff32 预留 NEMU F/D DiffTest；需要 REF 已支持 F/D 状态 ABI。
NPC_HOME := ../../..
CROSS64 ?= riscv64-unknown-elf-
CROSS32 ?= riscv32-unknown-elf-
BIN ?= $(NPC_HOME)/build/ysyx_26030103_npc
REF_SO64 ?= $(NPC_HOME)/../nemu/build/riscv64-nemu-interpreter-so
REF_SO32 ?= $(NPC_HOME)/../nemu/build/riscv32-nemu-interpreter-so
OBJCOPY64 := $(CROSS64)objcopy
OBJCOPY32 := $(CROSS32)objcopy

CFLAGS_COMMON := -O2 -ffreestanding -fno-builtin -ffp-contract=off \
	-fno-stack-protector -mcmodel=medany -mno-relax -Wall -Wextra
CFLAGS64 := $(CFLAGS_COMMON) -march=rv64imafd -mabi=lp64d -DFPU_TEST_DOUBLE
CFLAGS32 := $(CFLAGS_COMMON) -march=rv32imaf -mabi=ilp32f
LDFLAGS := -nostdlib -nostartfiles -Wl,--no-relax -Wl,--build-id=none \
	-T linker.ld

.PHONY: all run64 run32 run-diff64 run-diff32 clean
all: rv64.bin rv32.bin

main64.o: main.S
	$(CROSS64)gcc $(CFLAGS64) -c -o $@ $<
test64.o: test.c
	$(CROSS64)gcc $(CFLAGS64) -c -o $@ $<
rv64.elf: main64.o test64.o linker.ld
	$(CROSS64)gcc $(CFLAGS64) $(LDFLAGS) -o $@ main64.o test64.o

main32.o: main.S
	$(CROSS32)gcc $(CFLAGS32) -c -o $@ $<
test32.o: test.c
	$(CROSS32)gcc $(CFLAGS32) -c -o $@ $<
rv32.elf: main32.o test32.o linker.ld
	$(CROSS32)gcc $(CFLAGS32) $(LDFLAGS) -o $@ main32.o test32.o

rv64.bin: rv64.elf
	$(OBJCOPY64) -O binary $< $@

rv32.bin: rv32.elf
	$(OBJCOPY32) -O binary $< $@

# 两个 defconfig 都使用 CONFIG_SDB=n，direct-NPC 没有 --batch 参数；
# 仿真在 custom halt 后自行退出。
run64: rv64.bin
	$(BIN) rv64.bin

run32: rv32.bin
	$(BIN) rv32.bin

# NEMU 主线已提供可选 FPR/fcsr DiffTest ABI（difftest_fp_*）。
run-diff64: rv64.bin
	@test -f "$(REF_SO64)" || { echo "REF 不存在: $(REF_SO64)"; exit 1; }
	$(BIN) rv64.bin --diff "$(REF_SO64)"

run-diff32: rv32.bin
	@test -f "$(REF_SO32)" || { echo "REF 不存在: $(REF_SO32)"; exit 1; }
	$(BIN) rv32.bin --diff "$(REF_SO32)"

clean:
	rm -f main64.o test64.o main32.o test32.o rv64.elf rv64.bin \
		rv32.elf rv32.bin
