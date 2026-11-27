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
  private val MetadataQueue =
    Module(new ysyx_26030103_RingBuffer(metadataDepth, 2))
  private val metadataValid = !MetadataQueue.io.Empty
  private val headNegative = MetadataQueue.io.HeadData(1)
  private val headTakeHigh = MetadataQueue.io.HeadData(0)
  val requestNegative = ysyx_26030103_MULAdapterUtils.ResultNegative(
    IO.Request.bits.LHS,
    IO.Request.bits.RHS,
    IO.Request.bits.SignedMode
  )
  val lhsMagnitude = ysyx_26030103_MDUArithmetic.Magnitude(
    IO.Request.bits.LHS,
    ysyx_26030103_MULSignedMode.LHSSigned(IO.Request.bits.SignedMode)
  )
  val rhsMagnitude = ysyx_26030103_MDUArithmetic.Magnitude(
    IO.Request.bits.RHS,
    ysyx_26030103_MULSignedMode.RHSSigned(IO.Request.bits.SignedMode)
  )
  core.IO.Flush := IO.Flush
  private def alignOperand(value: UInt): UInt =
    if (coreWidth == 32) value
    else Cat(0.U((coreWidth - 32).W), value)
  private val coreLHSMagnitude = alignOperand(lhsMagnitude)
  private val coreRHSMagnitude = alignOperand(rhsMagnitude)
  core.IO.Request.bits.LHSMagnitude := coreLHSMagnitude
  core.IO.Request.bits.RHSMagnitude := coreRHSMagnitude
  core.IO.Response.ready := !IO.Flush && metadataValid && IO.Response.ready
  val coreResponseFire = core.IO.Response.valid && core.IO.Response.ready
  val metadataHasSpace = MetadataQueue.io.HasSpace
  IO.Request.ready := !IO.Flush && metadataHasSpace && core.IO.Request.ready
  core.IO.Request.valid := !IO.Flush && IO.Request.valid && metadataHasSpace
  val coreRequestFire = core.IO.Request.valid && core.IO.Request.ready
  // 适配层只需要 2*XLEN 位乘积。MULWidth 可能大于 XLEN（例如为
  // 时序/复用预留的 128 位乘法器），高位不参与架构结果。
  private val archProductWidth = 2 * config.XLEN
  val coreProductWidthAdjusted = if (coreProductWidth == archProductWidth) {
    core.IO.Response.bits.Product
  } else {
    core.IO.Response.bits.Product(archProductWidth - 1, 0)
  }
  val coreProduct = ysyx_26030103_MDUArithmetic.RestoreSign(
    coreProductWidthAdjusted,
    headNegative
  )
  IO.Response.valid := !IO.Flush && metadataValid && core.IO.Response.valid
  IO.Response.bits.Result := ysyx_26030103_MULAdapterUtils.SelectResult(
    coreProduct,
    headTakeHigh,
    config.XLEN
  )
  MetadataQueue.io.Enqueue := coreRequestFire
  MetadataQueue.io.EnqueueData :=
    Cat(requestNegative, IO.Request.bits.TakeHigh)
  MetadataQueue.io.Dequeue := coreResponseFire
  MetadataQueue.io.Flush := IO.Flush
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
}
