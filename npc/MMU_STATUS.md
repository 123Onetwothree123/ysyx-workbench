# NPC Sv32 MMU/TLB 状态

## 已接入

- 标准 Sv32：4KiB 页、2 级页表、4B PTE、4KiB 页表页。
- 可配置页大小、页表级数、PTE 大小（4/8B）、每级页表项数；RTL 要求
  `pageBits + levels * indexBits == 32`，支持 1..4 级和 4B/8B PTE。
- M 模式旁路；S/U 模式经 `satp` 翻译。
- `satp`、`sstatus`、`stvec`、`sepc`、`scause`、`stval`、`sscratch`、
  `medeleg`、`mideleg`、`mie/mip/sie/sip`。
- `mret`、`sret`、`sfence.vma`。
- mstatus 字段：MIE/MPIE/MPP/SIE/SPIE/SPP/MPRV/SUM/MXR/TVM/TW/TSR，
  并有 TVM/TSR/TW 的译码陷门和 MPRV 数据访问特权级。
- CSR 逐字段读/写模式生成 mstatus/satp 掩码；`CSR_ENABLED` 控制
  CSR 存在性，`CSR_MISSING_MODE=illegal/zero` 生效；CSR 访问做
  最低特权级检查。
- CLINT `mtimecmp` 与 timer interrupt（machine cause 7）。
- `TIMER_PATH=direct_stip` 直接产生 S timer 中断（cause 5）。
- PLIC 寄存器/claim-complete 模块，source 数、优先级位宽、最大值和
  基地址可配置，已接入 Xbar 和顶层；priority/pending/claim/threshold
  行为测试通过。
- 取指/load/store 页错误，以及向 S 模式委托异常。
- split ITLB/DTLB 与 unified TLB。
- unified TLB 可配置 2 个查找端口（`UTLB_LOOKUP_PORTS=2`），每拍可让
  两个不同上下文同时查找；split 模式每个 TLB 仍为单端口。
- TLB ASID、Global、RR/PLRU/LRU 替换。
- TLB Reg-CAM 和同步 SRAM 数据阵列两种存储。
- TLB 可选 4MiB 超级页 CAM；PTW 默认不把超级页填 TLB，打开
  `TLB_SUPERPAGE` 后由 PTW 返回 fillLevel=1 并填入超级页 CAM。
- 独立只读 PTW AXI 通道，或复用 LSU 读端口（`MMU_PTW_PATH=reuse_dcache`）。
- MMU 请求队列、响应表、序号重排和 1..16 个在途 walk 槽。
- 真实 TLB miss queue：follow_tlb 下 split 使用独立 ITLB/DTLB 队列、
  unified 使用 UTLB 队列；global 模式共用一条队列。深度分别由
  ITLB/DTLB/UTLB/GLOBAL_MISS_QUEUE_ENTRIES 控制，miss 入队后再调度到
  PTW。
- PTW 支持 `MMU_PTW_ARCH=independent` 的多 walker 簇，以及
  `MMU_PTW_ARCH=pipelined` 的级间流水实现：
  - A 级为在途请求计算 PTE 地址并查 PTE cache；
  - B 级共享单端口页表总线，处理 4B/8B PTE 读和 A/D 写回；
  - C 级解析 PTE、推进页表级数并产生响应。
  - 页表读等待期间 A 级仍可服务其它在途请求；同一请求的各级仍受
    数据依赖约束。
- PTW 返回 in_order/out_of_order 选择；MMU 层仍保证架构响应顺序。
- PTW 页表读的共享 AXI 读端口支持多 outstanding：PTWToAXI 为每笔页表读
  保存原事务 ID 并分配 AXI ID 3..15，AXIReadMux 与 AXI5Arbiter 按 RID
  路由回对应 PTW 槽位。ICache burst 仍使用 ID 0，LSU 使用 ID 1，DMA
  使用 ID 2；四类读可以同时在途。PTW AXI 读最多 13 笔并发，超过部分在
  适配器队列等待（4 位下游 ID 的物理上限）。
- PIPT 和 VIPT：VIPT 使用虚拟地址提前选 index/block offset，物理地址
  到达后再做 tag/PMA/refill。elaboration 会检查 index+块内偏移是否落在
  页内，超出 pageBits 的配置明确报错，避免静默 alias。ICache 和 DCache 都有并行 VIPT 路径；
  DCache 的 load/store 都可以提前受理：store 先按 VA 登记写缓冲，只有
  携带匹配 VA 标签的 PA 返回后才发 AW/W 或更新缓存镜像，页错误 kill
  不会留下任何写副作用。
- 可选 PTW PTE cache（set-associative），支持 4B/8B PTE；8B 表项保存
  低/高两个字，命中后无需重复两次页表读。
