package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//MDU只负责MUL/DIV路由和在途owner。
class ysyx_26030103_StandardMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_MDU(config) {
  private val mulUnit = Module(new ysyx_26030103_MULUnit(config))
  private val divUnit = Module(new ysyx_26030103_DIVUnit(config))
  // MUL与DIV独立执行，而此FIFO在两者延迟不同时仍保持架构请求顺序。
  // 其容量覆盖MUL的所有流水槽位，以及DIV单元的一项在途请求。
  private val ownerDepth = config.MDUMaxInflight
  private val OwnerQueue =
    Module(new ysyx_26030103_RingBuffer(ownerDepth, 1))
  private val ownerValid = !OwnerQueue.io.Empty
  private val headIsMUL = OwnerQueue.io.HeadData.asBool
  val request = io.Request.bits
  val isMULRequest = ysyx_26030103_MDUOp.IsMul(request.MDUOp)
  val isDIVRequest = ysyx_26030103_MDUOp.IsDiv(request.MDUOp)
  val mulSignedMode = ysyx_26030103_MDUOp.MulSignedMode(request.MDUOp)
  val mulTakeHigh = ysyx_26030103_MDUOp.TakeHigh(request.MDUOp)
  mulUnit.IO.Flush := io.Flush
  mulUnit.IO.Request.bits.LHS := request.LHS
  mulUnit.IO.Request.bits.RHS := request.RHS
  mulUnit.IO.Request.bits.SignedMode := mulSignedMode
  mulUnit.IO.Request.bits.TakeHigh := mulTakeHigh
  divUnit.IO.Flush := io.Flush
  divUnit.IO.Request.bits.LHS := request.LHS
  divUnit.IO.Request.bits.RHS := request.RHS
  divUnit.IO.Request.bits.Signed :=
    ysyx_26030103_MDUOp.IsSignedDiv(request.MDUOp)
  divUnit.IO.Request.bits.TakeRemainder :=
    ysyx_26030103_MDUOp.IsRemainder(request.MDUOp)
  val selectedResponseValid = Mux(
    headIsMUL,
    mulUnit.IO.Response.valid,
    divUnit.IO.Response.valid
  )
  mulUnit.IO.Response.ready :=
    !io.Flush && ownerValid && headIsMUL && io.Response.ready
  divUnit.IO.Response.ready :=
    !io.Flush && ownerValid && !headIsMUL && io.Response.ready
  io.Response.valid := !io.Flush && ownerValid && selectedResponseValid
  io.Response.bits.Result := Mux(
    headIsMUL,
    mulUnit.IO.Response.bits.Result,
    divUnit.IO.Response.bits.Result
  )
  val responseFire = io.Response.valid && io.Response.ready
  io.PerfDivIterations := divUnit.IO.PerfIterations
  io.PerfDivEarlyOut := divUnit.IO.PerfEarlyOut
  val ownerHasSpace = OwnerQueue.io.HasSpace
  val selectedRequestReady = Mux(
    isMULRequest,
    mulUnit.IO.Request.ready,
    divUnit.IO.Request.ready
  )
  io.Request.ready := !io.Flush && ownerHasSpace && selectedRequestReady
  mulUnit.IO.Request.valid :=
    !io.Flush && io.Request.valid && ownerHasSpace && isMULRequest
  divUnit.IO.Request.valid :=
    !io.Flush && io.Request.valid && ownerHasSpace && isDIVRequest
  val requestFire = io.Request.valid && io.Request.ready
  OwnerQueue.io.Enqueue := requestFire
  OwnerQueue.io.EnqueueData := isMULRequest.asUInt
  OwnerQueue.io.Dequeue := responseFire
  OwnerQueue.io.Flush := io.Flush
  assert(
    mulUnit.IO.Request.fire === (requestFire && isMULRequest),
    "MDU MUL request and owner metadata became misaligned"
  )
  assert(
    divUnit.IO.Request.fire === (requestFire && isDIVRequest),
    "MDU DIV request and owner metadata became misaligned"
  )
  assert(
    !mulUnit.IO.Response.fire || (ownerValid && headIsMUL && responseFire),
    "MDU consumed a MUL response out of owner order"
  )
  assert(
    !divUnit.IO.Response.fire || (ownerValid && !headIsMUL && responseFire),
    "MDU consumed a DIV response out of owner order"
  )
}
