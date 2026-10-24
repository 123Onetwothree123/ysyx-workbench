# RV64GC / LP64D 用户态目标：供 Nanos-lite 的 RV64GC 配置使用。
#
# 与 AM 的 riscv64gc-npc 保持同一套 ISA/ABI：
#   -march=rv64gc_zicsr_zifencei  -> RV64IMAFDC + Zicsr/Zifencei，含 RVC
#   -mabi=lp64d                   -> 64 位指针 + 双精度硬浮点调用约定
#   -mcmodel=medany               -> 代码可放在 Sv39 的任意 2GiB 窗口
#
# 这里故意不复用 riscv/common.mk：后者固定 rv64g，无法表达 C 扩展。
# CROSS_COMPILE 沿用 Navy 既有 riscv32/riscv64 目标的
# riscv64-linux-gnu 工具链；Navy 用 ld 直接静态链接自己的 newlib/libos，
# 不会引入 glibc 运行时。若工具链没有 lp64d 多库，-mabi=lp64d 会在
# 编译阶段直接报错，不会静默退化为 lp64 软浮点。（AM 内核仍用
# riscv64-unknown-elf，两者共享 lp64d 调用约定。）
CROSS_COMPILE = riscv64-linux-gnu-
LNK_ADDR = $(if $(VME), 0x40000000, 0x83000000)
# newlib 3.3 的部分源文件仍有旧式隐式声明（例如 wcstold.c 的
# strtold_l）；与 rv32 目标一致，只把这个诊断降级为警告，不改写
# newlib 语义。ABI/ISA 相关选项仍是硬性错误。
CFLAGS  += -fno-pic -march=rv64gc_zicsr_zifencei -mabi=lp64d -mcmodel=medany \
           -Wno-error=implicit-function-declaration
LDFLAGS += -melf64lriscv --no-relax -Ttext-segment $(LNK_ADDR)
