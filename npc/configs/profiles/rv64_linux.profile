# RV64GC + Sv39 + OpenSBI + Linux（64 MiB）profile。
#
# 每行一个片段路径（相对 npc/configs/）；# 开头为注释。
# 后列出的片段覆盖前列片段中的同名符号。
fragments/base_rv64gc.config
fragments/mmu_sv39.config
fragments/linux_sbi.config
