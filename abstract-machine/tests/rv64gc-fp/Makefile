NAME = rv64gc-fp
SRCS = main.c fp_probe.S
include $(AM_HOME)/Makefile

# 允许用 make EXTRA_CFLAGS='-DPROBE_SPIN=... -DITERS=...' 做短回归。
CFLAGS   += $(EXTRA_CFLAGS)
ASFLAGS  += $(EXTRA_CFLAGS)

.PHONY: check-isa
check-isa: $(IMAGE).elf
	@CROSS_COMPILE=$(CROSS_COMPILE) READELF=$(READELF) OBJDUMP=$(OBJDUMP) \
		NM=$(CROSS_COMPILE)nm sh $(WORK_DIR)/check-isa.sh $(IMAGE).elf
	@echo "check-isa: libgcc = $$($(CC) -march=rv64gc_zicsr_zifencei -mabi=lp64d -print-libgcc-file-name)"
	@for obj in $$(find $(AM_HOME)/am/build/$(ARCH) $(AM_HOME)/klib/build/$(ARCH) \
			-name '*.o' 2>/dev/null); do \
		$(READELF) -h $$obj | grep -q 'double-float ABI' || { \
			echo "check-isa: $$obj 不是 lp64d" >&2; exit 1; }; \
	done
	@echo "check-isa: AM/KLIB 全部目标文件均为 double-float ABI"
