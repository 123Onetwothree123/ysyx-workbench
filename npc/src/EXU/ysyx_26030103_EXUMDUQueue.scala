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
      val mduUnit = Module(ysyx_26030103_MDU(config))
      // MDU请求保存在顺序指令队列中。这样，即使最老结果仍在
      // 等待LSU/WBU级，流水乘法器也能接收连续的M扩展指令。
      val mduQueueDepth = config.MDUMaxInflight
      val mduQueuePointerWidth = log2Ceil(mduQueueDepth).max(1)
      val mduQueueCountWidth = log2Ceil(mduQueueDepth + 1)
      val mduInstQueue = RegInit(
        VecInit(Seq.fill(mduQueueDepth)(0.U.asTypeOf(chiselTypeOf(io.in.bits))))
      )
      val mduQueueHead = RegInit(0.U(mduQueuePointerWidth.W))
      val mduQueueTail = RegInit(0.U(mduQueuePointerWidth.W))
      val mduQueueCount = RegInit(0.U(mduQueueCountWidth.W))
      pendingMDU := mduQueueCount =/= 0.U
      pendingMDUInst := mduInstQueue(mduQueueHead)

      // MDU的Flush必须同时屏蔽旧Resp；访存故障提交时，所有已经
      // 接收的年轻M指令都属于待取消事务。
      mduUnit.io.Flush := io.MemTrapCommit
      mduUnit.io.Request.bits.LHS := mduRequestLHS
      mduUnit.io.Request.bits.RHS := mduRequestRHS
      mduUnit.io.Request.bits.MDUOp := mduRequestOp

      val mduResponseReady = pendingMDU && io.out.ready &&
        !io.MemTrapCommit && !blockPendingMDUForMEM
      mduUnit.io.Response.ready := mduResponseReady
      mduResponseValid := mduUnit.io.Response.valid
      mduResponseResult := mduUnit.io.Response.bits.Result
      mduPerfDivIterations := mduUnit.io.PerfDivIterations
      mduPerfDivEarlyOut := mduUnit.io.PerfDivEarlyOut
      mduResponseFire := mduUnit.io.Response.valid && mduResponseReady
      val mduQueueHasSpace = mduQueueCount < mduQueueDepth.U || mduResponseFire
      mduRequestReady := isMDUInstruction && !mduInputBlockedByIRQ &&
        mduQueueHasSpace && mduUnit.io.Request.ready
      mduUnit.io.Request.valid := io.in.valid && isMDUInstruction &&
        !mduInputBlockedByIRQ && mduQueueHasSpace && !fpPending
      mduRequestFire := mduUnit.io.Request.valid && mduUnit.io.Request.ready
      assert(!mduRequestFire || mduQueueHasSpace, "EXU MDU instruction queue overflow")
      assert(
        !mduResponseFire || pendingMDU,
        "EXU received an MDU response without an instruction"
      )
      assert(mduQueueCount <= mduQueueDepth.U, "EXU MDU instruction queue overflowed")

      val nextMDUQueueTail = Mux(
        mduQueueTail === (mduQueueDepth - 1).U,
        0.U,
        mduQueueTail + 1.U
      )
      val nextMDUQueueHead = Mux(
        mduQueueHead === (mduQueueDepth - 1).U,
        0.U,
        mduQueueHead + 1.U
      )
      when(io.MemTrapCommit) {
        mduQueueCount := 0.U
        mduQueueHead := 0.U
        mduQueueTail := 0.U
      }.elsewhen(mduRequestFire || mduResponseFire) {
        when(mduRequestFire) {
          mduInstQueue(mduQueueTail) := inst
          mduQueueTail := nextMDUQueueTail
        }
        when(mduResponseFire) {
          mduQueueHead := nextMDUQueueHead
        }
        when(mduRequestFire && !mduResponseFire) {
          mduQueueCount := mduQueueCount + 1.U
        }.elsewhen(!mduRequestFire && mduResponseFire) {
          mduQueueCount := mduQueueCount - 1.U
        }
      }

      // 现有的单一冒险/转发端口描述MDU队首。为每个更年轻的已排队写入者，
      // 以及当前正在等待入队的MDU输入构建掩码。
      var hiddenMDUWriteMask: UInt = 0.U(32.W)
      for (offset <- 1 until mduQueueDepth) {
        val extendedHead = Cat(0.U(1.W), mduQueueHead)
        val rawIndex = extendedHead + offset.U((mduQueuePointerWidth + 1).W)
        val wrappedIndex = Mux(
          rawIndex >= mduQueueDepth.U,
          rawIndex - mduQueueDepth.U,
          rawIndex
        )
        val entry = mduInstQueue(wrappedIndex(mduQueuePointerWidth - 1, 0))
        val entryWrites = offset.U < mduQueueCount && entry.RegisterWrite &&
          entry.Rd =/= 0.U
        hiddenMDUWriteMask = hiddenMDUWriteMask |
          Mux(entryWrites, UIntToOH(entry.Rd, 32), 0.U(32.W))
      }
      val currentMDUWrite = pendingMDU && io.in.valid && isMDUInstruction &&
        inst.RegisterWrite && inst.Rd =/= 0.U
      val currentMDUWriteMask =
        Mux(currentMDUWrite, UIntToOH(inst.Rd, 32), 0.U(32.W))
      mduHiddenWriteMask := hiddenMDUWriteMask | currentMDUWriteMask
    }
  }
}
