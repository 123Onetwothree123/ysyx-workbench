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
  private val PendingValid = RegInit(false.B)
  private val PendingIsMUL = RegInit(false.B)
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
  val CurrentRespValid = Mux(
    PendingIsMUL,
    MULUnit.IO.Resp.valid,
    DIVUnit.IO.Resp.valid
  ) && PendingValid
  val CanReplace = PendingValid && CurrentRespValid && io.Resp.ready
  val AcceptWindow = !PendingValid || CanReplace
  io.Req.ready := AcceptWindow && Mux(
    IsMULRequest,
    MULUnit.IO.Req.ready,
    DIVUnit.IO.Req.ready
  )
  val ReqFire = io.Req.valid && io.Req.ready
  MULUnit.IO.Req.valid := ReqFire && IsMULRequest
  DIVUnit.IO.Req.valid := ReqFire && IsDIVRequest
  MULUnit.IO.Resp.ready := PendingValid && PendingIsMUL && io.Resp.ready
  DIVUnit.IO.Resp.ready := PendingValid && !PendingIsMUL && io.Resp.ready
  io.Resp.valid := PendingValid && Mux(
    PendingIsMUL,
    MULUnit.IO.Resp.valid,
    DIVUnit.IO.Resp.valid
  )
  io.Resp.bits.Result := Mux(
    PendingIsMUL,
    MULUnit.IO.Resp.bits.Result,
    DIVUnit.IO.Resp.bits.Result
  )
  val RespFire = io.Resp.valid && io.Resp.ready
  when(io.Flush) {
    PendingValid := false.B
    PendingIsMUL := false.B
  }.elsewhen(ReqFire) {
    PendingValid := true.B
    PendingIsMUL := IsMULRequest
  }.elsewhen(RespFire) {
    PendingValid := false.B
  }
}
