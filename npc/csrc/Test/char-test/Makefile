ifeq ($(ARCH),)
$(error 没写ARCH跑个毛线啊，虽然目前也就只支持rv32i的NPC，没适配NEMU，所以是ARCH只能写riscv32-npc)
endif

run:
	mkdir -p build
	riscv32-unknown-elf-gcc -march=rv32i -mabi=ilp32 \
		-O2 -ffreestanding -nostdlib -nostartfiles \
		-Wl,-Ttext=0x20000000 -Wl,-e,_start \
		-o build/char-test-$(ARCH).elf char-test.c
	riscv32-unknown-elf-objcopy -j .text -O binary \
		build/char-test-$(ARCH).elf build/char-test-$(ARCH).bin
	make -C ../../.. run \
		file=$(abspath build/char-test-$(ARCH).bin) \
		elf=$(abspath build/char-test-$(ARCH).elf)

clean:
	rm -rf build
