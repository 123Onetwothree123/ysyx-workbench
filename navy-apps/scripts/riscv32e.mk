include $(NAVY_HOME)/scripts/riscv/common.mk
CFLAGS  += -march=rv32em -mabi=ilp32e  -Wno-error=implicit-function-declaration#overwrite
LDFLAGS += -melf32lriscv
