# 最小 S-mode payload（OpenSBI 测试用）
#
# 用法：
#   make OUT=../../build/opensbi/test/payload ADDR=0x80200000
#
# 该 Makefile 只负责生成 payload.bin/elf；固件与运行由
# npc/scripts/build_opensbi.sh 和 test_opensbi.sh 驱动。

CROSS_COMPILE ?= riscv64-unknown-elf-
CC := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy

OUT ?= build
ADDR ?= 0x80200000
ISA ?= rv64ima_zicsr_zifencei
ABI ?= lp64
SBI_NAME ?= OpenSBI
FDT_EXPECTED ?= 0
FDT_ADDR ?= 0x80f00000

CFLAGS := -march=$(ISA) -mabi=$(ABI) -mcmodel=medany -mno-relax \
	-ffreestanding -fno-builtin -fno-stack-protector -nostdlib \
	-O2 -Wall -Wextra -Werror
CFLAGS += -DPAYLOAD_SBI_NAME=\"$(SBI_NAME)\"
ifneq ($(filter 1 y yes Y YES,$(FDT_EXPECTED)),)
CFLAGS += -DFDT_EXPECTED -DFDT_EXPECT_ADDR=$(FDT_ADDR)
endif
LDFLAGS := -nostdlib -fuse-ld=lld -Wl,-T,link.ld \
	-Wl,--defsym=PAYLOAD_ADDR=$(ADDR) -Wl,--no-relax \
	-Wl,--build-id=none -Wl,-e,_start

all: $(OUT)/payload.bin

$(OUT)/payload.elf: start.S payload.c link.ld
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) $(LDFLAGS) start.S payload.c -o $@

$(OUT)/payload.bin: $(OUT)/payload.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -rf $(OUT)

.PHONY: all clean
