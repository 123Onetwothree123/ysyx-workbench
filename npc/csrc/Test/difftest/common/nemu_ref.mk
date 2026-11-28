# difftest/common/nemu_ref.mk
#
# 9 个 difftest 测试重复的 NEMU REF 构建逻辑：
#   - 定位 NEMU 源码并收集 NEMU_SRCS；
#   - 定位 difftest/common/build_nemu_ref.sh；
#   - 用 nemu_ref_rule 生成 $(REF_*) 目标。
#
# 用法（在测试 Makefile 中，TEST_DIR 已定义）：
#   include $(TEST_DIR)/../common/nemu_ref.mk
#   $(eval $(call nemu_ref_rule,$(REF_SO),riscv32,$(REF_OBJ_DIR),share))
#
# 参数：target isa objdir mode [diff] [rve] [ext]
#   isa    riscv32 | riscv32e | riscv64
#   mode   share | native
#   diff   0/1（native 模式下是否启用 CONFIG_DIFFTEST）
#   rve    0/1
#   ext    空 | rvc | fd
#
# 省略 diff/rve/ext 时与 build_nemu_ref.sh 的默认值一致（0/0/空）。
# 各测试的镜像、ISA、objdir、输出路径差异仍由调用参数保留。

NEMU_REF_COMMON_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
TEST_DIR ?= $(abspath $(dir $(firstword $(MAKEFILE_LIST))))
NEMU_DIR ?= $(abspath $(TEST_DIR)/../../../../..)/nemu
REF_BUILDER ?= $(NEMU_REF_COMMON_DIR)/build_nemu_ref.sh
NEMU_SRCS ?= $(shell find $(NEMU_DIR)/src $(NEMU_DIR)/include -type f \
	\( -name '*.c' -o -name '*.h' \) -print 2>/dev/null)

define nemu_ref_rule
$(1): $(NEMU_SRCS) $(TEST_DIR)/Makefile $(REF_BUILDER) $(NEMU_REF_COMMON_DIR)/nemu_ref.mk
	$(REF_BUILDER) $(2) $$@ $(3) $(4) $(5) $(6) $(7)
endef
