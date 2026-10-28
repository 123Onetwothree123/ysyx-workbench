package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._

//MDU请求。
class ysyx_26030103_MDURequest(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"MDURequest XLEN 非法: $XLEN")
  val LHS = UInt(XLEN.W) //左操作数
  val RHS = UInt(XLEN.W) //右操作数
  val MDUOp = UInt(ysyx_26030103_MDUOp.Width.W) //M扩展操作码
}
//MDU结果。
class ysyx_26030103_MDUResponse(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"MDUResponse XLEN 非法: $XLEN")
  val Result = UInt(XLEN.W) //指令结果
}
//MDU路由握手接口。
class ysyx_26030103_MDUInterface(val XLEN: Int = 32) extends Bundle {
  val Req = Flipped(Decoupled(new ysyx_26030103_MDURequest(XLEN))) //输入请求
  val Resp = Decoupled(new ysyx_26030103_MDUResponse(XLEN)) //输出结果
  val Flush = Input(Bool()) //取消事务
}

abstract class ysyx_26030103_MDU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  require(config.UseM, "ysyx_26030103_MDU 只能在 RISCV_M=y 时例化")
  require(config.MDUImpl != ysyx_26030103_MDUImpl.External,
    "MDU_IMPL=external 只是预留接口，当前没有外部 MDU 实现")
  val io = IO(new ysyx_26030103_MDUInterface(config.XLEN))
}

object ysyx_26030103_MDU {
  def apply(config: ysyx_26030103_NPCConfig): ysyx_26030103_MDU = {
    require(config.UseM, "ysyx_26030103_MDU 只能在 RISCV_M=y 时创建")
    config.MDUImpl match {
      case ysyx_26030103_MDUImpl.Standard =>
        new ysyx_26030103_StandardMDU(config)
      case ysyx_26030103_MDUImpl.Rocket =>
        new ysyx_26030103_RocketMDU(config)
      case ysyx_26030103_MDUImpl.NutShell =>
        new ysyx_26030103_NutShellMDU(config)
      case ysyx_26030103_MDUImpl.Ibex =>
        new ysyx_26030103_IbexMDU(config)
      case ysyx_26030103_MDUImpl.Cve2 =>
        new ysyx_26030103_CVE2MDU(config)
      case ysyx_26030103_MDUImpl.Cv32e40p =>
        new ysyx_26030103_CV32E40PMDU(config)
      case ysyx_26030103_MDUImpl.PicoRV32 =>
        new ysyx_26030103_PicoRV32MDU(config)
      case ysyx_26030103_MDUImpl.External =>
        throw new NotImplementedError(
          "MDU_IMPL=external 只是预留接口，当前没有外部 MDU 实现"
        )
    }
  }
}
