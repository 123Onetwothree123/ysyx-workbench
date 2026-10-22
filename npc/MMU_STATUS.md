# NPC Sv32/Sv39/Sv48 与 RV64 CUSTOM MMU/TLB 状态

## 已接入

- 标准 Sv32：4KiB 页、2 级页表、4B PTE、4KiB 页表页（XLEN=32）。
- RV64 标准 Sv39：4KiB 页、3 级页表（9+9+9+12）、8B PTE、44 位 PPN；
  `satp.MODE=8`、ASID 16 位、PPN 44 位。Sv48 复用同一套参数化
  walker/TLB：4 级（9+9+9+9+12）、`satp.MODE=9`；未配置对应几何时
  satp 写入按 WARL 收敛到 Bare(0)。
- RV64 显式 CUSTOM 模式（`MMU_GEOMETRY_MODE=custom`）：允许非标准
  页大小、1..4 级页表、任意 2 的幂页表项数、4B/8B PTE 以及私有 PTE
  字段位置。CUSTOM 使用保留的 `satp.MODE=10..15` 作为非标准扩展，
  标准 8/9 的语义完全不变；在 CUSTOM 几何下写入 8/9 会按 WARL
  收敛到 Bare(0)。示例配置：
  - 1KiB 页 + 3 级 + 每级 2048 项（11 位索引）→ 43 位 VA，
    `MMU_CUSTOM_SATP_MODE=10`；
  - 2KiB 页 + 4 级 + 每级 512 项（9 位索引）→ 47 位 VA。
  `configs/rv64_custom_page_defconfig` 提供 1KiB 示例。
- 64 位 PTE 支持完整 PPN 字段、A/D 硬件写回（只写低 32 位字，因此
  CUSTOM 的 A/D 位必须位于低字）与超级页 PPN 对齐检查；
  STANDARD/CUSTOM 非规范虚拟地址都在 TLB 查找前产生页错误，不会与
  低位别名命中。CUSTOM 下 satp MODE 与配置不匹配也会直接页错误，
  不会用私有几何继续翻译标准模式请求。
- `satp`、`mstatus`、`sepc`、`stval` 等 CSR 在 RV64 下保持 64 位；
  `sstatus.SUM/MXR` 字段可写并参与 Sv39/CUSTOM 权限检查。
- 可配置页大小、页表级数、PTE 大小（4/8B）、每级页表项数；RV32
  自定义几何和 STANDARD Sv32/Sv39/Sv48 保持原有约束。CUSTOM 要求
  `pageBits + levels*indexBits` 在 2..64 之间，且
  `pageBits + PPN 位宽 <= 64`，否则 elaboration 明确报错。
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
- TLB 可选超级页 CAM（Sv32 4MiB、Sv39 2MiB/1GiB、Sv48 继续扩展到
  1GiB/512GiB）；PTW 默认不把超级页填 TLB，打开 `TLB_SUPERPAGE` 后由
  PTW 返回 fillLevel 并填入超级页 CAM。
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
  到达后再做 tag/PMA/refill。默认要求 index+块内偏移落在页内，超出
  pageBits 的配置明确报错，避免静默 alias；显式打开
  `MMU_CACHE_VIPT_ALIAS_SAFE=y` 后允许该几何：
  - tag 改为完整物理行号（index 含 VPN 位时不能省略 PA 的对应位）；
  - refill 安装时按物理行失效其他 set 的同名副本；
  - 写直达 store 完成（BRESP 成功）后失效所有其他 set 的 alias 副本，
    store 按 VA index 未命中也能清掉旧副本；
  - 半填行只允许同一 set 的后续请求 partial-hit；
  - alias 扫描方式由 `MMU_CACHE_VIPT_ALIAS_SCAN_MODE` 选择：
    `all_sets`（默认）单拍并行比较所有 set，refill/store 无额外等待；
    `serial` 每周期只扫描一个 set，面积小，扫描期间反压新的 Cache
    请求，refill 最多增加 set 数拍。两种模式都在扫描完成前拒绝可能
    命中旧 alias 副本的请求。DCache 的 store 完成事件不能被反压，
    因此 serial 模式用小队列排队扫描；队列溢出时清空整个 Cache，
    绝不保留陈旧副本。
  - VIPT 下 cache line 不得大于页，跨页行无法用单个物理行回填，
    elaboration 会明确报错并提示改用 `MMU_CACHE_MODE=pipt`；
    PIPT 用物理地址索引/回填，允许 line > page，地址不会被截断。
  ICache 和 DCache 都有并行 VIPT 路径；DCache 的 load/store 都可以提前
  受理：store 先按 VA 登记写缓冲，只有携带匹配 VA 标签的 PA 返回后才发
  AW/W 或更新缓存镜像，页错误 kill 不会留下任何写副作用。VIPT cache
  使用物理 tag，sfence.vma/satp 切换不需要清 cache，也不会误命中旧映射。
