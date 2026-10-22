# 字符输出裸机测试。工具链/ISA 位宽从 Kconfig 的 CONFIG_XLEN 派生，
# 不再写死 riscv32-unknown-elf-*。ARCH 仅用于产物命名和 AM 平台选择。
include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../xlen.mk)

ARCH ?= $(XLEN_AM_ARCH)
CROSS ?= $(XLEN_CROSS_COMPILE)
MARCH ?= $(XLEN_GUEST_MARCH)
MABI ?= $(XLEN_GUEST_MABI)
CXXSRCS := $(wildcard *.cpp)

run:
	mkdir -p build
	$(CROSS)g++ -march=$(MARCH) -mabi=$(MABI) \
		-O2 -ffreestanding -nostdlib -nostartfiles \
		-fno-exceptions -fno-rtti \
		-Wl,-Ttext=0x30000000 -Wl,-e,_start \
		-o build/char-test-$(ARCH).elf $(CXXSRCS)
	$(CROSS)objcopy -j .text -O binary \
		build/char-test-$(ARCH).elf build/char-test-$(ARCH).bin
	make -C ../../.. run \
		file=$(abspath build/char-test-$(ARCH).bin) \
		elf=$(abspath build/char-test-$(ARCH).elf)

clean:
	rm -rf build
