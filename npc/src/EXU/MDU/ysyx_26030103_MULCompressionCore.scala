package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
//无符号部分积压缩树公共结构。
private object ysyx_26030103_MULCompressionUtils {
  def PlainPartialProducts(LHS: UInt, RHS: UInt, OperandWidth: Int = 32, ProductWidth: Int = 64): IndexedSeq[UInt] = {
    (0 until OperandWidth).map { Bit =>
      val Shifted = (Cat(0.U(OperandWidth.W), LHS) << Bit)(ProductWidth - 1, 0)
      Mux(RHS(Bit), Shifted, 0.U(ProductWidth.W))
    }
  }
  final case class WeightedDot(Value: Bool, Offset: Int)
  def CompressDots(
      Dots: IndexedSeq[Bool],
      Config: ysyx_26030103_MULGPCConfig
  ): IndexedSeq[WeightedDot] = {
    require(Dots.length >= 2)
    require(Dots.length <= Config.InputCountWithCin)
    val UseCin = Config.HasCin && Dots.length > Config.Inputs
    val DataInputs = math.min(Dots.length, Config.Inputs)
    val Compressor = Module(new ysyx_26030103_MULGPC(Config))
    for (Index <- 0 until Config.Inputs) {
      Compressor.IO.In(Index) := (if (Index < DataInputs) Dots(Index) else false.B)
    }
    Compressor.IO.Cin := (if (UseCin) Dots(DataInputs) else false.B)
    Config.ActiveOutputBits(DataInputs, UseCin).map { Bit =>
      if (Bit < Config.Outputs) {
        WeightedDot(Compressor.IO.Out(Bit), Config.OutputOffsets(Bit))
      } else {
        WeightedDot(Compressor.IO.Cout, Config.CoutOffset)
      }
    }
  }
  // 广义Wallace stage：每组按配置K:R GPC压缩，各加权输出写入
  // 下一层对应列，不在本级继续消费。
  def WallaceReduceColumns(
      InputColumns: IndexedSeq[IndexedSeq[Bool]],
      ProductWidth: Int,
      Config: ysyx_26030103_MULGPCConfig
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(InputColumns.length == ProductWidth)
    val NextColumns = Array.fill(ProductWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (Bit <- 0 until ProductWidth) {
      val Work = scala.collection.mutable.ArrayBuffer[Bool]()
      Work ++= InputColumns(Bit)
      while (Work.length >= 3) {
        val GroupSize = math.min(Config.InputCountWithCin, Work.length)
        val Group = Work.take(GroupSize).toIndexedSeq
        Work.remove(0, GroupSize)
        val Outputs = CompressDots(Group, Config)
        for (Output <- Outputs if Bit + Output.Offset < ProductWidth) {
          NextColumns(Bit + Output.Offset) += Output.Value
        }
      }
      NextColumns(Bit) ++= Work
    }
    NextColumns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 只用静态列高计算 Wallace 的层数，供流水边界均匀分布使用。
  def WallaceColumnSchedule(
      InitialHeights: IndexedSeq[Int],
      ProductWidth: Int,
      Config: ysyx_26030103_MULGPCConfig
  ): IndexedSeq[Int] = {
    require(InitialHeights.length == ProductWidth)
    val Schedule = scala.collection.mutable.ArrayBuffer[Int]()
    var Heights = InitialHeights
    while (Heights.max > 2) {
      val NextHeights = Array.fill(ProductWidth)(0)
      for (Bit <- 0 until ProductWidth) {
        var Remaining = Heights(Bit)
        while (Remaining >= 3) {
          val GroupSize = math.min(Config.InputCountWithCin, Remaining)
          Remaining -= GroupSize
          val UseCin = Config.HasCin && GroupSize > Config.Inputs
          val DataInputs = math.min(GroupSize, Config.Inputs)
          for (OutputBit <- Config.ActiveOutputBits(DataInputs, UseCin)) {
            val Offset = Config.AllOutputOffsets(OutputBit)
            if (Bit + Offset < ProductWidth) {
              NextHeights(Bit + Offset) += 1
            }
          }
        }
        NextHeights(Bit) += Remaining
      }
      require(NextHeights.sum < Heights.sum, "GPC配置无法继续压缩Wallace点阵")
      Heights = NextHeights.toIndexedSeq
      Schedule += Heights.max
    }
    Schedule.toIndexedSeq
  }
  // 将带有静态有效范围的部分积行放入列点阵。
  // 范围外的位不是“值为零的点”，而是不存在的点；这是标准
  // Dadda 与把所有行补齐到完整字长再压缩的实现之间的关键区别。
  def InitialDaddaColumns(
      Rows: IndexedSeq[UInt],
      ValidRanges: IndexedSeq[(Int, Int)],
      ProductWidth: Int = 64
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(Rows.length == ValidRanges.length)
    val Columns = Array.fill(ProductWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    for ((row, bounds) <- Rows.zip(ValidRanges)) {
      val (low, high) = bounds
      require(low >= 0 && high < ProductWidth && low <= high)
      for (Bit <- low to high) {
        Columns(Bit) += row(Bit)
      }
    }
    Columns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 对稀疏列点阵执行一个由配置K:R GPC驱动的广义Dadda stage。
  def DaddaReduceColumns(
      InputColumns: IndexedSeq[IndexedSeq[Bool]],
      Target: Int,
      ProductWidth: Int = 64,
      Config: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(InputColumns.length == ProductWidth)
    require(Target >= 2)
    // 当前 stage 在低位列产生的 carry 会作为下一列的输入点参与同一轮
    // 的列高计算。这正是标准 Dadda 列扫描的做法；若把它只计数而不
    // 放回 Work，会得到一个功能等价但压缩器布局不标准的网络。
    val IncomingCarries = Array.fill(ProductWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    val ReducedColumns = Array.fill(ProductWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (Bit <- 0 until ProductWidth) {
      val Work = scala.collection.mutable.ArrayBuffer[Bool]()
      Work ++= InputColumns(Bit)
      Work ++= IncomingCarries(Bit)
      while (Work.length + ReducedColumns(Bit).length > Target) {
        val Excess = Work.length + ReducedColumns(Bit).length - Target
        val CandidateGroups = (2 to math.min(Config.InputCountWithCin, Work.length)).map { GroupSize =>
          val UseCin = Config.HasCin && GroupSize > Config.Inputs
          val DataInputs = math.min(GroupSize, Config.Inputs)
          val SameColumnOutputs = Config
            .ActiveOutputBits(DataInputs, UseCin)
            .count(OutputBit => Config.AllOutputOffsets(OutputBit) == 0)
          (GroupSize, GroupSize - SameColumnOutputs)
        }.filter(_._2 > 0)
        require(CandidateGroups.nonEmpty, "GPC配置无法降低当前Dadda列高")
        val WithinExcess = CandidateGroups.filter(_._2 <= Excess)
        val GroupSize = if (WithinExcess.nonEmpty) {
          WithinExcess.maxBy { case (inputs, reduction) => (reduction, inputs) }._1
        } else {
          CandidateGroups.minBy { case (inputs, reduction) => (reduction, -inputs) }._1
        }
        val Group = Work.take(GroupSize).toIndexedSeq
        Work.remove(0, GroupSize)
        val Outputs = CompressDots(Group, Config)
        for (Output <- Outputs) {
          if (Output.Offset == 0) {
            ReducedColumns(Bit) += Output.Value
          } else if (Bit + Output.Offset < ProductWidth) {
            IncomingCarries(Bit + Output.Offset) += Output.Value
          }
        }
      }
      ReducedColumns(Bit) ++= Work
    }
    require(
      ReducedColumns.forall(_.length <= Target),
      s"Dadda reduction exceeded target height $Target"
    )
    ReducedColumns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 兼容旧的完整行接口；新的 Dadda core 不再使用它。
  // 这里仍按完整 P 位行处理，便于保留已有的单元级回归测试。
  def DaddaReduceStrict(
      Rows: IndexedSeq[UInt],
      Target: Int,
      ProductWidth: Int = 64,
      Config: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
  ): IndexedSeq[UInt] = {
    val FullRanges = Rows.indices.map(_ => (0, ProductWidth - 1)).toIndexedSeq
    ColumnsToRows(
      DaddaReduceColumns(
        InitialDaddaColumns(Rows, FullRanges, ProductWidth),
        Target,
        ProductWidth,
        Config
      ),
      ProductWidth
    )
  }

  // 仅在最后两行已经形成后才重新拼接成 UInt；中间阶段始终保持稀疏列。
  def ColumnsToRows(Columns: IndexedSeq[IndexedSeq[Bool]], ProductWidth: Int = 64): IndexedSeq[UInt] = {
    require(Columns.length == ProductWidth)
    val RowCount = Columns.map(_.length).max
    (0 until RowCount).map { RowIndex =>
      val RowBits = (0 until ProductWidth).map { Bit =>
        if (RowIndex < Columns(Bit).length) Columns(Bit)(RowIndex) else false.B
      }
      RowBits.reverse.map(_.asUInt).reduce((High, Low) => Cat(High, Low))
    }
  }

  // 在 Dadda stage 之间插入按列保存的寄存器，不把稀疏列重新补成完整行。
  def RegisterColumns(
      Columns: IndexedSeq[IndexedSeq[Bool]],
      Advance: Bool,
      Clear: Bool = false.B
  ): IndexedSeq[IndexedSeq[Bool]] = {
    Columns.map { Column =>
      if (Column.isEmpty) {
        Column
      } else {
        val Boundary = RegInit(VecInit(Seq.fill(Column.length)(false.B)))
        when (Clear) {
          for (Index <- Column.indices) {
            Boundary(Index) := false.B
          }
        }.elsewhen (Advance) {
          for (Index <- Column.indices) {
            Boundary(Index) := Column(Index)
          }
        }
        Boundary.toIndexedSeq
      }
    }
  }
  // 按配置GPC的有效输入/输出dot数量生成广义Dadda目标高度。
  def DaddaSchedule(
      MaxHeight: Int,
      Config: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
  ): IndexedSeq[Int] = {
    val InputDots = Config.InputCountWithCin
    val OutputDots = Config.FullActiveOutputBits.length
    val Targets = scala.collection.mutable.ArrayBuffer(2)
    while (Targets.last < MaxHeight) {
      val Scaled = Targets.last * InputDots / OutputDots
      Targets += math.max(Targets.last + 1, Scaled)
    }
    Targets.dropRight(1).reverse.toIndexedSeq
  }
  def FinalSum(Rows: IndexedSeq[UInt], ProductWidth: Int = 64): UInt = {
    if (Rows.length == 1) Rows.head else (Rows(0) +& Rows(1))(ProductWidth - 1, 0)
  }
}
//压缩树乘法器的握手、背压和内部流水寄存器。
abstract class ysyx_26030103_MULCompressionCore(
    config: ysyx_26030103_NPCConfig,
    UseDadda: Boolean
) extends ysyx_26030103_MULCore(config.MULWidth) {
  private val OperandWidth = config.MULWidth
  private val ProductWidth = 2 * OperandWidth
  private val CompressorConfig = config.MULCompressorConfig
  private val UseBooth = config.MULEncoding == ysyx_26030103_MULEncoding.Booth //是否使用Booth编码
  private val BoothRadix = if (UseBooth) config.MULRadix else 2 //Booth基数
  private val BoothBits = if (UseBooth) ysyx_26030103_MULBoothConfig.BoothBits(BoothRadix) else 1 //每组处理位数
  private val BoothGroups = if (UseBooth) (OperandWidth + 1 + BoothBits - 1) / BoothBits else 0 //Booth部分积行数
  private val BoothPaddedBits = BoothGroups * BoothBits //补齐后的乘数宽度
  private val BoothProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(BoothRadix, OperandWidth) //编码器部分积宽度
  // Booth 负部分积只保留局部的 K 位表示。对 K 位补码 p：
  // 符号扩展关系：sign_extend(p) = zero_extend(p) - sign(p) * 2^K；
  // InitialBoothCorrectionMagnitude 汇总这些减法补偿项。
  private val InitialRowsAndCorrections = if (!UseBooth) {
    (
      ysyx_26030103_MULCompressionUtils.PlainPartialProducts(
        IO.Req.bits.LHSMagnitude,
        IO.Req.bits.RHSMagnitude,
        OperandWidth,
        ProductWidth
      ),
      0.U(ProductWidth.W)
    )
  } else {
    val MultiplierPadding = BoothPaddedBits - OperandWidth
    val Multiplier = if (MultiplierPadding == 0) {
      Cat(IO.Req.bits.RHSMagnitude, 0.U(1.W))
    } else {
      Cat(0.U(MultiplierPadding.W), IO.Req.bits.RHSMagnitude, 0.U(1.W))
    }
    val Multiplicand = Cat(0.U(BoothBits.W), IO.Req.bits.LHSMagnitude)
    val Rows = scala.collection.mutable.ArrayBuffer[UInt]()
    val CorrectionBits = scala.collection.mutable.ArrayBuffer[(Int, Bool)]()
    for (Group <- 0 until BoothGroups) {
      val Encoder = Module(new ysyx_26030103_MULBoothEncoder(BoothRadix, OperandWidth))
      val WindowLow = Group * BoothBits
      Encoder.IO.Multiplicand := Multiplicand
      Encoder.IO.Window := Multiplier(WindowLow + BoothBits, WindowLow)
      val PartialProduct = Encoder.IO.PartialProduct
      val FiniteProduct = if (BoothProductWidth >= ProductWidth) {
        PartialProduct(ProductWidth - 1, 0)
      } else {
        Cat(0.U((ProductWidth - BoothProductWidth).W), PartialProduct)
      }
      Rows += (FiniteProduct << (Group * BoothBits))(ProductWidth - 1, 0)

      val CorrectionExponent = BoothProductWidth + Group * BoothBits
      if (CorrectionExponent < ProductWidth) {
        CorrectionBits += ((CorrectionExponent, PartialProduct(BoothProductWidth - 1)))
      }
    }
    val CorrectionMagnitudeBits = (0 until ProductWidth).map { Bit =>
      CorrectionBits.find(_._1 == Bit).map(_._2).getOrElse(false.B)
    }
    val CorrectionMagnitude = CorrectionMagnitudeBits.reverse.map(_.asUInt).reduce((High, Low) => Cat(High, Low))
    (Rows.toIndexedSeq, CorrectionMagnitude)
  }
  private val InitialRows = InitialRowsAndCorrections._1
  private val InitialBoothCorrectionMagnitude = InitialRowsAndCorrections._2
  // Wallace 与 Dadda 共用真实的稀疏部分积列。
  // 普通部分积第 r 行只占据 [r, r+N-1]；Booth 行只保留其 K 位
  // 有效段，符号扩展由单独的补偿点处理。
  private val InitialColumnRanges = if (!UseBooth) {
    (0 until InitialRows.length).map { Row =>
      (Row, (Row + OperandWidth - 1).min(ProductWidth - 1))
    }.toIndexedSeq
  } else {
    (0 until InitialRows.length).map { Group =>
      (Group * BoothBits, (Group * BoothBits + BoothProductWidth - 1).min(ProductWidth - 1))
    }.toIndexedSeq
  }
  private val InitialCompressionColumnsBase =
    ysyx_26030103_MULCompressionUtils.InitialDaddaColumns(InitialRows, InitialColumnRanges, ProductWidth)
  // 将 -CorrectionMagnitude 改写成 ~CorrectionMagnitude + 1，作为
  // 普通点注入 Dadda 初始列，避免在树外再串接一个 P 位减法器。
  private val InitialCompressionColumns = if (UseBooth) {
    val Columns = InitialCompressionColumnsBase.map(_.toBuffer).toArray
    val Complement = (~InitialBoothCorrectionMagnitude)(ProductWidth - 1, 0)
    for (Bit <- 0 until ProductWidth) {
      Columns(Bit) += Complement(Bit)
    }
    Columns(0) += true.B
    Columns.map(_.toIndexedSeq).toIndexedSeq
  } else {
    InitialCompressionColumnsBase
  }
  private val InitialColumnHeight = InitialCompressionColumns.map(_.length).max
  private val BaseSchedule = if (UseDadda) {
    ysyx_26030103_MULCompressionUtils.DaddaSchedule(InitialColumnHeight, CompressorConfig)
  } else {
    ysyx_26030103_MULCompressionUtils.WallaceColumnSchedule(
      InitialCompressionColumns.map(_.length),
      ProductWidth,
      CompressorConfig
    )
  }
  private val Schedule = if (BaseSchedule.length < config.MULPipeline) BaseSchedule ++ Seq.fill(config.MULPipeline - BaseSchedule.length)(2) else BaseSchedule
  private val BoundarySteps = (1 to config.MULPipeline).map { Boundary =>
    math.ceil(Schedule.length.toDouble * Boundary / (config.MULPipeline + 1)).toInt - 1
  }.toSet
  private val Valid = RegInit(VecInit(Seq.fill(config.MULPipeline + 1)(false.B)))
  private val Product = RegInit(0.U(ProductWidth.W))
  private val Advance = !Valid.last || IO.Resp.ready
  private val PipelineEnable = !IO.Flush && Advance
  IO.Req.ready := PipelineEnable
  IO.Resp.valid := Valid.last && !IO.Flush
  IO.Resp.bits.Product := Product
  private val NextProduct = if (UseDadda) {
    // 中间阶段始终保留稀疏列；只在最终两行形成后重组成 UInt。
    var Columns = InitialCompressionColumns
    for ((target, step) <- Schedule.zipWithIndex) {
      if (Columns.map(_.length).max > 2) {
        Columns = ysyx_26030103_MULCompressionUtils.DaddaReduceColumns(
          Columns,
          target,
          ProductWidth,
          CompressorConfig
        )
      }
      if (BoundarySteps.contains(step)) {
        Columns = ysyx_26030103_MULCompressionUtils.RegisterColumns(
          Columns,
          PipelineEnable,
          IO.Flush
        )
      }
    }
    ysyx_26030103_MULCompressionUtils.FinalSum(
      ysyx_26030103_MULCompressionUtils.ColumnsToRows(Columns, ProductWidth),
      ProductWidth
    )
  } else {
    // Wallace 每一级只消费当前层的 dots，carry 留给下一层。
    var Columns = InitialCompressionColumns
    for ((_, step) <- Schedule.zipWithIndex) {
      if (Columns.map(_.length).max > 2) {
        Columns = ysyx_26030103_MULCompressionUtils.WallaceReduceColumns(
          Columns,
          ProductWidth,
          CompressorConfig
        )
      }
      if (BoundarySteps.contains(step)) {
        Columns = ysyx_26030103_MULCompressionUtils.RegisterColumns(
          Columns,
          PipelineEnable,
          IO.Flush
        )
      }
    }
    ysyx_26030103_MULCompressionUtils.FinalSum(
      ysyx_26030103_MULCompressionUtils.ColumnsToRows(Columns, ProductWidth),
      ProductWidth
    )
  }
  when (IO.Flush) {
    for (Index <- Valid.indices) {
      Valid(Index) := false.B
    }
    Product := 0.U
  }.elsewhen (PipelineEnable) {
    for (Index <- Valid.indices) {
      Valid(Index) := (if (Index == 0) IO.Req.valid else Valid(Index - 1))
    }
    Product := NextProduct
  }
}
