package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//MUL ISA适配层。
class ysyx_26030103_MULUnit(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_MULAdapter(config.XLEN) {
  private val core = ysyx_26030103_MULCoreFactory.Create(config)
  private val coreWidth = config.MULEffectiveWidth
  private val coreProductWidth = 2 * coreWidth
  // 流水压缩核能够保留的每项在途请求都必须有一项符号/高半部元数据。
  // 仅使用一个Pending位会使原本II=1的Wallace/Dadda核串行化。
  private val metadataDepth = config.MULMaxInflight
  private val metadataPointerWidth = log2Ceil(metadataDepth).max(1)
  private val metadataCountWidth = log2Ceil(metadataDepth + 1)
  private val negativeQueue =
    RegInit(VecInit(Seq.fill(metadataDepth)(false.B)))
  private val takeHighQueue =
    RegInit(VecInit(Seq.fill(metadataDepth)(false.B)))
  private val metadataHead = RegInit(0.U(metadataPointerWidth.W))
  private val metadataTail = RegInit(0.U(metadataPointerWidth.W))
  private val metadataCount = RegInit(0.U(metadataCountWidth.W))
  private val metadataValid = metadataCount =/= 0.U
  private val headNegative =
    if (metadataDepth == 1) negativeQueue(0) else negativeQueue(metadataHead)
  private val headTakeHigh =
    if (metadataDepth == 1) takeHighQueue(0) else takeHighQueue(metadataHead)
  val requestNegative = ysyx_26030103_MULAdapterUtils.ResultNegative(
    IO.Request.bits.LHS,
    IO.Request.bits.RHS,
    IO.Request.bits.SignedMode
  )
  val lhsMagnitude = ysyx_26030103_MULAdapterUtils.Magnitude(
    IO.Request.bits.LHS,
    ysyx_26030103_MULSignedMode.LHSSigned(IO.Request.bits.SignedMode)
  )
  val rhsMagnitude = ysyx_26030103_MULAdapterUtils.Magnitude(
    IO.Request.bits.RHS,
    ysyx_26030103_MULSignedMode.RHSSigned(IO.Request.bits.SignedMode)
  )
  core.IO.Flush := IO.Flush
  private val coreLHSMagnitude = if (coreWidth == 32) {
    lhsMagnitude
  } else if (coreWidth > 32) {
    Cat(0.U((coreWidth - 32).W), lhsMagnitude)
  } else {
    lhsMagnitude(coreWidth - 1, 0)
  }
  private val coreRHSMagnitude = if (coreWidth == 32) {
    rhsMagnitude
  } else if (coreWidth > 32) {
    Cat(0.U((coreWidth - 32).W), rhsMagnitude)
  } else {
    rhsMagnitude(coreWidth - 1, 0)
  }
  core.IO.Request.bits.LHSMagnitude := coreLHSMagnitude
  core.IO.Request.bits.RHSMagnitude := coreRHSMagnitude
  core.IO.Response.ready := !IO.Flush && metadataValid && IO.Response.ready
  val coreResponseFire = core.IO.Response.valid && core.IO.Response.ready
  val metadataHasSpace = metadataCount < metadataDepth.U || coreResponseFire
  IO.Request.ready := !IO.Flush && metadataHasSpace && core.IO.Request.ready
  core.IO.Request.valid := !IO.Flush && IO.Request.valid && metadataHasSpace
  val coreRequestFire = core.IO.Request.valid && core.IO.Request.ready
  // 适配层只需要 2*XLEN 位乘积。MULWidth 可能大于 XLEN（例如为
  // 时序/复用预留的 128 位乘法器），高位不参与架构结果。
  private val archProductWidth = 2 * config.XLEN
  val coreProductWidthAdjusted = if (coreProductWidth == archProductWidth) {
    core.IO.Response.bits.Product
  } else if (coreProductWidth > archProductWidth) {
    core.IO.Response.bits.Product(archProductWidth - 1, 0)
  } else {
    Cat(0.U((archProductWidth - coreProductWidth).W), core.IO.Response.bits.Product)
  }
  val coreProduct = ysyx_26030103_MULAdapterUtils.RestoreSign(
    coreProductWidthAdjusted,
    headNegative
  )
  IO.Response.valid := !IO.Flush && metadataValid && core.IO.Response.valid
  IO.Response.bits.Result := ysyx_26030103_MULAdapterUtils.SelectResult(
    coreProduct,
    headTakeHigh,
    config.XLEN
  )
  val nextMetadataHead = Mux(
    metadataHead === (metadataDepth - 1).U,
    0.U,
    metadataHead + 1.U
  )
  val nextMetadataTail = Mux(
    metadataTail === (metadataDepth - 1).U,
    0.U,
    metadataTail + 1.U
  )
  when(core.IO.Response.valid && !IO.Flush) {
    assert(metadataValid, "MUL core produced a response without metadata")
  }
  assert(
    IO.Request.fire === coreRequestFire,
    "MUL request and sign metadata became misaligned"
  )
  assert(
    IO.Response.fire === coreResponseFire,
    "MUL response and sign metadata became misaligned"
  )
  assert(metadataCount <= metadataDepth.U, "MUL metadata FIFO overflow")
  when(IO.Flush) {
    metadataHead := 0.U
    metadataTail := 0.U
    metadataCount := 0.U
  }.elsewhen(coreRequestFire || coreResponseFire) {
    when(coreRequestFire) {
      if (metadataDepth == 1) {
        negativeQueue(0) := requestNegative
        takeHighQueue(0) := IO.Request.bits.TakeHigh
      } else {
        negativeQueue(metadataTail) := requestNegative
        takeHighQueue(metadataTail) := IO.Request.bits.TakeHigh
      }
      metadataTail := nextMetadataTail
    }
    when(coreResponseFire) {
      metadataHead := nextMetadataHead
    }
    when(coreRequestFire && !coreResponseFire) {
      metadataCount := metadataCount + 1.U
    }.elsewhen(!coreRequestFire && coreResponseFire) {
      metadataCount := metadataCount - 1.U
    }
  }
}