- IFU/LSU 双端口仲裁：两笔翻译可以同时在途，响应按 ID 路由；
  DMMU 对非访存指令组合直通。
- 所有新增注释为简体中文。

## 已验证

单元测试：

```text
总计约 110 项 mill 单元测试通过，包含：
MMU 结构/行为/流、PTW pipeline、AXI 多 outstanding、CSR/U 裁剪、
IDU 特权级、PLIC、PMP、ICache VIPT、DCache VIPT、LSUMMUVipt、
LSUVipt、LSUDmmuVipt、LSUStoreVipt。
```

裸机测试（MMU 开启）：

```text
S 模式访存              HIT GOOD
U 模式访存              HIT GOOD
S load page fault       HIT GOOD
S store page fault      HIT GOOD
S ecall + SRET          HIT GOOD
```

## 配置行为

- `HAS_MMU=n`：IFU/LSU 直接使用物理地址，MMU/PTW 不例化。
- `HAS_TLB=n`：每次访问走 PTW，TLB 不例化。
- `HAS_TLB=y, HAS_MMU=n`：生成器按无 TLB 处理并告警。
- `refillPorts>1` 已支持 TLB 多写回端口和 PTW 多响应端口；
  `miss_queue_mode=follow_tlb` 使用 ITLB/DTLB 或 UTLB 独立 miss queue，
  `miss_queue_mode=global` 使用一条共享队列；入队深度由对应 Kconfig
  项控制，队列满时请求留在查找上下文等待。
- `unified + utlbLookupPorts=2` 时每拍选择两个不同的 `ctxLookup`
  上下文并行查找；同拍命中按请求序号写入响应表。若与 PTW 响应写回
  冲突则 PTW 优先，TLB 命中延后；每拍仍只发出一个 PTW 请求。
- `HAS_PMP=y` 启用基础 PMP：OFF/TOR/NA4/NAPOT、L 锁定和 R/W/X；
  有 MMU 时检查翻译后的物理地址，无 MMU 时直接检查 IFU 物理取指
  地址和 LSU 的 VA==PA 访存地址。
- `HAS_U=y`：保持 M/S/U 三特权级、U 模式 ecall(cause=8)、U 页
  U 位/SUM 检查和 SRET 返回 U 的原有行为。
- `HAS_U=n`：MRET/SRET 的最低目标特权级固定为 S（mstatus.MPP/SPP
  的 U 编码在写入时夹到 S）；U 级 CSR 从实现集合裁剪；ecall cause=8
  选择路径被裁掉；TLB/PTW 的 U 位和 SUM 检查固定为不适用，
  S 模式可直接访问 U=1 的页（R/W/X 仍检查）。
- `HAS_S=n`：M-only 配置。S 级 CSR、`stvec/sepc/scause/stval`、`satp`、
  委托寄存器、S 中断视图和 S 模式入口逻辑在 elaboration 期直接裁剪；
  SRET/SFENCE.VMA 在译码阶段按非法指令处理。`HAS_MMU=y` 或 `HAS_U=y`
  与 `HAS_S=n` 组合会在 elaboration 前明确报错，不会静默保留 S 逻辑。
- `HAS_MMU=y` 要求 `HAS_S=y`；当前不支持 M-only 的 Sv32。`HAS_TLB=y`
  且 `HAS_MMU=n` 时 TLB 按未启用处理。
- 默认 `HAS_S=y/HAS_U=y` 时行为与原来一致。

## 验证结论

- Nanos-lite：已修复 AXIRAM CPU 写地址 256KiB 回绕，输出启动日志并
  运行用户进程，最终到达 `halt(0)` / `HIT GOOD TRAP`，rc=0。
- HAS_S/HAS_U：M-only、M/S/U、S 裁剪组合均有 elaboration/结构测试；
  HAS_MMU 与 HAS_S=n 的不合法组合明确报错。
- PMP：有/无 MMU 两种路径均已接入。
- DCache VIPT：load/store 都支持 VA 提前受理，store 在 PA 确认前
  不产生写副作用。
- TLB superpage：支持按 pageBits/levels/indexBits 计算的多个超级页层级。
- PTW 页表总线：内部支持多 outstanding 和事务 ID；共享 AXI IFU/LSU
  读端口按 ID 支持多笔 AXI read 重叠。ICache/LSU/DMA 固定占用 0/1/2，
  PTW 使用 3..15，最多 13 笔 AXI 读同时在途。
- 剩余限制：PTW 内部页表总线可配置到 16 笔，但下游 4 位 AXI ID 在保留
  ICache/LSU/DMA 后只剩 13 个 PTW 槽；更多请求会在 PTWToAXI 队列等待，
  不再退化为单笔串行，也不影响功能正确性。
