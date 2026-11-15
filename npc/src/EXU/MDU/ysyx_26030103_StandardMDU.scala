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
  private val ownerPointerWidth = log2Ceil(ownerDepth).max(1)
  private val ownerCountWidth = log2Ceil(ownerDepth + 1)
  private val ownerIsMULQueue =
    RegInit(VecInit(Seq.fill(ownerDepth)(false.B)))
  private val ownerHead = RegInit(0.U(ownerPointerWidth.W))
  private val ownerTail = RegInit(0.U(ownerPointerWidth.W))
  private val ownerCount = RegInit(0.U(ownerCountWidth.W))
  private val ownerValid = ownerCount =/= 0.U
  private val headIsMUL =
    if (ownerDepth == 1) ownerIsMULQueue(0) else ownerIsMULQueue(ownerHead)
  val request = io.Request.bits
  val isMULRequest = request.MDUOp <= ysyx_26030103_MDUOp.MULHU
  val isDIVRequest = !isMULRequest
  val mulSignedMode = MuxLookup(
    request.MDUOp,
    ysyx_26030103_MULSignedMode.UU
  )(
    Seq(
      ysyx_26030103_MDUOp.MULH -> ysyx_26030103_MULSignedMode.SS,
      ysyx_26030103_MDUOp.MULHSU -> ysyx_26030103_MULSignedMode.SU,
      ysyx_26030103_MDUOp.MULHU -> ysyx_26030103_MULSignedMode.UU
    )
  )
  val mulTakeHigh = request.MDUOp =/= ysyx_26030103_MDUOp.MUL
  mulUnit.IO.Flush := io.Flush
  mulUnit.IO.Request.bits.LHS := request.LHS
  mulUnit.IO.Request.bits.RHS := request.RHS
  mulUnit.IO.Request.bits.SignedMode := mulSignedMode
  mulUnit.IO.Request.bits.TakeHigh := mulTakeHigh
  divUnit.IO.Flush := io.Flush
  divUnit.IO.Request.bits.LHS := request.LHS
  divUnit.IO.Request.bits.RHS := request.RHS
  divUnit.IO.Request.bits.Signed :=
    request.MDUOp === ysyx_26030103_MDUOp.DIV ||
      request.MDUOp === ysyx_26030103_MDUOp.REM
  divUnit.IO.Request.bits.TakeRemainder :=
    request.MDUOp === ysyx_26030103_MDUOp.REM ||
      request.MDUOp === ysyx_26030103_MDUOp.REMU
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
  val ownerHasSpace = ownerCount < ownerDepth.U || responseFire
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
  val nextOwnerHead = Mux(
    ownerHead === (ownerDepth - 1).U,
    0.U,
    ownerHead + 1.U
  )
  val nextOwnerTail = Mux(
    ownerTail === (ownerDepth - 1).U,
    0.U,
    ownerTail + 1.U
  )
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
  assert(ownerCount <= ownerDepth.U, "MDU owner FIFO overflow")
  when(io.Flush) {
    ownerHead := 0.U
    ownerTail := 0.U
    ownerCount := 0.U
  }.elsewhen(requestFire || responseFire) {
    when(requestFire) {
      if (ownerDepth == 1) {
        ownerIsMULQueue(0) := isMULRequest
      } else {
        ownerIsMULQueue(ownerTail) := isMULRequest
      }
      ownerTail := nextOwnerTail
    }
    when(responseFire) {
      ownerHead := nextOwnerHead
    }
    when(requestFire && !responseFire) {
      ownerCount := ownerCount + 1.U
    }.elsewhen(!requestFire && responseFire) {
      ownerCount := ownerCount - 1.U
    }
  }
}
