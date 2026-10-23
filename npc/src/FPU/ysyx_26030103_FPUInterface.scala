package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config._

/** FPU 操作码。
  *
  * 操作码只描述 RISC-V 浮点指令的语义类别；具体精度由 `FpuRequest.fmt`
  * 选择（S/D），整数转换的源/目的宽度由操作码本身区分。后端不得把
  * vendor 私有操作码暴露到该接口。
  */
object ysyx_26030103_FpuOp {
  val Width: Int = 6

  val FAdd = 0.U(Width.W)
  val FSub = 1.U(Width.W)
  val FMul = 2.U(Width.W)
  val FFma = 3.U(Width.W)
  val FFms = 4.U(Width.W)
  val FFnma = 5.U(Width.W)
  val FFnms = 6.U(Width.W)
  val FDiv = 7.U(Width.W)
  val FSqrt = 8.U(Width.W)

  val FSgnj = 9.U(Width.W)
  val FSgnjn = 10.U(Width.W)
  val FSgnjx = 11.U(Width.W)
  val FMin = 12.U(Width.W)
  val FMax = 13.U(Width.W)

  // 浮点 <-> 整数转换；W/WU 与 L/LU 共用同一操作码，由 fmt 无法
  // 表达的整数宽度在这里固定为 XLEN 对应语义，后续可由上层拆分。
  val FCvtFpToInt = 14.U(Width.W)
  val FCvtFpToUInt = 15.U(Width.W)
  val FCvtIntToFp = 16.U(Width.W)
  val FCvtUIntToFp = 17.U(Width.W)
  val FMvFpToInt = 18.U(Width.W)
  val FMvIntToFp = 19.U(Width.W)

  val FEq = 20.U(Width.W)
  val FLt = 21.U(Width.W)
  val FLe = 22.U(Width.W)
  val FClass = 23.U(Width.W)

  // S <-> D 精度转换（FCVT.S.D / FCVT.D.S）。
  val FCvtFpToFp = 24.U(Width.W)
}

/** 浮点格式编码，对应 RISC-V 指令的 fmt 字段。 */
object ysyx_26030103_FpuFmt {
  val Width: Int = 2
  val S = 0.U(Width.W)
  val D = 1.U(Width.W)
  // H/Q 只保留编码，当前 F/D 配置不会产生。
  val H = 2.U(Width.W)
  val Q = 3.U(Width.W)
}

/** 舍入模式编码，对应 RISC-V frm/funct3。 */
object ysyx_26030103_FpuRm {
  val Width: Int = 3
  val RNE = 0.U(Width.W)
  val RTZ = 1.U(Width.W)
  val RDN = 2.U(Width.W)
  val RUP = 3.U(Width.W)
  val RMM = 4.U(Width.W)
  val DYN = 7.U(Width.W)
}

/** fflags 五位异常标志的位位置。 */
object ysyx_26030103_FpuFlags {
  val Width: Int = 5
  val NX = 0
  val UF = 1
  val OF = 2
  val DZ = 3
  val NV = 4
}

/** FPU 请求。
  *
  * `rs1/rs2/rs3` 始终使用 FLEN 位 IEEE 754 位模式；浮点寄存器堆的
  * NaN-boxing 由上层/后端在边界统一处理。valid/ready 由外层
  * `Decoupled` 握手提供，不重复放进 bits。
  */
class ysyx_26030103_FpuRequest(val FLEN: Int = 64) extends Bundle {
  require(FLEN == 32 || FLEN == 64, s"FpuRequest FLEN 非法: $FLEN")
  val op = UInt(ysyx_26030103_FpuOp.Width.W)
  val fmt = UInt(ysyx_26030103_FpuFmt.Width.W)
  val rm = UInt(ysyx_26030103_FpuRm.Width.W)
  val rs1 = UInt(FLEN.W)
  val rs2 = UInt(FLEN.W)
  val rs3 = UInt(FLEN.W)
}

/** FPU 响应：IEEE 754 位模式结果 + 累积到 fflags 的 5 位异常。 */
class ysyx_26030103_FpuResponse(val FLEN: Int = 64) extends Bundle {
  require(FLEN == 32 || FLEN == 64, s"FpuResponse FLEN 非法: $FLEN")
  val result = UInt(FLEN.W)
  val fflags = UInt(ysyx_26030103_FpuFlags.Width.W)
}

/** 所有 FPU 后端共用的顶层接口。
  *
  * - `req.valid`/`req.ready`：请求握手；
  * - `resp.valid`/`resp.ready`：结果握手，允许后端有可变/多拍延迟；
  * - 后端不得依赖固定 latency，必须遵守 Decoupled 反压。
  */
class ysyx_26030103_FPUInterface(val FLEN: Int = 64) extends Bundle {
  require(FLEN == 32 || FLEN == 64, s"FPUInterface FLEN 非法: $FLEN")
  val req = Flipped(Decoupled(new ysyx_26030103_FpuRequest(FLEN)))
  val resp = Decoupled(new ysyx_26030103_FpuResponse(FLEN))
}

/** FPU 顶层抽象骨架。
  *
  * 后端实现继承本类，只实现 `io.req`/`io.resp` 的语义；上层（IDU/EXU/
  * 浮点寄存器堆）只依赖 `ysyx_26030103_FPUInterface`，不直接引用任何
  * vendor 模块或内部信号。
  */
abstract class ysyx_26030103_FPU(val Config: ysyx_26030103_NPCConfig)
    extends Module {
  require(Config.UseF,
    "ysyx_26030103_FPU 只能在 RISCV_F=y 时例化；软浮点构建不应生成 FPU")
  require(Config.FPUImpl != ysyx_26030103_FPUImpl.None,
    "ysyx_26030103_FPU 需要具体后端，FPU_IMPL=none 不能例化")
  val io = IO(new ysyx_26030103_FPUInterface(Config.FPULen))
}

/** FPU 工厂：按后端选择实现。
  *
  * Fudian 已通过源码级 vendor 接入实现；HardFloat 仍在后续任务中接入。
  * 工厂只返回统一的 `ysyx_26030103_FPU` 抽象类型，上层不依赖 vendor。
  */
object ysyx_26030103_FPU {
  def apply(Config: ysyx_26030103_NPCConfig): ysyx_26030103_FPU = {
    require(Config.UseF,
      "ysyx_26030103_FPU 只能在 RISCV_F=y 时创建")
    Config.FPUImpl match {
      case ysyx_26030103_FPUImpl.None =>
        throw new IllegalArgumentException(
          "FPU_IMPL=none 不能创建 FPU；请选择 hardfloat/fudian")
      case ysyx_26030103_FPUImpl.HardFloat =>
        throw new NotImplementedError(
          "Berkeley HardFloat 后端尚未接入：vendor 实现与 " +
            "FPU_HARDFLOAT_* 参数映射完成后才能启用 RISCV_F/D")
      case ysyx_26030103_FPUImpl.Fudian =>
        new ysyx_26030103_FudianFPU(Config)
      case ysyx_26030103_FPUImpl.External =>
        throw new NotImplementedError(
          "FPU_IMPL=external 只是预留接口，当前没有外部 FPU 实现")
    }
  }
}