- 可选 PTW PTE cache（set-associative），支持 4B/8B PTE；8B 表项保存
  低/高两个字，命中后无需重复两次页表读。
- IFU/LSU 双端口仲裁：两笔翻译可以同时在途，响应按 ID 路由；
  DMMU 对非访存指令组合直通。
- 所有新增注释为简体中文。

## 已验证

单元测试：

```text
总计 160+ 项 mill 单元测试通过，包含：
MMU 结构/行为/流、PTW pipeline、AXI 多 outstanding、CSR/U 裁剪、
IDU 特权级、PLIC、PMP、ICache VIPT、DCache VIPT、CacheViptAlias
（非标准页大小、PA alias 读写/取指、store 后 alias 一致、satp 切换）、
CacheAliasScan（8/16/32 set、all_sets/serial、Reg/SRAM、扫描期间 stall）、
CachePiptLargeLine（line > page、物理索引/回填、跨 64B 页偏移）、
LSUMMUVipt、LSUVipt、LSUDmmuVipt、LSUStoreVipt；以及 RV64 定向测试：
Sv39 三级 walk/权限/U-SUM/R-W-X/超级页/A-D/非规范地址、
Sv48 四级 walk、Sv39 TLB refill、64 位 satp WARL 与 sepc/stval；
RV64 CUSTOM：1KiB/3 级与 2KiB/4 级几何、43/47 位 VA、私有 PTE
字段位置、CUSTOM 超级页、TLB refill、权限/A/D、非规范 VA、
标准 MODE 拒绝与标准 Sv39/Sv48、RV32 自定义几何回归。
```

裸机测试（MMU 开启）：

