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
      val MDUInstQueue = Module(
        new ysyx_26030103_RingBuffer(MDUQueueDepth, io.in.bits.getWidth)
      )
      PendingMDU := !MDUInstQueue.io.Empty
      PendingMDUInst :=
        MDUInstQueue.io.HeadData.asTypeOf(chiselTypeOf(io.in.bits))

      // MDU的Flush必须同时屏蔽旧Resp；访存故障提交时，所有已经
      // 接收的年轻M指令都属于待取消事务。
      MDUUnit.io.Flush := io.MemTrapCommit
      MDUInstQueue.io.Flush := io.MemTrapCommit
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
      MDUInstQueue.io.Dequeue := MDUResponseFire
      val MDUQueueHasSpace = MDUInstQueue.io.HasSpace
      MDURequestReady := IsMDUInstruction && !MDUInputBlockedByIRQ &&
        MDUQueueHasSpace && MDUUnit.io.Request.ready
      MDUUnit.io.Request.valid := io.in.valid && IsMDUInstruction &&
        !MDUInputBlockedByIRQ && MDUQueueHasSpace && !FPPending
      MDURequestFire := MDUUnit.io.Request.valid && MDUUnit.io.Request.ready
      MDUInstQueue.io.Enqueue := MDURequestFire
      MDUInstQueue.io.EnqueueData := inst.asUInt
      assert(!MDURequestFire || MDUQueueHasSpace, "EXU MDU instruction queue overflow")
      assert(
        !MDUResponseFire || PendingMDU,
        "EXU received an MDU response without an instruction"
      )

      // 现有的单一冒险/转发端口描述MDU队首。为每个更年轻的已排队写入者，
      // 以及当前正在等待入队的MDU输入构建掩码。
      var HiddenMDUWriteMask: UInt = 0.U(32.W)
      for (offset <- 1 until MDUQueueDepth) {
        val extendedHead = Cat(0.U(1.W), MDUInstQueue.io.HeadPointer)
        val rawIndex =
          extendedHead + offset.U((MDUInstQueue.PointerWidth + 1).W)
        val wrappedIndex = Mux(
          rawIndex >= MDUQueueDepth.U,
          rawIndex - MDUQueueDepth.U,
          rawIndex
        )
        val entry = MDUInstQueue.io
          .SlotData(wrappedIndex(MDUInstQueue.PointerWidth - 1, 0))
          .asTypeOf(chiselTypeOf(io.in.bits))
        val entryWrites =
          offset.U < MDUInstQueue.io.Occupancy && entry.RegisterWrite &&
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
