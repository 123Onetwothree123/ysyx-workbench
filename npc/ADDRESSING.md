# RV64 地址/Cache/AXI 边界说明

## 地址宽度

- `XLEN=32` 时 `AddressWidth=32`，行为与前序版本一致。
- `XLEN=64` 时 elaborator 默认选择 `AddressWidth=64`；也可通过
  `ADDRESS_WIDTH` 显式传入（`XLEN=64` 只接受 64）。
- PC、分支/跳转目标、IFU/LSU 虚拟/物理地址、BTB/RAS、PMA、Xbar、
  ICache/DCache tag 与 refill 地址全部使用 `AddressWidth`，不再在
  32 位处截断。
- 调试端口（`debug_pc`、`debug_mtrace_*` 等）为固定 32 位观测 ABI，
  不参与架构地址计算；完整地址在内部寄存器中保持 64 位。

## SoC 边界

- 外部 `ysyxSoC` CPU AXI 黑盒接口固定为 32 位地址、32 位数据。
- 因此 `AddressWidth=64` 的顶层配置要求全部 PMA 区域位于低 4GiB。
  超过 4GiB 的访问在 Xbar 内局部返回 DECERR，不能到达 SoC 边界；
  顶层还对 SoCBus 地址高位做断言，杜绝静默截断后误访问低地址外设。
- 叶模块（Xbar/ICache/DCache/LSU）可用高于 4GiB 的 PMA 区域做定向
  测试；该能力不改变 SoC 顶层 ABI。

## 8 字节 LD/SD

- LSU 的 LD/SD 在 32 位 AXI 数据通道上使用两拍 INCR：
  `AWLEN/ARLEN=1`、`AWSIZE/ARSIZE=2`，两拍合计覆盖 8 字节。
- 非缓存路径由 LSU 直接拆分；启用 DCache 时由 DCache 用两个相邻
  32 位 word 完成同一笔 8 字节 load/store，store 在匹配 BRESP 后
  同步更新低/高 word。
- 32 位 access 仍保持原单拍路径和既有 Cache 时序。

## RV64 MMU/Sv39

- `XLEN=64` 且 `HAS_MMU=y` 支持标准 Sv39（默认，3 级、8B PTE、44 位 PPN、
  `satp.MODE=8`）以及可配置 Sv48（4 级、`satp.MODE=9`）。
- MMURequest/PTWRequest/PTWResponse/TLB tag/PPN/PMP 地址均按 XLEN/
  AddressWidth 参数化；RV32/Sv32 默认参数与既有实现一致。
- 页表必须位于低 4GiB（外部 SoC AXI 边界）；更高物理地址的叶模块行为
  仍由 Xbar/Cache 定向测试覆盖。
- 非标准 RV64 页表几何在 elaboration 期明确报错；`configs/rv64_mmu_defconfig`
  提供 Sv39 + TLB 基线。
- 裸机镜像：`csrc/Test/rv64-mmu-test`。

## 本阶段不做

- A/C 扩展、AM/Nanos ELF64 加载。
