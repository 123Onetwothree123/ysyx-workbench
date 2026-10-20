# NPC Sv32 MMU/TLB 状态

## 已接入

- 标准 Sv32：4KiB 页、2 级页表、4B PTE、4KiB 页表页。
- M 模式旁路；S/U 模式经 `satp` 翻译。
- `satp`、`sstatus`、`stvec`、`sepc`、`scause`、`stval`、`sscratch`、
  `medeleg`、`mideleg`、`mie/mip/sie/sip`。
- `mret`、`sret`、`sfence.vma`。
- 取指/load/store 页错误，以及向 S 模式委托异常。
- split ITLB/DTLB 与 unified TLB。
- TLB ASID、Global、RR/PLRU/LRU 替换。
- 独立只读 PTW AXI 通道，与 ICache 共用 IFU 读端口。
- IFU/LSU 共用一个 MMU 请求端口；DMMU 对非访存指令组合直通。

## 已验证

单元测试：

```text
ysyx_26030103_MMUTest             7
ysyx_26030103_MMUBehaviorTest     5
ysyx_26030103_MMUStreamTest       1
ysyx_26030103_CSRPrivilegeTest    3
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

- superpage
- 自定义 PTE
- A/D hardware 更新
- VIPT
- PTW 复用 DCache
- pipelined / out-of-order PTW
- PTW cache
- TLB SRAM 存储
- 多 refill 端口和非 follow_tlb 缺失队列

`MMU_PTW_OUTSTANDING` 当前仍按单笔 walk 处理；这是下一步要补的
数据结构，不能仅靠 Kconfig 数字改变行为。

## 未完成验证

Nanos-lite 在 MMU 开启的 NPC 上可执行约 17.6 万条指令，但之后发生
`PC=0x8000236c` 的 access fault，并被 NPC 的 AM 异常入口归为未处理事件。
这不是启动即失败，仍需继续定位该访问地址。