```text
Sv32：S 模式访存              HIT GOOD
Sv32：U 模式访存              HIT GOOD
Sv32：S load page fault       HIT GOOD
Sv32：S store page fault      HIT GOOD
Sv32：S ecall + SRET          HIT GOOD
Sv39：csrc/Test/rv64-mmu-test HIT GOOD
      （4KiB walk、TLB refill、1GiB 超级页取指、load/store 页错误、
       U 页 SUM、A/D fault、非规范 VA）
Sv48：csrc/Test/rv64-sv48-test HIT GOOD
      （satp.MODE=9、四级 walk、1GiB 超级页取指、4KiB 映射、
       未映射页与非规范 VA 页错误、TLB 重访）
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
- `HAS_U=y`：M/S/U 三特权级或 M+U（`HAS_S=n`）配置。U 模式
  ecall 的 cause=8，MRET 按 mstatus.MPP 返回 U；有 S 时保留 U 页
  U 位/SUM 检查和 SRET 返回 U 的行为。
- `HAS_U=n`：MRET/SRET 的最低目标特权级固定为 S（mstatus.MPP/SPP
  的 U 编码在写入时夹到 S）；U 级 CSR 从实现集合裁剪；ecall cause=8
  选择路径被裁掉；TLB/PTW 的 U 位和 SUM 检查固定为不适用，
  S 模式可直接访问 U=1 的页（R/W/X 仍检查）。
- `HAS_S=n`：M-only 或 M+U 配置。S 级 CSR、`stvec/sepc/scause/stval`、
  `satp`、委托寄存器、S 中断视图和 S 模式入口逻辑在 elaboration 期
  直接裁剪；SRET/SFENCE.VMA 在译码阶段按非法指令处理。M+U 时
  U 的异常/中断全部进入 M（`mepc/mcause/mtval/mstatus.MPP`），
  `mcounteren` 保留用于门控 U 访问用户计数器。
- `HAS_MMU=y` 要求 `HAS_S=y`；`HAS_S=n + HAS_MMU=y` 即使
  `HAS_U=y` 也会在 elaboration 前明确报错，不会静默保留 S 逻辑。
  `HAS_TLB=y` 且 `HAS_MMU=n` 时 TLB 按未启用处理。
- `XLEN=64 + HAS_MMU=y` 默认（STANDARD）只接受标准 Sv39/Sv48：
  `MMU_LEVELS=3/4`、`MMU_PTE_SIZE_BYTES=8`、`MMU_PTE_FORMAT=riscv64`、
  `MMU_PAGE_TABLE_SIZE_BYTES=4096`、`MMU_PAGE_SIZE_BYTES=4096`。
  使用 `configs/rv64_mmu_defconfig` 可生成 Sv39 + TLB 配置。
- 非标准 RV64 几何必须显式设置 `MMU_GEOMETRY_MODE=custom`，并选择
  10..15 的 `MMU_CUSTOM_SATP_MODE`。生成器会在 JSON/header/env 中
  写入 `geometry_mode`、`non_standard` 和 `custom_satp_mode`；私有
  PTE 字段位置还必须使用 `MMU_PTE_FORMAT=custom`，否则按“伪装成
  标准编码”拒绝。`configs/rv64_custom_page_defconfig` 是 1KiB 示例。
- RV32 的既有自定义 32 位几何不变，仍使用 `satp.MODE=1`；
  `MMU_GEOMETRY_MODE=custom` 仅用于 RV64。
- 默认 `HAS_S=y/HAS_U=y` 时行为与原来一致；RV32/Sv32 的默认端子宽度
  与既有实现保持一致（MMURequest/PTW/TLB 默认 32 位）。

## 验证结论

- Nanos-lite：已修复 AXIRAM CPU 写地址 256KiB 回绕，输出启动日志并
  运行用户进程，最终到达 `halt(0)` / `HIT GOOD TRAP`，rc=0。
- HAS_S/HAS_U：M-only、M+U、M/S/U、S 裁剪组合均有 elaboration/结构测试；
  HAS_MMU 与 HAS_S=n 的不合法组合（包括 M+U）明确报错。
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
- CUSTOM 限制：页表级数受 TLB fillLevel 2 位限制为 1..4；有效 VA
  必须 2..64 位；`pageBits + PPN 位宽 <= 64`；RV64 satp.PPN 固定
  44 位，因此 `pageBits <= 20`（页大小上限 1MiB）；A/D 硬件写回仍只
  更新低 32 位字，因此 8B PTE 的 A/D 位落在高字时 elaboration 拒绝。
  PTE 标志位不能彼此重叠、不能与 PPN 字段重叠，也不能占用 PPN 高位
  以上的保留位区域。这些组合无法用现有 satp/页表格式/32 位页表写端口
  正确表达，已明确报错。
- Sv48 已完成参数化 walker/TLB、顶层 elaboration、PTW 四级 walk
  单元测试与独立裸机镜像 `csrc/Test/rv64-sv48-test`
  （`configs/rv64_sv48_defconfig`，独立 `BUILD_DIR` 构建）。默认裸机
  基线仍使用 Sv39；未配置对应几何的 satp.MODE 写入按 WARL 收敛到
  Bare(0)。
- RV64 顶层仍受外部 ysyxSoC 32 位 AXI 地址限制：页表和数据必须位于
  低 4GiB；Xbar 对更高地址返回 DECERR。
