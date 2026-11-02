# RV64GC + Sv39 + OpenSBI + Linux（64 MiB）+ AMD FPGA 目标框架。
#
# 这是一个可直接仿真的 Linux 配置，同时启用 FPGA 厂商/工具链选项；
# 具体 FPGA 器件、板卡、时钟和约束文件在 menuconfig 的“CPU 与平台 -> FPGA 目标”
# 菜单中继续完善。
fragments/base_rv64gc.config
fragments/mmu_sv39.config
fragments/linux_sbi.config
fragments/fpga_amd.config
