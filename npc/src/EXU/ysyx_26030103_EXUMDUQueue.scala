package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._

// M 扩展的顺序请求队列：MDU 模块只在 UseM 时生成。
private[exu] trait EXUMDUQueue { this: ysyx_26030103_EXU =>
  def mduQueueBody(): Unit = {
    if (config.UseM) {
      val MDUUnit = Module(ysyx_26030103_MDU(config))
      // MDU请求保存在顺序指令队列中。这样，即使最老结果仍在
      // 等待LSU/WBU级，流水乘法器也能接收连续的M扩展指令。
      val MDUQueueDepth = config.MDUMaxInflight
      val MDUQueuePointerWidth = log2Ceil(MDUQueueDepth).max(1)
      val MDUQueueCountWidth = log2Ceil(MDUQueueDepth + 1)
      val MDUInstQueue = RegInit(
        VecInit(Seq.fill(MDUQueueDepth)(0.U.asTypeOf(chiselTypeOf(io.in.bits))))
      )
      val MDUQueueHead = RegInit(0.U(MDUQueuePointerWidth.W))
      val MDUQueueTail = RegInit(0.U(MDUQueuePointerWidth.W))
      val MDUQueueCount = RegInit(0.U(MDUQueueCountWidth.W))
      PendingMDU := MDUQueueCount =/= 0.U
      PendingMDUInst := MDUInstQueue(MDUQueueHead)

      // MDU的Flush必须同时屏蔽旧Resp；访存故障提交时，所有已经
      // 接收的年轻M指令都属于待取消事务。
      MDUUnit.io.Flush := io.MemTrapCommit
      MDUUnit.io.Request.bits.LHS := MDURequestLHS
      MDUUnit.io.Request.bits.RHS := MDURequestRHS
      MDUUnit.io.Request.bits.MDUOp := MDURequestOp

      val MDUResponseReady = PendingMDU && io.out.ready &&
        !io.MemTrapCommit && !BlockIRQForPipeline
      MDUUnit.io.Response.ready := MDUResponseReady
      MDUResponseValid := MDUUnit.io.Response.valid
      MDUResponseResult := MDUUnit.io.Response.bits.Result
      MDUPerfDivIterations := MDUUnit.io.PerfDivIterations
      MDUPerfDivEarlyOut := MDUUnit.io.PerfDivEarlyOut
      MDUResponseFire := MDUUnit.io.Response.valid && MDUResponseReady
      val MDUQueueHasSpace = MDUQueueCount < MDUQueueDepth.U || MDUResponseFire
      MDURequestReady := IsMDUInstruction && !MDUInputBlockedByIRQ &&
        MDUQueueHasSpace && MDUUnit.io.Request.ready
      MDUUnit.io.Request.valid := io.in.valid && IsMDUInstruction &&
        !MDUInputBlockedByIRQ && MDUQueueHasSpace && !FPPending
      MDURequestFire := MDUUnit.io.Request.valid && MDUUnit.io.Request.ready
      assert(!MDURequestFire || MDUQueueHasSpace, "EXU MDU instruction queue overflow")
      assert(
        !MDUResponseFire || PendingMDU,
        "EXU received an MDU response without an instruction"
      )
      assert(MDUQueueCount <= MDUQueueDepth.U, "EXU MDU instruction queue overflowed")

      val NextMDUQueueTail = Mux(
        MDUQueueTail === (MDUQueueDepth - 1).U,
        0.U,
        MDUQueueTail + 1.U
      )
      val NextMDUQueueHead = Mux(
        MDUQueueHead === (MDUQueueDepth - 1).U,
        0.U,
        MDUQueueHead + 1.U
      )
      when(io.MemTrapCommit) {
        MDUQueueCount := 0.U
        MDUQueueHead := 0.U
        MDUQueueTail := 0.U
      }.elsewhen(MDURequestFire || MDUResponseFire) {
        when(MDURequestFire) {
          MDUInstQueue(MDUQueueTail) := inst
          MDUQueueTail := NextMDUQueueTail
        }
        when(MDUResponseFire) {
          MDUQueueHead := NextMDUQueueHead
        }
        when(MDURequestFire && !MDUResponseFire) {
          MDUQueueCount := MDUQueueCount + 1.U
        }.elsewhen(!MDURequestFire && MDUResponseFire) {
          MDUQueueCount := MDUQueueCount - 1.U
        }
      }

      // 现有的单一冒险/转发端口描述MDU队首。为每个更年轻的已排队写入者，
      // 以及当前正在等待入队的MDU输入构建掩码。
      var HiddenMDUWriteMask: UInt = 0.U(32.W)
      for (offset <- 1 until MDUQueueDepth) {
        val extendedHead = Cat(0.U(1.W), MDUQueueHead)
        val rawIndex = extendedHead + offset.U((MDUQueuePointerWidth + 1).W)
        val wrappedIndex = Mux(
          rawIndex >= MDUQueueDepth.U,
          rawIndex - MDUQueueDepth.U,
          rawIndex
        )
        val entry = MDUInstQueue(wrappedIndex(MDUQueuePointerWidth - 1, 0))
        val entryWrites = offset.U < MDUQueueCount && entry.RegisterWrite &&
          entry.Rd =/= 0.U
        HiddenMDUWriteMask = HiddenMDUWriteMask |
          Mux(entryWrites, UIntToOH(entry.Rd, 32), 0.U(32.W))
      }
      val CurrentMDUWrite = PendingMDU && io.in.valid && IsMDUInstruction &&
        inst.RegisterWrite && inst.Rd =/= 0.U
      val CurrentMDUWriteMask =
        Mux(CurrentMDUWrite, UIntToOH(inst.Rd, 32), 0.U(32.W))
      MDUHiddenWriteMask := HiddenMDUWriteMask | CurrentMDUWriteMask
    }
  }
}
