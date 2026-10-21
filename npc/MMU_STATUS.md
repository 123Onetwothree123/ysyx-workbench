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
- TLB ASID、Global、RR/PLRU/LRU 替换。
- TLB Reg-CAM 和同步 SRAM 数据阵列两种存储。
- TLB 可选 4MiB 超级页 CAM；PTW 默认不把超级页填 TLB，打开
  `TLB_SUPERPAGE` 后由 PTW 返回 fillLevel=1 并填入超级页 CAM。
- 独立只读 PTW AXI 通道，或复用 LSU 读端口（`MMU_PTW_PATH=reuse_dcache`）。
- MMU 请求队列、响应表、序号重排和 1..16 个在途上下文。
- MMU 控制器可把多个 TLB miss 同时分发给 PTWCluster 的多个 walker；
  响应按请求序号返回，TLB 命中和 PTW 完成可以并行推进。
- PTW 返回 in_order/out_of_order 选择；MMU 层仍保证架构响应顺序。
- PIPT 和 VIPT：VIPT 使用虚拟地址选 index、物理地址做 tag；当前缓存
  访问仍等待翻译完成，不是 TLB/Cache 同拍并行流水。
- 可选 PTW PTE cache（set-associative）。
- IFU/LSU 双端口仲裁：两笔翻译可以同时在途，响应按 ID 路由；
  DMMU 对非访存指令组合直通。
- 所有新增注释为简体中文。

## 已验证

单元测试：

```text
ysyx_26030103_MMUTest             7
ysyx_26030103_MMUBehaviorTest     16
ysyx_26030103_MMUStreamTest       1
ysyx_26030103_CSRPrivilegeTest    10
ysyx_26030103_PLICTest             2
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
- `refillPorts>1` 和 `miss_queue_mode=global` 当前按单 refill
  端口、统一请求队列深度实现；没有 N 个独立 PTW 写回源。
- `HAS_PMP` 仍是 RESERVED，选择时生成器报错。
- `HAS_S/HAS_U` 仍不参与 RTL 面积裁剪；MMU 需要至少一个 S/U 特权级。

## 未完成

- 并行 VIPT TLB/Cache 流水。
- 多 refill 端口（当前只有一个 PTW 响应写回源）。
- `UTLB_LOOKUP_PORTS=2` 的双查找端口（当前统一 TLB 仍单查找端口）。
- PTW 级间流水化（当前为多个独立 walker）。
- PMP。
- Nanos-lite：direct-NPC 的 PMA/`CONFIG_MSIZE`/AXI RAM 已从 256KiB
  扩到 64MiB，重新构建后运行 240s（含启动）未再出现
  `PC=0x8000236c` 的 access fault，但 direct-NPC 没有 UART 回显，
  也没有退出，仍需判断是等待设备/输入还是死循环。
