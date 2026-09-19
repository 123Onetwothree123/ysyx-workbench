package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
//无符号部分积压缩树公共结构。
private object ysyx_26030103_MULCompressionUtils {
  def PlainPartialProducts(LHS: UInt, RHS: UInt): IndexedSeq[UInt] = {
    (0 until 32).map { Bit =>
      val Shifted = (Cat(0.U(32.W), LHS) << Bit)(63, 0)
      Mux(RHS(Bit), Shifted, 0.U(64.W))
    }
  }
  def CompressThree(X: UInt, Y: UInt, Z: UInt): (UInt, UInt) = {
    val Sum = X ^ Y ^ Z
    val Carry = (((X & Y) | (X & Z) | (Y & Z)) << 1)(63, 0)
    (Sum, Carry)
  }
  def WallaceReduce(Rows: IndexedSeq[UInt]): IndexedSeq[UInt] = {
    val NextRows = scala.collection.mutable.ArrayBuffer[UInt]()
    var Index = 0
    while (Index + 2 < Rows.length) {
      val (sum, carry) = CompressThree(Rows(Index), Rows(Index + 1), Rows(Index + 2))
      NextRows += sum
      NextRows += carry
      Index += 3
    }
    while (Index < Rows.length) {
      NextRows += Rows(Index)
      Index += 1
    }
    NextRows.toIndexedSeq
  }
  def DaddaReduceStrict(Rows: IndexedSeq[UInt], Target: Int): IndexedSeq[UInt] = {
    val Columns = Array.fill(65)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (Row <- Rows; Bit <- 0 until 64) {
      Columns(Bit) += Row(Bit)
    }
    val ReducedColumns = Array.fill(64)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (Bit <- 0 until 64) {
      val Work = scala.collection.mutable.ArrayBuffer[Bool]()
      Work ++= Columns(Bit)
      while (Work.length + ReducedColumns(Bit).length > Target) {
        val Excess = Work.length + ReducedColumns(Bit).length - Target
        if (Excess >= 2) {
          val A = Work(0)
          val B = Work(1)
          val C = Work(2)
          Work.remove(0, 3)
          ReducedColumns(Bit) += A ^ B ^ C
          if (Bit < 63) {
            Columns(Bit + 1) += ((A & B) | (A & C) | (B & C))
          }
        } else {
          val A = Work(0)
          val B = Work(1)
          Work.remove(0, 2)
          ReducedColumns(Bit) += A ^ B
          if (Bit < 63) {
            Columns(Bit + 1) += A & B
          }
        }
      }
      ReducedColumns(Bit) ++= Work
    }
    val RowCount = ReducedColumns.map(_.length).max
    (0 until RowCount).map { RowIndex =>
      val RowBits = (0 until 64).map { Bit =>
        if (RowIndex < ReducedColumns(Bit).length) ReducedColumns(Bit)(RowIndex) else false.B
      }
      RowBits.reverse.map(_.asUInt).reduce((High, Low) => Cat(High, Low))
    }
  }
  def WallaceSchedule(RowCount: Int): IndexedSeq[Int] = {
    val Schedule = scala.collection.mutable.ArrayBuffer[Int]()
    var CurrentRows = RowCount
    while (CurrentRows > 2) {
      CurrentRows = (CurrentRows / 3) * 2 + CurrentRows % 3
      Schedule += CurrentRows
    }
    Schedule.toIndexedSeq
  }
  def DaddaSchedule(RowCount: Int): IndexedSeq[Int] = {
    val Targets = scala.collection.mutable.ArrayBuffer(2)
    while (Targets.last < RowCount) {
      Targets += Targets.last * 3 / 2
    }
    Targets.dropRight(1).reverse.toIndexedSeq
  }
  def FinalSum(Rows: IndexedSeq[UInt]): UInt = {
    if (Rows.length == 1) Rows.head else (Rows(0) +& Rows(1))(63, 0)
  }
}
//压缩树乘法器的握手、背压和内部流水寄存器。
abstract class ysyx_26030103_MULCompressionCore(
    config: ysyx_26030103_NPCConfig,
    UseDadda: Boolean
) extends ysyx_26030103_MULCore {
  private val UseBooth = config.MULEncoding == ysyx_26030103_MULEncoding.Booth //是否使用Booth编码
  private val BoothRadix = if (UseBooth) config.MULRadix else 2 //Booth基数
  private val BoothBits = if (UseBooth) ysyx_26030103_MULBoothConfig.BoothBits(BoothRadix) else 1 //每组处理位数
  private val BoothGroups = if (UseBooth) (33 + BoothBits - 1) / BoothBits else 0 //Booth部分积行数
  private val BoothPaddedBits = BoothGroups * BoothBits //补齐后的乘数宽度
  private val BoothProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(BoothRadix) //编码器部分积宽度
  private val InitialRows = if (!UseBooth) {
    ysyx_26030103_MULCompressionUtils.PlainPartialProducts(IO.Req.bits.LHSMagnitude, IO.Req.bits.RHSMagnitude)
  } else {
    val Multiplier = Cat(0.U((BoothPaddedBits - 32).W), IO.Req.bits.RHSMagnitude, 0.U(1.W))
    val Multiplicand = Cat(0.U(BoothBits.W), IO.Req.bits.LHSMagnitude)
    (0 until BoothGroups).map { Group =>
      val Encoder = Module(new ysyx_26030103_MULBoothEncoder(BoothRadix))
      val WindowLow = Group * BoothBits
      Encoder.IO.Multiplicand := Multiplicand
      Encoder.IO.Window := Multiplier(WindowLow + BoothBits, WindowLow)
      val PartialProduct = Encoder.IO.PartialProduct
      val PartialProductLow = if (BoothProductWidth >= 64) PartialProduct(63, 0) else Cat(Fill(64 - BoothProductWidth, PartialProduct(BoothProductWidth - 1)), PartialProduct)
      (PartialProductLow << (Group * BoothBits))(63, 0)
    }
  }
  private val BaseSchedule = if (UseDadda) ysyx_26030103_MULCompressionUtils.DaddaSchedule(InitialRows.length) else ysyx_26030103_MULCompressionUtils.WallaceSchedule(InitialRows.length)
  private val Schedule = if (BaseSchedule.length < config.MULPipeline) BaseSchedule ++ Seq.fill(config.MULPipeline - BaseSchedule.length)(2) else BaseSchedule
  private val BoundarySteps = (1 to config.MULPipeline).map { Boundary =>
    math.ceil(Schedule.length.toDouble * Boundary / (config.MULPipeline + 1)).toInt - 1
  }.toSet
  private val Valid = RegInit(VecInit(Seq.fill(config.MULPipeline + 1)(false.B)))
  private val Product = Reg(UInt(64.W))
  private val Advance = !Valid.last || IO.Resp.ready
  IO.Req.ready := !IO.Flush && Advance
  IO.Resp.valid := Valid.last && !IO.Flush
  IO.Resp.bits.Product := Product
  var Rows = InitialRows
  for ((target, step) <- Schedule.zipWithIndex) {
    Rows = if (Rows.length <= 2) Rows else if (UseDadda) ysyx_26030103_MULCompressionUtils.DaddaReduceStrict(Rows, target) else ysyx_26030103_MULCompressionUtils.WallaceReduce(Rows)
    if (BoundarySteps.contains(step)) {
      val Boundary = Reg(Vec(Rows.length, UInt(64.W)))
      when (Advance) {
        for (Index <- Rows.indices) {
          Boundary(Index) := Rows(Index)
        }
      }
      Rows = Boundary.toIndexedSeq
    }
  }
  private val NextProduct = ysyx_26030103_MULCompressionUtils.FinalSum(Rows)
  when (IO.Flush) {
    for (Index <- Valid.indices) {
      Valid(Index) := false.B
    }
  }.elsewhen (Advance) {
    for (Index <- Valid.indices) {
      Valid(Index) := (if (Index == 0) IO.Req.valid else Valid(Index - 1))
    }
    Product := NextProduct
  }
}
