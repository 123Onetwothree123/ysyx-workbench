# RV64 地址/Cache/AXI 边界说明

## 地址宽度

- `XLEN=32` 时 `AddressWidth=32`，行为与前序版本一致。
- `XLEN=64` 时 elaborator 默认选择 `AddressWidth=64`；也可通过
  `ADDRESS_WIDTH` 显式传入（`XLEN=64` 只接受 64）。
- PC、分支/跳转目标、IFU/LSU 虚拟/物理地址、BTB/RAS、PMA、Xbar、
  ICache/DCache tag 与 refill 地址全部使用 `AddressWidth`，不再在
  32 位处截断。
- direct-NPC 的调试端口按低/高两个 32 位字导出（`debug_pc` /
  `debug_pc_hi` 等），C++ 侧按 XLEN 拼成完整 `XWord`；外部 ysyxSoC
  顶层调试 ABI 仍固定 32 位。调试端口不参与架构地址计算。

## SoC 边界

- 外部 `ysyxSoC` CPU AXI 黑盒接口固定为 32 位地址、32 位数据。
- 因此 `AddressWidth=64` 的顶层配置要求全部 PMA 区域位于低 4GiB。
  超过 4GiB 的访问在 Xbar 内局部返回 DECERR，不能到达 SoC 边界；
  顶层还对 SoCBus 地址高位做断言，杜绝静默截断后误访问低地址外设。
- 叶模块（Xbar/ICache/DCache/LSU）可用高于 4GiB 的 PMA 区域做定向
  测试；该能力不改变 SoC 顶层 ABI。

## SoC XLEN 独立配置

- `CONFIG_SOC_XLEN_FOLLOW_CPU`（默认 `n`）控制 ysyxSoC CPU 侧接口
  是否跟随 CPU 的 `CONFIG_XLEN`；它只是“跟随”，不是强制 CPU 与 SoC
  同宽。
- `CONFIG_SOC_XLEN`（默认 32）仅在 FOLLOW=`n` 时可见/生效，只允许
  32 或 64；有效值为
  `EffectiveSocXLEN = if (FOLLOW) XLEN else SOC_XLEN`。
- 配置层允许并测试以下四种组合：

  | CPU XLEN | FOLLOW | SOC_XLEN | EffectiveSocXLEN |
  | --- | --- | --- | --- |
  | 32 | n | 32 | 32 |
  | 64 | n | 32 | 32（64 位 CPU + 32 位 SoC，低 4GiB 窗口） |
  | 32 | n | 64 | 64（允许配置，等待桥接） |
  | 64 | n | 64 | 64（允许配置，等待桥接） |

- `NPCConfig` 暴露 `SocXLENFollowCpu`、`SocXLEN`、`EffectiveSocXLEN`
  与 `SocXLENNeedsAxiBridge`；`Elaborate` 从环境变量读取并原样传入。
- 当前顶层 AXI RTL/IO 仍是固定 32 位地址/数据，因此
  `EffectiveSocXLEN=64` 只表示配置已保留，尚不代表 RTL 已支持；
  elaboration 会打印 WARN。后续 AXI 桥接任务应消费
  `EffectiveSocXLEN`（和 `SocXLENNeedsAxiBridge`）扩展端口与转换逻辑，
  而不是重新解释 Kconfig 或直接假设 SoC 与 CPU 同宽。
- 由于当前实际端口仍是 32 位，`AddressWidth=64` 的 PMA 低 4GiB 约束
  继续按实际 RTL 生效，不随 `EffectiveSocXLEN` 放宽。

## C++ 运行时 XLEN

- `csrc/Config.h` 是 NPC 运行时唯一的 XLEN 入口，只消费 Makefile 传入的
  `CONFIG_XLEN` 与有效 `CONFIG_SOC_XLEN`（已解析 FOLLOW_CPU）。
- 它派生 `npc::XLen`、`npc::SocXLen`、`npc::XWord`、`npc::SocXWord`：
  RV32 仍为 32 位，RV64 的地址/GPR/PC/CSR 为完整 64 位。
- csrc 不再生成或读取独立的 32/64 位第二开关；ELF class、反汇编模式、
  DUT 调试状态、SDB 地址宽度和 DiffTest 状态布局都从上述派生值获得。
- 状态 ABI 由 REF 的 `difftest_state_size()` / `difftest_csr_state_size()`
  动态校验：RV32 期望 132/16 字节，RV64 期望 264/32 字节。RV64
  direct-NPC 完整保留 GPR/PC/CSR 高半区；旧 32 位 REF 会在执行前被
  明确拒绝。full-SoC 顶层因调试端口固定 32 位，RV64 DiffTest 仍拒绝。

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
  提供 Sv39 + TLB 基线，`configs/rv64_sv48_defconfig` 提供 Sv48 + TLB
  独立基线。
- 裸机镜像：Sv39 为 `csrc/Test/rv64-mmu-test`，Sv48 为
  `csrc/Test/rv64-sv48-test`；后者的 `satp.MODE=9`、四级页表与
  独立构建目录见 `scripts/rv64_sv48_regression.sh`。

## 本阶段不做

- A/C 扩展、AM/Nanos ELF64 加载。
