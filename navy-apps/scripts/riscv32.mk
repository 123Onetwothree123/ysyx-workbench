include $(NAVY_HOME)/scripts/riscv/common.mk
CFLAGS  += -march=rv32g -mabi=ilp32 -Wno-error=implicit-function-declaration  #overwrite
LDFLAGS += -melf32lriscv
