AM_SRCS := riscv/npc/start.S \
           riscv/npc/trm.c \
           riscv/npc/ioe.c \
           riscv/npc/timer.c \
           riscv/npc/input.c \
           riscv/npc/cte.c \
           riscv/npc/trap.S \
           riscv/npc/vme.c \
           platform/dummy/mpe.c

CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(AM_HOME)/scripts/linker.ld
NPC_HOME ?= $(abspath $(AM_HOME)/../npc)
-include $(NPC_HOME)/include/config/auto.conf

# direct-NPC的物理地址布局跟随Kconfig，缺省值保持历史行为
NPC_RAM_BASE ?= $(or $(CONFIG_NPC_RAM_BASE),0x80000000)
NPC_RAM_SIZE ?= $(or $(CONFIG_NPC_RAM_SIZE),0x04000000)
PMA_UART_BASE ?= $(or $(CONFIG_PMA_UART_BASE),0x10000000)
PMA_UART_SIZE ?= $(or $(CONFIG_PMA_UART_SIZE),0x00001000)
PMA_CLINT_BASE ?= $(or $(CONFIG_PMA_CLINT_BASE),0x02000000)
PMA_CLINT_SIZE ?= $(or $(CONFIG_PMA_CLINT_SIZE),0x00010000)

LDFLAGS   += --defsym=_pmem_start=$(NPC_RAM_BASE) --defsym=_entry_offset=0x0
CFLAGS    += -DAM_NPC_RAM_BASE=$(NPC_RAM_BASE) \
             -DAM_NPC_RAM_SIZE=$(NPC_RAM_SIZE) \
             -DAM_PMA_UART_BASE=$(PMA_UART_BASE) \
             -DAM_PMA_UART_SIZE=$(PMA_UART_SIZE) \
             -DAM_PMA_CLINT_BASE=$(PMA_CLINT_BASE) \
             -DAM_PMA_CLINT_SIZE=$(PMA_CLINT_SIZE)
CACHE_PADDING ?= $(or $(CONFIG_CACHE_PADDING),0)
LDFLAGS   += --defsym=_cache_padding=$(CACHE_PADDING)
LDFLAGS   += --gc-sections -e _start

# The in-core mtime counter advances once per CPU clock.  Pass the configured
# target clock to AM at compile time instead of probing a non-existent MMIO
# frequency register at run time.
AM_TIMER_FREQ_MHZ ?= $(or $(CONFIG_SYNTH_FREQ),450)
CFLAGS += -DAM_TIMER_FREQ_MHZ=$(AM_TIMER_FREQ_MHZ)

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)

insert-arg: image
	@python $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

run: insert-arg
	#ysyx自带的 echo "TODO: add command here to run simulation"
	$(MAKE) -C $(NPC_HOME) run file=$(IMAGE).bin elf=$(IMAGE).elf

perf: insert-arg
	$(MAKE) -C $(NPC_HOME) perf file=$(IMAGE).bin elf=$(IMAGE).elf

cachesim: insert-arg
	$(MAKE) -C $(NPC_HOME) cachesim file=$(IMAGE).bin elf=$(IMAGE).elf

.PHONY: insert-arg perf cachesim
