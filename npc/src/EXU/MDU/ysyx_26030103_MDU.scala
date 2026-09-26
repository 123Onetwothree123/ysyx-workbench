package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
//MDU请求。
class ysyx_26030103_MDURequest extends Bundle {
  val LHS = UInt(32.W) //左操作数
  val RHS = UInt(32.W) //右操作数
  val MDUOp = UInt(ysyx_26030103_MDUOp.Width.W) //M扩展操作码
}
//MDU结果。
class ysyx_26030103_MDUResponse extends Bundle {
  val Result = UInt(32.W) //指令结果
}
//MDU路由握手接口。
class ysyx_26030103_MDUInterface extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MDURequest)) //输入请求
  val Resp = Decoupled(new ysyx_26030103_MDUResponse) //输出结果
  val Flush = Input(Bool()) //取消事务
}
//MDU只负责MUL/DIV路由和在途owner。
class ysyx_26030103_MDU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends Module {
  val io = IO(new ysyx_26030103_MDUInterface) //MDU接口
  private val MULUnit = Module(new ysyx_26030103_MULUnit(config))
  private val DIVUnit = Module(new ysyx_26030103_DIVUnit(config))
  // MUL and DIV execute independently, while this FIFO preserves architectural
  // request order when their latencies differ.  Its capacity covers every MUL
  // pipeline slot plus the DIV unit's one outstanding request.
  private val OwnerDepth = config.MDUMaxInflight
  private val OwnerPtrWidth = log2Ceil(OwnerDepth).max(1)
  private val OwnerCountWidth = log2Ceil(OwnerDepth + 1)
  private val OwnerIsMULQueue = Reg(Vec(OwnerDepth, Bool()))
  private val OwnerHead = RegInit(0.U(OwnerPtrWidth.W))
  private val OwnerTail = RegInit(0.U(OwnerPtrWidth.W))
  private val OwnerCount = RegInit(0.U(OwnerCountWidth.W))
  private val OwnerValid = OwnerCount =/= 0.U
  private val HeadIsMUL =
    if (OwnerDepth == 1) OwnerIsMULQueue(0) else OwnerIsMULQueue(OwnerHead)
  val Request = io.Req.bits
  val IsMULRequest = Request.MDUOp <= ysyx_26030103_MDUOp.MULHU
  val IsDIVRequest = !IsMULRequest
  val MULSignedMode = MuxLookup(
    Request.MDUOp,
    ysyx_26030103_MULSignedMode.UU
  )(
    Seq(
      ysyx_26030103_MDUOp.MULH -> ysyx_26030103_MULSignedMode.SS,
      ysyx_26030103_MDUOp.MULHSU -> ysyx_26030103_MULSignedMode.SU,
      ysyx_26030103_MDUOp.MULHU -> ysyx_26030103_MULSignedMode.UU
    )
  )
  val MULTakeHigh = Request.MDUOp =/= ysyx_26030103_MDUOp.MUL
  MULUnit.IO.Flush := io.Flush
  MULUnit.IO.Req.bits.LHS := Request.LHS
  MULUnit.IO.Req.bits.RHS := Request.RHS
  MULUnit.IO.Req.bits.SignedMode := MULSignedMode
  MULUnit.IO.Req.bits.TakeHigh := MULTakeHigh
  DIVUnit.IO.Flush := io.Flush
  DIVUnit.IO.Req.bits.LHS := Request.LHS
  DIVUnit.IO.Req.bits.RHS := Request.RHS
  DIVUnit.IO.Req.bits.Signed :=
    Request.MDUOp === ysyx_26030103_MDUOp.DIV ||
      Request.MDUOp === ysyx_26030103_MDUOp.REM
  DIVUnit.IO.Req.bits.TakeRemainder :=
    Request.MDUOp === ysyx_26030103_MDUOp.REM ||
      Request.MDUOp === ysyx_26030103_MDUOp.REMU
  val SelectedRespValid = Mux(
    HeadIsMUL,
    MULUnit.IO.Resp.valid,
    DIVUnit.IO.Resp.valid
  )
  MULUnit.IO.Resp.ready :=
    !io.Flush && OwnerValid && HeadIsMUL && io.Resp.ready
  DIVUnit.IO.Resp.ready :=
    !io.Flush && OwnerValid && !HeadIsMUL && io.Resp.ready
  io.Resp.valid := !io.Flush && OwnerValid && SelectedRespValid
  io.Resp.bits.Result := Mux(
    HeadIsMUL,
    MULUnit.IO.Resp.bits.Result,
    DIVUnit.IO.Resp.bits.Result
  )
  val RespFire = io.Resp.valid && io.Resp.ready
  val OwnerHasSpace = OwnerCount < OwnerDepth.U || RespFire
  val SelectedReqReady = Mux(
    IsMULRequest,
    MULUnit.IO.Req.ready,
    DIVUnit.IO.Req.ready
  )
  io.Req.ready := !io.Flush && OwnerHasSpace && SelectedReqReady
  MULUnit.IO.Req.valid :=
    !io.Flush && io.Req.valid && OwnerHasSpace && IsMULRequest
  DIVUnit.IO.Req.valid :=
    !io.Flush && io.Req.valid && OwnerHasSpace && IsDIVRequest
  val ReqFire = io.Req.valid && io.Req.ready
  val NextOwnerHead = Mux(
    OwnerHead === (OwnerDepth - 1).U,
    0.U,
    OwnerHead + 1.U
  )
  val NextOwnerTail = Mux(
    OwnerTail === (OwnerDepth - 1).U,
    0.U,
    OwnerTail + 1.U
  )
  assert(
    MULUnit.IO.Req.fire === (ReqFire && IsMULRequest),
    "MDU MUL request and owner metadata became misaligned"
  )
  assert(
    DIVUnit.IO.Req.fire === (ReqFire && IsDIVRequest),
    "MDU DIV request and owner metadata became misaligned"
  )
  assert(
    !MULUnit.IO.Resp.fire || (OwnerValid && HeadIsMUL && RespFire),
    "MDU consumed a MUL response out of owner order"
  )
  assert(
    !DIVUnit.IO.Resp.fire || (OwnerValid && !HeadIsMUL && RespFire),
    "MDU consumed a DIV response out of owner order"
  )
  assert(OwnerCount <= OwnerDepth.U, "MDU owner FIFO overflow")
  when(io.Flush) {
    OwnerHead := 0.U
    OwnerTail := 0.U
    OwnerCount := 0.U
  }.elsewhen(ReqFire || RespFire) {
    when(ReqFire) {
      if (OwnerDepth == 1) {
        OwnerIsMULQueue(0) := IsMULRequest
      } else {
        OwnerIsMULQueue(OwnerTail) := IsMULRequest
      }
      OwnerTail := NextOwnerTail
    }
    when(RespFire) {
      OwnerHead := NextOwnerHead
    }
    when(ReqFire && !RespFire) {
      OwnerCount := OwnerCount + 1.U
    }.elsewhen(!ReqFire && RespFire) {
      OwnerCount := OwnerCount - 1.U
    }
  }
}
