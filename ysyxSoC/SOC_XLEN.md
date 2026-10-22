# ysyxSoC CPU 接口 XLEN 配置（SOC_XLEN）

## 目标

`SOC_XLEN` 控制 **CPU 侧 AXI4 黑盒接口**的地址/数据宽度：

| SOC_XLEN | CPU AXI 地址 | CPU AXI 数据 | WSTRB | SoC 内部互联/外设 |
| --- | --- | --- | --- | --- |
| 32（默认） | 32 位 | 32 位 | 4 位 | 32 位 |
| 64 | 64 位 | 64 位 | 8 位 | 32 位 |

SoC 内部的 `AXI4Xbar`、`AXI4ToAPB`、SRAM/SDRAM/MROM 以及所有 APB
外设仍保持 32 位，不因 `SOC_XLEN=64` 改变。64 位 CPU 端口通过
`src/amba/AXI4Downsizer.scala` 接入 32 位 xbar。

`SOC_XLEN` 的取值优先级：

1. `SOC_XLEN`（显式设置，可让 SoC 独立于 CPU 选择）
2. `CPU_XLEN`
3. `XLEN`
4. `32`

因此既可以用 `SOC_XLEN=64 make verilog` 独立选择，也可以由上层
Makefile 传入 `CPU_XLEN`/`XLEN` 让 SoC 跟随 CPU。

## 构建

```sh
cd ysyxSoC

# 默认 32 位，生成 build/ysyxSoCFull.v
make verilog

# 显式 32 位
make verilog-32

# 显式 64 位
make verilog-64
# 等价于：
SOC_XLEN=64 make verilog
```

32/64 构建共用 `build/ysyxSoCFull.v`。`build/.elaboration-config` 包含
`SOC_XLEN`，切换宽度会自动触发重新 elaboration。

64 位构建要求 NPC 提供的 `ysyx_26030103` 黑盒模块同时具有 64 位
`io_master_*` 和 `io_slave_*` 地址/数据端口（`io_master_awaddr`、
`io_master_wdata`、`io_master_araddr`、`io_master_rdata` 等）。如果
NPC 边界仍是 32 位端口，Verilator 会在端口宽度处报错，而不会静默
按 32 位连接。

## 64 位数据通路

```text
CPU (64 位 AXI4 master)
        |
        v
AXI4Downsizer (64 位地址/数据 -> 32 位地址/数据)
        |
        v
AXI4Xbar (32 位) -> AXI4ToAPB -> 32 位 APB 外设
        |          -> AXI4 RAM/SDRAM/MROM
        v
ChipLink / 其它 32 位 SoC 设备
```

`AXI4Downsizer` 的约束与行为：

- 把每个上游 beat 拆成一个或多个 4 字节单拍 AXI 传输；
- 支持 INCR/FIXED/WRAP burst、1/2/4/8 字节 size；
- 读通道把多笔 32 位 R 数据合并回上游 64 位 R beat；
- 写通道按 WSTRB 把 64 位数据和字节使能拆分到 32 位字；
- 上游每个方向同一时刻只处理一笔事务，用 backpressure 保证顺序；
- ID 原样透传，下游只使用单拍 `len=0` 传输。

这不是高性能实现：64 位构建的 CPU 端口吞吐低于 32 位直连，适合
功能验证和 SoC 集成，不用于性能评估。后续若需要高吞吐，可在
`AXI4Downsizer` 内增加读写流水或改用带宽度转换的交换网络。

此外，桥会把 `AWCACHE/ARCACHE/AWPROT/ARPROT/AWLOCK/QOS` 置为
保守默认值（0/非缓存），不向 32 位 SoC 透传；ysyxSoC 现有外设不
依赖这些字段。若后续接入带缓存属性或 QoS 的设备，需要在桥中补充
相应的字段映射。

## 地址与错误

SoC 内部地址空间仍是 32 位。64 位 CPU 端口只接受低 4GiB 的访问：

- 起始地址高于 `0xffffffff`，或整笔 burst 跨越 4GiB 边界时，
  读返回 `DECERR` + `RLAST`，写吃掉整个 W burst 后返回 `DECERR`；
- 不会把地址高 32 位静默截断后访问低地址外设；
- 未映射的低地址仍由现有 xbar/外设行为决定，软件 PMA 应保证不访问。

## 调试/trap 端口

`io_trap_*`、`io_debug_*`、`io_perf_*` 的观测 ABI **仍为 32 位**。
`SOC_XLEN=64` 不会自动把这些端口扩成 64 位，也不会把 64 位 PC/CSR
截断后当作完整值上报。

64 位 SoC 的完整调试需要 NPC 边界桥接提供高 32 位端口（或使用
NPC 侧的 `debug_*_hi` 观测 ABI）。当前 SoC 只保留 32 位低半区观测，
elaboration 时会打印明确的警告。启用 64 位调试前不要把 32 位
`debug_pc`/`debug_trap_pc` 当作完整 64 位地址使用。

## 验证状态

已实际执行：

- `SOC_XLEN=32 make verilog`：成功生成 `build/ysyxSoCFull.v`；
- `SOC_XLEN=64 make verilog`：成功 elaboration 并生成
  `build/ysyxSoCFull.v`，CPU 黑盒实例端口为 64 位地址/数据；
- 从 64 位生成物中抽出 `AXI4Downsizer`，用 Verilator 运行独立
  AXI 主/从测试，覆盖单拍 8 字节读写、两拍 8 字节 INCR/WRAP burst、
  偏移 4 字节的部分写/读、1 字节写、高 4GiB 读写 DECERR，全部通过；
- `SOC_XLEN=64 CHIPLINK=y` 也能完成 elaboration（仅验证生成，未做
  ChipLink 整机仿真）。

**未声称** 64 位 SoC 已跑通完整 NPC 启动/回归：当前 `npc/` 的边界
仍按 32 位 AXI 生成，64 位 CPU 到 SoC 的边界桥接由另一个任务完成。
只有该桥接接入并通过整机仿真后，才能宣称 64 位 SoC 端到端可用。
