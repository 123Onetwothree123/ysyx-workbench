package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//无符号部分积压缩树公共结构。
private object ysyx_26030103_MULCompressionUtils {
  def plainPartialProducts(lhs: UInt, rhs: UInt, operandWidth: Int = 32, productWidth: Int = 64): IndexedSeq[UInt] = {
    (0 until operandWidth).map { bit =>
      val shifted = (Cat(0.U(operandWidth.W), lhs) << bit)(productWidth - 1, 0)
      Mux(rhs(bit), shifted, 0.U(productWidth.W))
    }
  }
  final case class WeightedDot(Value: Bool, Offset: Int)
  def compressDots(
      Dots: IndexedSeq[Bool],
      Config: ysyx_26030103_MULGPCConfig
  ): IndexedSeq[WeightedDot] = {
    require(Dots.length >= 2)
    require(Dots.length <= Config.InputCountWithCin)
    val useCin = Config.HasCin && Dots.length > Config.Inputs
    val dataInputs = math.min(Dots.length, Config.Inputs)
    val compressor = Module(new ysyx_26030103_MULGPC(Config))
    for (index <- 0 until Config.Inputs) {
      compressor.IO.In(index) := (if (index < dataInputs) Dots(index) else false.B)
    }
    compressor.IO.Cin := (if (useCin) Dots(dataInputs) else false.B)
    Config.ActiveOutputBits(dataInputs, useCin).map { bit =>
      if (bit < Config.Outputs) {
        WeightedDot(compressor.IO.Out(bit), Config.OutputOffsets(bit))
      } else {
        WeightedDot(compressor.IO.Cout, Config.CoutOffset)
      }
    }
  }
  // 广义Wallace stage：每组按配置K:R GPC压缩，各加权输出写入
  // 下一层对应列，不在本级继续消费。
  def wallaceReduceColumns(
      InputColumns: IndexedSeq[IndexedSeq[Bool]],
      productWidth: Int,
      Config: ysyx_26030103_MULGPCConfig
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(InputColumns.length == productWidth)
    val nextColumns = Array.fill(productWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (bit <- 0 until productWidth) {
      val work = scala.collection.mutable.ArrayBuffer[Bool]()
      work ++= InputColumns(bit)
      while (work.length >= 3) {
        val groupSize = math.min(Config.InputCountWithCin, work.length)
        val group = work.take(groupSize).toIndexedSeq
        work.remove(0, groupSize)
        val outputs = compressDots(group, Config)
        for (output <- outputs if bit + output.Offset < productWidth) {
          nextColumns(bit + output.Offset) += output.Value
        }
      }
      nextColumns(bit) ++= work
    }
    nextColumns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 只用静态列高计算 Wallace 的层数，供流水边界均匀分布使用。
  def wallaceColumnSchedule(
      InitialHeights: IndexedSeq[Int],
      productWidth: Int,
      Config: ysyx_26030103_MULGPCConfig
  ): IndexedSeq[Int] = {
    require(InitialHeights.length == productWidth)
    val schedule = scala.collection.mutable.ArrayBuffer[Int]()
    var heights = InitialHeights
    while (heights.max > 2) {
      val nextHeights = Array.fill(productWidth)(0)
      for (bit <- 0 until productWidth) {
        var remaining = heights(bit)
        while (remaining >= 3) {
          val groupSize = math.min(Config.InputCountWithCin, remaining)
          remaining -= groupSize
          val useCin = Config.HasCin && groupSize > Config.Inputs
          val dataInputs = math.min(groupSize, Config.Inputs)
          for (outputBit <- Config.ActiveOutputBits(dataInputs, useCin)) {
            val offset = Config.AllOutputOffsets(outputBit)
            if (bit + offset < productWidth) {
              nextHeights(bit + offset) += 1
            }
          }
        }
        nextHeights(bit) += remaining
      }
      require(nextHeights.sum < heights.sum, "GPC配置无法继续压缩Wallace点阵")
      heights = nextHeights.toIndexedSeq
      schedule += heights.max
    }
    schedule.toIndexedSeq
  }
  // 将带有静态有效范围的部分积行放入列点阵。
  // 范围外的位不是“值为零的点”，而是不存在的点；这是标准
  // Dadda 与把所有行补齐到完整字长再压缩的实现之间的关键区别。
  def initialDaddaColumns(
      rows: IndexedSeq[UInt],
      ValidRanges: IndexedSeq[(Int, Int)],
      productWidth: Int = 64
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(rows.length == ValidRanges.length)
    val columns = Array.fill(productWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    for ((row, bounds) <- rows.zip(ValidRanges)) {
      val (low, high) = bounds
      require(low >= 0 && high < productWidth && low <= high)
      for (bit <- low to high) {
        columns(bit) += row(bit)
      }
    }
    columns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 对稀疏列点阵执行一个由配置K:R GPC驱动的广义Dadda stage。
  def daddaReduceColumns(
      InputColumns: IndexedSeq[IndexedSeq[Bool]],
      Target: Int,
      productWidth: Int = 64,
      Config: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
  ): IndexedSeq[IndexedSeq[Bool]] = {
    require(InputColumns.length == productWidth)
    require(Target >= 2)
    // 当前 stage 在低位列产生的 carry 会作为下一列的输入点参与同一轮
    // 的列高计算。这正是标准 Dadda 列扫描的做法；若把它只计数而不
    // 放回 Work，会得到一个功能等价但压缩器布局不标准的网络。
    val incomingCarries = Array.fill(productWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    val reducedColumns = Array.fill(productWidth)(scala.collection.mutable.ArrayBuffer[Bool]())
    for (bit <- 0 until productWidth) {
      val work = scala.collection.mutable.ArrayBuffer[Bool]()
      work ++= InputColumns(bit)
      work ++= incomingCarries(bit)
      while (work.length + reducedColumns(bit).length > Target) {
        val excess = work.length + reducedColumns(bit).length - Target
        val candidateGroups = (2 to math.min(Config.InputCountWithCin, work.length)).map { groupSize =>
          val useCin = Config.HasCin && groupSize > Config.Inputs
          val dataInputs = math.min(groupSize, Config.Inputs)
          val sameColumnOutputs = Config
            .ActiveOutputBits(dataInputs, useCin)
            .count(outputBit => Config.AllOutputOffsets(outputBit) == 0)
          (groupSize, groupSize - sameColumnOutputs)
        }.filter(_._2 > 0)
        require(candidateGroups.nonEmpty, "GPC配置无法降低当前Dadda列高")
        val withinExcess = candidateGroups.filter(_._2 <= excess)
        val groupSize = if (withinExcess.nonEmpty) {
          withinExcess.maxBy { case (inputs, reduction) => (reduction, inputs) }._1
        } else {
          candidateGroups.minBy { case (inputs, reduction) => (reduction, -inputs) }._1
        }
        val group = work.take(groupSize).toIndexedSeq
        work.remove(0, groupSize)
        val outputs = compressDots(group, Config)
        for (output <- outputs) {
          if (output.Offset == 0) {
            reducedColumns(bit) += output.Value
          } else if (bit + output.Offset < productWidth) {
            incomingCarries(bit + output.Offset) += output.Value
          }
        }
      }
      reducedColumns(bit) ++= work
    }
    require(
      reducedColumns.forall(_.length <= Target),
      s"Dadda reduction exceeded target height $Target"
    )
    reducedColumns.map(_.toIndexedSeq).toIndexedSeq
  }

  // 仅在最后两行已经形成后才重新拼接成 UInt；中间阶段始终保持稀疏列。
  def columnsToRows(columns: IndexedSeq[IndexedSeq[Bool]], productWidth: Int = 64): IndexedSeq[UInt] = {
    require(columns.length == productWidth)
    val rowCount = columns.map(_.length).max
    (0 until rowCount).map { rowIndex =>
      val rowBits = (0 until productWidth).map { bit =>
        if (rowIndex < columns(bit).length) columns(bit)(rowIndex) else false.B
      }
      rowBits.reverse.map(_.asUInt).reduce((High, Low) => Cat(High, Low))
    }
  }

  // 在 Dadda stage 之间插入按列保存的寄存器，不把稀疏列重新补成完整行。
  def registerColumns(
      columns: IndexedSeq[IndexedSeq[Bool]],
      advance: Bool,
      Clear: Bool = false.B
  ): IndexedSeq[IndexedSeq[Bool]] = {
    columns.map { column =>
      if (column.isEmpty) {
        column
      } else {
        val boundary = RegInit(VecInit(Seq.fill(column.length)(false.B)))
        when (Clear) {
          for (index <- column.indices) {
            boundary(index) := false.B
          }
        }.elsewhen (advance) {
          for (index <- column.indices) {
            boundary(index) := column(index)
          }
        }
        boundary.toIndexedSeq
      }
    }
  }
  // 按配置GPC的有效输入/输出dot数量生成广义Dadda目标高度。
  def daddaSchedule(
      MaxHeight: Int,
      Config: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
  ): IndexedSeq[Int] = {
    val inputDots = Config.InputCountWithCin
    val outputDots = Config.FullActiveOutputBits.length
    val targets = scala.collection.mutable.ArrayBuffer(2)
    while (targets.last < MaxHeight) {
      val scaled = targets.last * inputDots / outputDots
      targets += math.max(targets.last + 1, scaled)
    }
    targets.dropRight(1).reverse.toIndexedSeq
  }
  def finalSum(rows: IndexedSeq[UInt], productWidth: Int = 64): UInt = {
    if (rows.length == 1) rows.head else (rows(0) +& rows(1))(productWidth - 1, 0)
  }
}
//压缩树乘法器的握手、背压和内部流水寄存器。
abstract class ysyx_26030103_MULCompressionCore(
    config: ysyx_26030103_NPCConfig,
    UseDadda: Boolean
) extends ysyx_26030103_MULCore(config.MULEffectiveWidth) {
  private val operandWidth = config.MULEffectiveWidth
  private val productWidth = 2 * operandWidth
  private val compressorConfig = config.MDUProfile.MUL.Compression.Compressor
  private val useBooth = config.MDUProfile.MUL.Encoding == ysyx_26030103_MULEncoding.Booth //是否使用Booth编码
  private val boothRadix = if (useBooth) config.MDUProfile.MUL.Radix else 2 //Booth基数
  private val boothBits = if (useBooth) ysyx_26030103_MULBoothConfig.BoothBits(boothRadix) else 1 //每组处理位数
  private val boothGroups = if (useBooth) (operandWidth + 1 + boothBits - 1) / boothBits else 0 //Booth部分积行数
  private val boothPaddedBits = boothGroups * boothBits //补齐后的乘数宽度
  private val boothProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(boothRadix, operandWidth) //编码器部分积宽度
  // Booth 负部分积只保留局部的 K 位表示。对 K 位补码 p：
  // 符号扩展关系：sign_extend(p) = zero_extend(p) - sign(p) * 2^K；
  // InitialBoothCorrectionMagnitude 汇总这些减法补偿项。
  private val initialRowsAndCorrections = if (!useBooth) {
    (
      ysyx_26030103_MULCompressionUtils.plainPartialProducts(
        IO.Request.bits.LHSMagnitude,
        IO.Request.bits.RHSMagnitude,
        operandWidth,
        productWidth
      ),
      0.U(productWidth.W)
    )
  } else {
    val multiplierPadding = boothPaddedBits - operandWidth
    val multiplier = if (multiplierPadding == 0) {
      Cat(IO.Request.bits.RHSMagnitude, 0.U(1.W))
    } else {
      Cat(0.U(multiplierPadding.W), IO.Request.bits.RHSMagnitude, 0.U(1.W))
    }
    val multiplicand = Cat(0.U(boothBits.W), IO.Request.bits.LHSMagnitude)
    val rows = scala.collection.mutable.ArrayBuffer[UInt]()
    val correctionBits = scala.collection.mutable.ArrayBuffer[(Int, Bool)]()
    for (group <- 0 until boothGroups) {
      val encoder = Module(new ysyx_26030103_MULBoothEncoder(boothRadix, operandWidth))
      val windowLow = group * boothBits
      encoder.IO.Multiplicand := multiplicand
      encoder.IO.Window := multiplier(windowLow + boothBits, windowLow)
      val partialProduct = encoder.IO.PartialProduct
      val finiteProduct = if (boothProductWidth >= productWidth) {
        partialProduct(productWidth - 1, 0)
      } else {
        Cat(0.U((productWidth - boothProductWidth).W), partialProduct)
      }
      rows += (finiteProduct << (group * boothBits))(productWidth - 1, 0)

      val correctionExponent = boothProductWidth + group * boothBits
      if (correctionExponent < productWidth) {
        correctionBits += ((correctionExponent, partialProduct(boothProductWidth - 1)))
      }
    }
    val correctionMagnitudeBits = (0 until productWidth).map { bit =>
      correctionBits.find(_._1 == bit).map(_._2).getOrElse(false.B)
    }
    val correctionMagnitude = correctionMagnitudeBits.reverse.map(_.asUInt).reduce((High, Low) => Cat(High, Low))
    (rows.toIndexedSeq, correctionMagnitude)
  }
  private val initialRows = initialRowsAndCorrections._1
  private val initialBoothCorrectionMagnitude = initialRowsAndCorrections._2
  // Wallace 与 Dadda 共用真实的稀疏部分积列。
  // 普通部分积第 r 行只占据 [r, r+N-1]；Booth 行只保留其 K 位
  // 有效段，符号扩展由单独的补偿点处理。
  private val initialColumnRanges = if (!useBooth) {
    (0 until initialRows.length).map { row =>
      (row, (row + operandWidth - 1).min(productWidth - 1))
    }.toIndexedSeq
  } else {
    (0 until initialRows.length).map { group =>
      (group * boothBits, (group * boothBits + boothProductWidth - 1).min(productWidth - 1))
    }.toIndexedSeq
  }
  private val initialCompressionColumnsBase =
    ysyx_26030103_MULCompressionUtils.initialDaddaColumns(initialRows, initialColumnRanges, productWidth)
  // 将 -CorrectionMagnitude 改写成 ~CorrectionMagnitude + 1，作为
  // 普通点注入 Dadda 初始列，避免在树外再串接一个 P 位减法器。
  private val initialCompressionColumns = if (useBooth) {
    val columns = initialCompressionColumnsBase.map(_.toBuffer).toArray
    val complement = (~initialBoothCorrectionMagnitude)(productWidth - 1, 0)
    for (bit <- 0 until productWidth) {
      columns(bit) += complement(bit)
    }
    columns(0) += true.B
    columns.map(_.toIndexedSeq).toIndexedSeq
  } else {
    initialCompressionColumnsBase
  }
  private val initialColumnHeight = initialCompressionColumns.map(_.length).max
  private val baseSchedule = if (UseDadda) {
    ysyx_26030103_MULCompressionUtils.daddaSchedule(initialColumnHeight, compressorConfig)
  } else {
    ysyx_26030103_MULCompressionUtils.wallaceColumnSchedule(
      initialCompressionColumns.map(_.length),
      productWidth,
      compressorConfig
    )
  }
  private val schedule = if (baseSchedule.length < config.MDUProfile.MUL.Compression.Pipeline) baseSchedule ++ Seq.fill(config.MDUProfile.MUL.Compression.Pipeline - baseSchedule.length)(2) else baseSchedule
  private val boundarySteps = (1 to config.MDUProfile.MUL.Compression.Pipeline).map { boundary =>
    math.ceil(schedule.length.toDouble * boundary / (config.MDUProfile.MUL.Compression.Pipeline + 1)).toInt - 1
  }.toSet
  private val valid = RegInit(VecInit(Seq.fill(config.MDUProfile.MUL.Compression.Pipeline + 1)(false.B)))
  private val product = RegInit(0.U(productWidth.W))
  private val advance = !valid.last || IO.Response.ready
  private val pipelineEnable = !IO.Flush && advance
  IO.Request.ready := pipelineEnable
  IO.Response.valid := valid.last && !IO.Flush
  IO.Response.bits.Product := product
  private val nextProduct = if (UseDadda) {
    // 中间阶段始终保留稀疏列；只在最终两行形成后重组成 UInt。
    var columns = initialCompressionColumns
    for ((target, step) <- schedule.zipWithIndex) {
      if (columns.map(_.length).max > 2) {
        columns = ysyx_26030103_MULCompressionUtils.daddaReduceColumns(
          columns,
          target,
          productWidth,
          compressorConfig
        )
      }
      if (boundarySteps.contains(step)) {
        columns = ysyx_26030103_MULCompressionUtils.registerColumns(
          columns,
          pipelineEnable,
          IO.Flush
        )
      }
    }
    ysyx_26030103_MULCompressionUtils.finalSum(
      ysyx_26030103_MULCompressionUtils.columnsToRows(columns, productWidth),
      productWidth
    )
  } else {
    // Wallace 每一级只消费当前层的 dots，carry 留给下一层。
    var columns = initialCompressionColumns
    for ((_, step) <- schedule.zipWithIndex) {
      if (columns.map(_.length).max > 2) {
        columns = ysyx_26030103_MULCompressionUtils.wallaceReduceColumns(
          columns,
          productWidth,
          compressorConfig
        )
      }
      if (boundarySteps.contains(step)) {
        columns = ysyx_26030103_MULCompressionUtils.registerColumns(
          columns,
          pipelineEnable,
          IO.Flush
        )
      }
    }
    ysyx_26030103_MULCompressionUtils.finalSum(
      ysyx_26030103_MULCompressionUtils.columnsToRows(columns, productWidth),
      productWidth
    )
  }
  when (IO.Flush) {
    for (index <- valid.indices) {
      valid(index) := false.B
    }
    product := 0.U
  }.elsewhen (pipelineEnable) {
    for (index <- valid.indices) {
      valid(index) := (if (index == 0) IO.Request.valid else valid(index - 1))
    }
    product := nextProduct
  }
}
