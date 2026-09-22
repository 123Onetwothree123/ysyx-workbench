NAME = klib
SRCS = $(shell find src/ -name "*.c")
include $(AM_HOME)/Makefile

# The RISC-V canonical-NaN specialization intentionally compiles out the uses
# of two common-NaN temporaries and two rounding labels.  Keep these upstream
# warnings local to the vendored SoftFloat sources; KLIB code remains under
# the project-wide -Wall -Werror policy.
$(DST_DIR)/src/softfloat/%.o: CFLAGS += -Wno-unused-variable -Wno-unused-label
