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
  // 将带有静态有效范围的部分积行放入列点阵。
  // 范围外的位不是“值为零的点”，而是不存在的点；这是标准
  // Dadda 与把所有行补齐到完整字长再压缩的实现之间的关键区别。
  def InitialDaddaColumns(
      Rows: IndexedSeq[UInt],
      ValidRanges: IndexedSeq[(Int, Int)]
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(Rows.length == ValidRanges.length)
    val Columns = Array.fill(64)(scala.collection.mutable.ArrayBuffer[Bool]())
    for ((row, bounds) <- Rows.zip(ValidRanges)) {
      val (low, high) = bounds
      require(low >= 0 && high < 64 && low <= high)
      for (Bit <- low to high) {
        Columns(Bit) += row(Bit)
      }
    }
    Columns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 对稀疏列点阵执行一个 Dadda reduction stage。
  // 每列从低位到高位处理：超出目标高度至少两个点时使用 FA，
  // 恰好超出一个点时使用 HA；产生的 carry 进入下一列。
  def DaddaReduceColumns(
      InputColumns: IndexedSeq[IndexedSeq[Bool]],
      Target: Int
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(InputColumns.length == 64)
    require(Target >= 2)
    // carry 是本 stage 的输出点，只参与目标列高计算，不会被本 stage
    // 的其他压缩器再次消费，因而每个 Dadda stage 保持单层压缩深度。
    val IncomingCarries = Array.fill(64)(scala.collection.mutable.ArrayBuffer[Bool]())
    val ReducedColumns = Array.fill(64)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (Bit <- 0 until 64) {
      val Work = scala.collection.mutable.ArrayBuffer[Bool]()
      Work ++= InputColumns(Bit)
      while (Work.length + ReducedColumns(Bit).length + IncomingCarries(Bit).length > Target) {
        val Excess = Work.length + ReducedColumns(Bit).length + IncomingCarries(Bit).length - Target
        if (Excess >= 2) {
          require(Work.length >= 3)
          val A = Work(0)
          val B = Work(1)
          val C = Work(2)
          Work.remove(0, 3)
          ReducedColumns(Bit) += A ^ B ^ C
          if (Bit < 63) {
            IncomingCarries(Bit + 1) += ((A & B) | (A & C) | (B & C))
          }
        } else {
          require(Work.length >= 2)
          val A = Work(0)
          val B = Work(1)
          Work.remove(0, 2)
          ReducedColumns(Bit) += A ^ B
          if (Bit < 63) {
            IncomingCarries(Bit + 1) += A & B
          }
        }
      }
      ReducedColumns(Bit) ++= Work
      ReducedColumns(Bit) ++= IncomingCarries(Bit)
    }
    require(
      ReducedColumns.forall(_.length <= Target),
      s"Dadda reduction exceeded target height $Target"
    )
    ReducedColumns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 兼容旧的完整行接口；新的 Dadda core 不再使用它。
  // 这里仍按完整 64 位行处理，便于保留已有的单元级回归测试。
  def DaddaReduceStrict(Rows: IndexedSeq[UInt], Target: Int): IndexedSeq[UInt] = {
    val FullRanges = Rows.indices.map(_ => (0, 63)).toIndexedSeq
    ColumnsToRows(DaddaReduceColumns(InitialDaddaColumns(Rows, FullRanges), Target))
  }

  // 仅在最后两行已经形成后才重新拼接成 UInt；中间阶段始终保持稀疏列。
  def ColumnsToRows(Columns: IndexedSeq[IndexedSeq[Bool]]): IndexedSeq[UInt] = {
    require(Columns.length == 64)
    val RowCount = Columns.map(_.length).max
    (0 until RowCount).map { RowIndex =>
      val RowBits = (0 until 64).map { Bit =>
        if (RowIndex < Columns(Bit).length) Columns(Bit)(RowIndex) else false.B
      }
      RowBits.reverse.map(_.asUInt).reduce((High, Low) => Cat(High, Low))
    }
  }

  // 在 Dadda stage 之间插入按列保存的寄存器，不把稀疏列重新补成完整行。
  def RegisterColumns(
      Columns: IndexedSeq[IndexedSeq[Bool]],
      Advance: Bool
  ): IndexedSeq[IndexedSeq[Bool]] = {
    Columns.map { Column =>
      if (Column.isEmpty) {
        Column
      } else {
        val Boundary = Reg(Vec(Column.length, Bool()))
        when (Advance) {
          for (Index <- Column.indices) {
            Boundary(Index) := Column(Index)
          }
        }
        Boundary.toIndexedSeq
      }
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
  def DaddaSchedule(MaxHeight: Int): IndexedSeq[Int] = {
    val Targets = scala.collection.mutable.ArrayBuffer(2)
    while (Targets.last < MaxHeight) {
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
  // Dadda 使用真实的部分积列高，而不是完整 UInt 行的数量。
  // 普通部分积第 r 行只占据 [r, r+31]；Booth 行从其组移位位置
  // 开始占据到 bit63（高位包含符号扩展的有效点）。
  private val InitialDaddaRanges = if (!UseBooth) {
    (0 until InitialRows.length).map { Row =>
      (Row, (Row + 31).min(63))
    }.toIndexedSeq
  } else {
    (0 until InitialRows.length).map { Group =>
      (Group * BoothBits, 63)
    }.toIndexedSeq
  }
  private val InitialDaddaColumns =
    ysyx_26030103_MULCompressionUtils.InitialDaddaColumns(InitialRows, InitialDaddaRanges)
  private val InitialDaddaHeight = InitialDaddaColumns.map(_.length).max
  private val BaseSchedule = if (UseDadda) {
    ysyx_26030103_MULCompressionUtils.DaddaSchedule(InitialDaddaHeight)
  } else {
    ysyx_26030103_MULCompressionUtils.WallaceSchedule(InitialRows.length)
  }
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
  private val NextProduct = if (UseDadda) {
    // 中间阶段始终保留稀疏列；只在最终两行形成后重组成 UInt。
    var Columns = InitialDaddaColumns
    for ((target, step) <- Schedule.zipWithIndex) {
      if (Columns.map(_.length).max > 2) {
        Columns = ysyx_26030103_MULCompressionUtils.DaddaReduceColumns(Columns, target)
      }
      if (BoundarySteps.contains(step)) {
        Columns = ysyx_26030103_MULCompressionUtils.RegisterColumns(Columns, Advance)
      }
    }
    ysyx_26030103_MULCompressionUtils.FinalSum(
      ysyx_26030103_MULCompressionUtils.ColumnsToRows(Columns)
    )
  } else {
    var Rows = InitialRows
    for ((_, step) <- Schedule.zipWithIndex) {
      Rows = if (Rows.length <= 2) {
        Rows
      } else {
        ysyx_26030103_MULCompressionUtils.WallaceReduce(Rows)
      }
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
    ysyx_26030103_MULCompressionUtils.FinalSum(Rows)
  }
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
