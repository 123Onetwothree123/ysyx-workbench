package ysyx_26030103.common
import chisel3._
import chisel3.util._
abstract class ysyx_26030103_MDUResult(Width: Int) extends Bundle {
  val Result = UInt(Width.W)
}
abstract class ysyx_26030103_MDUHandshake[TReq <: Data, TResp <: Data](
    ReqType: TReq,
    RespType: TResp
) extends Bundle {
  val Request = Flipped(Decoupled(ReqType))
  val Response = Decoupled(RespType)
  val Flush = Input(Bool())
}
trait ysyx_26030103_MDUPerfPorts {
  this: Bundle =>
  val IterationCount = Output(UInt(16.W))
  val EarlyOut = Output(Bool())
}
