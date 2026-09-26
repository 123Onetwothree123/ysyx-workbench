package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
//MUL ISA适配层。
class ysyx_26030103_MULUnit(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_MULAdapter {
  private val Core = ysyx_26030103_MULCoreFactory.Create(config)
  private val CoreWidth = config.MULWidth
  private val CoreProductWidth = 2 * CoreWidth
  // Sign/high-half metadata must have one entry for every request which the
  // pipelined compression core can keep in flight.  A single Pending bit would
  // serialize the otherwise II=1 Wallace/Dadda cores.
  private val MetadataDepth = config.MULMaxInflight
  private val MetadataPtrWidth = log2Ceil(MetadataDepth).max(1)
  private val MetadataCountWidth = log2Ceil(MetadataDepth + 1)
  private val NegativeQueue = Reg(Vec(MetadataDepth, Bool()))
  private val TakeHighQueue = Reg(Vec(MetadataDepth, Bool()))
  private val MetadataHead = RegInit(0.U(MetadataPtrWidth.W))
  private val MetadataTail = RegInit(0.U(MetadataPtrWidth.W))
  private val MetadataCount = RegInit(0.U(MetadataCountWidth.W))
  private val MetadataValid = MetadataCount =/= 0.U
  private val HeadNegative =
    if (MetadataDepth == 1) NegativeQueue(0) else NegativeQueue(MetadataHead)
  private val HeadTakeHigh =
    if (MetadataDepth == 1) TakeHighQueue(0) else TakeHighQueue(MetadataHead)
  val RequestNegative = ysyx_26030103_MULAdapterUtils.ResultNegative(
    IO.Req.bits.LHS,
    IO.Req.bits.RHS,
    IO.Req.bits.SignedMode
  )
  val LHSMagnitude = ysyx_26030103_MULAdapterUtils.Magnitude(
    IO.Req.bits.LHS,
    ysyx_26030103_MULSignedMode.LHSSigned(IO.Req.bits.SignedMode)
  )
  val RHSMagnitude = ysyx_26030103_MULAdapterUtils.Magnitude(
    IO.Req.bits.RHS,
    ysyx_26030103_MULSignedMode.RHSSigned(IO.Req.bits.SignedMode)
  )
  Core.IO.Flush := IO.Flush
  private val CoreLHSMagnitude = if (CoreWidth == 32) {
    LHSMagnitude
  } else if (CoreWidth > 32) {
    Cat(0.U((CoreWidth - 32).W), LHSMagnitude)
  } else {
    LHSMagnitude(CoreWidth - 1, 0)
  }
  private val CoreRHSMagnitude = if (CoreWidth == 32) {
    RHSMagnitude
  } else if (CoreWidth > 32) {
    Cat(0.U((CoreWidth - 32).W), RHSMagnitude)
  } else {
    RHSMagnitude(CoreWidth - 1, 0)
  }
  Core.IO.Req.bits.LHSMagnitude := CoreLHSMagnitude
  Core.IO.Req.bits.RHSMagnitude := CoreRHSMagnitude
  Core.IO.Resp.ready := !IO.Flush && MetadataValid && IO.Resp.ready
  val CoreRespFire = Core.IO.Resp.valid && Core.IO.Resp.ready
  val MetadataHasSpace = MetadataCount < MetadataDepth.U || CoreRespFire
  IO.Req.ready := !IO.Flush && MetadataHasSpace && Core.IO.Req.ready
  Core.IO.Req.valid := !IO.Flush && IO.Req.valid && MetadataHasSpace
  val CoreReqFire = Core.IO.Req.valid && Core.IO.Req.ready
  val CoreProductWidthAdjusted = if (CoreProductWidth == 64) {
    Core.IO.Resp.bits.Product(63, 0)
  } else if (CoreProductWidth > 64) {
    Core.IO.Resp.bits.Product(63, 0)
  } else {
    Cat(0.U((64 - CoreProductWidth).W), Core.IO.Resp.bits.Product)
  }
  val CoreProduct = ysyx_26030103_MULAdapterUtils.RestoreSign(
    CoreProductWidthAdjusted,
    HeadNegative
  )
  IO.Resp.valid := !IO.Flush && MetadataValid && Core.IO.Resp.valid
  IO.Resp.bits.Result := ysyx_26030103_MULAdapterUtils.SelectResult(
    CoreProduct,
    HeadTakeHigh
  )
  val NextMetadataHead = Mux(
    MetadataHead === (MetadataDepth - 1).U,
    0.U,
    MetadataHead + 1.U
  )
  val NextMetadataTail = Mux(
    MetadataTail === (MetadataDepth - 1).U,
    0.U,
    MetadataTail + 1.U
  )
  when(Core.IO.Resp.valid && !IO.Flush) {
    assert(MetadataValid, "MUL core produced a response without metadata")
  }
  assert(
    IO.Req.fire === CoreReqFire,
    "MUL request and sign metadata became misaligned"
  )
  assert(
    IO.Resp.fire === CoreRespFire,
    "MUL response and sign metadata became misaligned"
  )
  assert(MetadataCount <= MetadataDepth.U, "MUL metadata FIFO overflow")
  when(IO.Flush) {
    MetadataHead := 0.U
    MetadataTail := 0.U
    MetadataCount := 0.U
  }.elsewhen(CoreReqFire || CoreRespFire) {
    when(CoreReqFire) {
      if (MetadataDepth == 1) {
        NegativeQueue(0) := RequestNegative
        TakeHighQueue(0) := IO.Req.bits.TakeHigh
      } else {
        NegativeQueue(MetadataTail) := RequestNegative
        TakeHighQueue(MetadataTail) := IO.Req.bits.TakeHigh
      }
      MetadataTail := NextMetadataTail
    }
    when(CoreRespFire) {
      MetadataHead := NextMetadataHead
    }
    when(CoreReqFire && !CoreRespFire) {
      MetadataCount := MetadataCount + 1.U
    }.elsewhen(!CoreReqFire && CoreRespFire) {
      MetadataCount := MetadataCount - 1.U
    }
  }
}
