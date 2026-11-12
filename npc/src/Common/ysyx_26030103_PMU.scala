package ysyx_26030103.common
// 这是PMU事件号，让软件写进mhpmeventN的低位来选事件，0表示不计数
// 编号同时是事件总线上的位号
// 表是直接让AI做的，不是我自己手搓的，现在复查，删掉了垃圾注释，然后我重写了，顺便改了下变量名
object ysyx_26030103_PMUEventId {
  val BusWidth = 32 // 事件总线32位
  val CounterCount = 29 // mhpmcounter3到31，刚好一共29个
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
  val CSRInstruction = 11
  val Trap = 12
  val ITLBMiss = 13
  val DTLBMiss = 14
  val TLBRefill = 15
  val PTWWalk = 16
  val AXIRead = 17
  val AXIWrite = 18
  val IFUStall = 19
  val FlushCycle = 20
  val InterruptTaken = 21
  val ExternalIrq = 22
  val TimerIrq = 23
  val FPUActive = 24
  val MDUActive = 25
  val WbufFull = 26
  val UncachedAccess = 27
  val ICacheLineFill = 28
  val DCacheLineFill = 29
  val ICacheAccess = 30
  val ICacheMiss = 31
}
