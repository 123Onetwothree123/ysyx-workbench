# NPC Sv32 MMU/TLB 状态

## 已接入

- 标准 Sv32：4KiB 页、2 级页表、4B PTE、4KiB 页表页。
- M 模式旁路；S/U 模式经 `satp` 翻译。
- `satp`、`sstatus`、`stvec`、`sepc`、`scause`、`stval`、`sscratch`、
  `medeleg`、`mideleg`、`mie/mip/sie/sip`。
- `mret`、`sret`、`sfence.vma`。
- CLINT `mtimecmp` 与 timer interrupt（machine cause 7）。
- PLIC 寄存器/claim-complete 模块，已接入 Xbar 和顶层，并有
  priority/pending/claim/threshold 行为测试。
- 取指/load/store 页错误，以及向 S 模式委托异常。
- split ITLB/DTLB 与 unified TLB。
- TLB ASID、Global、RR/PLRU/LRU 替换。
- TLB Reg-CAM 和同步 SRAM 数据阵列两种存储。
- 可选 Sv32 4MiB 超级页；超级页当前不填充 TLB，每次走 PTW。
- 独立只读 PTW AXI 通道，或复用 LSU 读端口（`MMU_PTW_PATH=reuse_dcache`）。
- MMU 请求队列（深度可配）和请求/响应 ID。
- PIPT 和 VIPT：VIPT 使用虚拟地址选 index、物理地址做 tag；当前是
  先翻译后访问 Cache，不是并行 TLB/Cache 流水。
- 可选 PTW PTE cache（set-associative）。
- IFU/LSU 共用一个 MMU 请求端口；DMMU 对非访存指令组合直通。

## 已验证

单元测试：

```text
ysyx_26030103_MMUTest             7
ysyx_26030103_MMUBehaviorTest     10
ysyx_26030103_MMUStreamTest       1
ysyx_26030103_CSRPrivilegeTest    5
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

## 尚未实现（选择时会 elaboration error）

- 并行 VIPT TLB/Cache 流水
- pipelined / out-of-order PTW
- 多 refill 端口和非 follow_tlb 缺失队列

`PTWCluster` 支持 N 个独立 walker 和共享页表总线仲裁，MMU 入口已有
请求队列和 ID；当前控制器仍逐个发起上下文，真正的多上下文并行调度
仍可继续深化。

## 未完成验证

Nanos-lite 在 MMU 开启的 NPC 上可执行约 17.6 万条指令，但之后发生
`PC=0x8000236c` 的 access fault，并被 NPC 的 AM 异常入口归为未处理事件。
这不是启动即失败，仍需继续定位该访问地址。
