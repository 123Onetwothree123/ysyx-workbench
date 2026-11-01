package ysyx_26030103.common

/** 架构 PMU 的事件编号与硬件规模。
  *
  * 事件编号是 `mhpmeventN` 低 8 位的实现定义编码；0 表示关闭计数。
  * 这些编号同时用于 RTL 顶层事件总线、固件/设备树映射和软件测试。
  */
object ysyx_26030103_PMUEventId {
  val BusWidth = 32
  val CounterCount = 29

  // SBI PMU 的通用 hardware event 1..6 与本表保持同号或由设备树映射。
  val Cycles = 1
  val Instructions = 2
  val CacheReference = 3
  val CacheMiss = 4
  val DCacheAccess = 5
  val DCacheMiss = 6
  val BranchInstruction = 7
  val BranchMispredict = 8
  val LoadInstruction = 9
  val StoreInstruction = 10
  val CsrInstruction = 11
  val Trap = 12
  val ITlbMiss = 13
  val DTlbMiss = 14
  val TlbRefill = 15
  val PtwWalk = 16
  val AxiRead = 17
  val AxiWrite = 18
  val IfuStall = 19
  val FlushCycle = 20
  val InterruptTaken = 21
  val ExternalIrq = 22
  val TimerIrq = 23
  val FpuActive = 24
  val MduActive = 25
  val WbufFull = 26
  val UncachedAccess = 27
  val ICacheLineFill = 28
  val DCacheLineFill = 29
  val ICacheAccess = 30
  val ICacheMiss = 31
}
