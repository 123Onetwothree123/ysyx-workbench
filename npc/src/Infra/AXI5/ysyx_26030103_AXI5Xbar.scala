package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_AXI5Xbar(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
    PLICBaseValue: BigInt =
      BigInt(ysyx_26030103_PhysicalMemoryMap.DefaultPLICBase),
    PLICSizeBytes: BigInt =
      BigInt(ysyx_26030103_PhysicalMemoryMap.DefaultPLICSize),
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth,
    ResponseQueueDepth: Int = ysyx_26030103_KconfigDefaults.AXIXbarResponseQueueDepth,
    CLINTBaseValue: BigInt =
      BigInt(ysyx_26030103_PMAOptions.Default.clintBase),
    CLINTSizeBytes: BigInt =
      BigInt(ysyx_26030103_PMAOptions.Default.clintSize)
) extends Module {
  require(DataWidth == 32 || DataWidth == 64,
    s"AXI5Xbar DataWidth 非法: $DataWidth")
  require(ResponseQueueDepth >= 2 && ResponseQueueDepth <= 16,
    s"AXI5Xbar RespQueueDepth 非法: $ResponseQueueDepth")
  private val busLaneBits = log2Ceil(DataWidth / 8)
  val io = IO(new Bundle {
    // 先做过笔记，因为这个是接裁决器的，裁决器是master，所以这里的对反
    val in = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    val SoCBus = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
    val CLINT = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
    val PLIC = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
  })
  // 我真的是烦死了ARM的命名规则
  val OKAY = "b00".U(2.W)
  val DECERR = "b11".U(2.W)
  // RISC-V标准CLINT区间：默认 0x02000000 ~ 0x0200FFFF（64KiB）。
  require(CLINTBaseValue >= 0 && CLINTBaseValue < (BigInt(1) << AddressWidth),
    "CLINT 基地址超出地址宽度")
  require(CLINTSizeBytes > 0 && CLINTBaseValue + CLINTSizeBytes <= (BigInt(1) << AddressWidth),
    "CLINT 地址窗口超出地址宽度")
  val clintBase = CLINTBaseValue.U(AddressWidth.W)
  val clintEnd = (CLINTBaseValue + CLINTSizeBytes).U(AddressWidth.W)
  require(PLICBaseValue >= 0 && PLICBaseValue < (BigInt(1) << AddressWidth),
    "PLIC 基地址超出地址宽度")
  require(PLICSizeBytes > 0 && PLICBaseValue + PLICSizeBytes <= (BigInt(1) << AddressWidth),
    "PLIC 地址窗口超出地址宽度")
  require(PLICBaseValue % BigInt(4096) == BigInt(0),
    "PLIC 基地址必须 4KiB 对齐")
  require(PLICSizeBytes % BigInt(4096) == BigInt(0),
    "PLIC 窗口大小必须为 4KiB 的整数倍")
  val plicBase = PLICBaseValue.U(AddressWidth.W)
  val plicEnd = (PLICBaseValue + PLICSizeBytes).U(AddressWidth.W)
  val targetSoCBus = 0.U(2.W)
  val targetCLINT = 1.U(2.W)
  val targetPLIC = 2.U(2.W)
  val targetInvalid = 3.U(2.W)
  io.PLIC.AR.ARVALID := false.B
  io.PLIC.AR.ARID := 0.U
  io.PLIC.AR.ARADDR := 0.U
  io.PLIC.AR.ARLEN := 0.U
  io.PLIC.AR.ARSIZE := 2.U
  io.PLIC.AR.ARBURST := 0.U
  io.PLIC.AR.ARPROT := 0.U
  io.PLIC.AR.ARCACHE := 0.U
  io.PLIC.AR.ARQOS := 0.U
  io.PLIC.R.RREADY := false.B
  io.PLIC.AW.AWVALID := false.B
  io.PLIC.AW.AWID := 0.U
  io.PLIC.AW.AWADDR := 0.U
  io.PLIC.AW.AWLEN := 0.U
  io.PLIC.AW.AWSIZE := 2.U
  io.PLIC.AW.AWBURST := 0.U
  io.PLIC.AW.AWPROT := 0.U
  io.PLIC.AW.AWCACHE := 0.U
  io.PLIC.AW.AWQOS := 0.U
  io.PLIC.W.WVALID := false.B
  io.PLIC.W.WDATA := 0.U
  io.PLIC.W.WSTRB := 0.U
  io.PLIC.W.WLAST := false.B
  io.PLIC.B.BREADY := false.B
  require(PMARegions.nonEmpty, "AXI Xbar物理地址图不能为空")
  require(
    PMARegions.forall(r => BigInt(r.EndExclusive) <= (BigInt(1) << AddressWidth)),
    "AXI Xbar PMA区域超出地址宽度"
  )

  private def lastByte(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt
  ): UInt =
    ysyx_26030103_AXI5Burst.lastByte(address, len, size, burst)
  private def inRegion(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      region: ysyx_26030103_PMARegion
  ): Bool = {
    val extendedAddress = Cat(0.U(1.W), address)
    val lastAddress = lastByte(address, len, size, burst)
    extendedAddress >= BigInt(region.Base).U &&
    lastAddress < BigInt(region.EndExclusive).U
  }
  private def inAnyRegion(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      regions: Seq[ysyx_26030103_PMARegion]
  ): Bool =
    regions
      .map(inRegion(address, len, size, burst, _))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  def isCLINT(address: UInt, len: UInt, size: UInt, burst: UInt): Bool = {
    val lastAddress = lastByte(address, len, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), clintBase) &&
    lastAddress < Cat(0.U(1.W), clintEnd)
  }
  def isPLIC(address: UInt, len: UInt, size: UInt, burst: UInt): Bool = {
    val lastAddress = lastByte(address, len, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), plicBase) &&
    lastAddress < Cat(0.U(1.W), plicEnd)
  }
  private val readableRegions = PMARegions.filter(_.Readable)
  private val writableRegions = PMARegions.filter(_.Writable)
  // PLIC 与 CLINT 一样必须经过 PMA 权限检查。基地址/窗口来自配置，
  // 因此按“某个可读/可写区域完整覆盖整个窗口”判定。
  private def coversRegion(region: ysyx_26030103_PMARegion,
                           base: BigInt,
                           size: BigInt): Boolean =
    BigInt(region.Base) <= base &&
      BigInt(region.EndExclusive) >= base + size
  private val readableCLINT = readableRegions.exists(region =>
    coversRegion(region, CLINTBaseValue, CLINTSizeBytes))
  private val writableCLINT = writableRegions.exists(region =>
    coversRegion(region, CLINTBaseValue, CLINTSizeBytes))
  private val readablePLIC = readableRegions.exists(region =>
    coversRegion(region, PLICBaseValue, PLICSizeBytes))
  private val writablePLIC = writableRegions.exists(region =>
    coversRegion(region, PLICBaseValue, PLICSizeBytes))

  def decode(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      isWrite: Boolean
  ): UInt = {
    val configSupported =
      size <= busLaneBits.U && (burst === 0.U || burst === 1.U)
    val permittedRegions = if (isWrite) writableRegions else readableRegions
    val clintPermitted = if (isWrite) writableCLINT else readableCLINT
    val plicPermitted = if (isWrite) writablePLIC else readablePLIC
    // PLIC 叶子只实现单拍寄存器访问：它不处理 AWLEN/WLAST，读响应也
    // 只在首拍置 RLAST。因此地址落在 PLIC 窗口但 burst 非单拍时，必须
    // 在 Xbar 本地判为非法，不能继续按起始地址转发给 PLIC。PMA 未授予
    // 读/写权限时同样判为非法，绝不能让 PLIC 端口绕过 CLINT/SoCBus 共享
    // 的权限检查。
    val plicAddress = isPLIC(address, 0.U, size, burst)
    Mux(
      !configSupported,
      targetInvalid,
      Mux(
        clintPermitted.B && isCLINT(address, len, size, burst),
        targetCLINT,
        Mux(
          plicAddress,
          Mux(
            plicPermitted.B && len === 0.U && size <= 2.U,
            targetPLIC,
            targetInvalid
          ),
          Mux(
            inAnyRegion(address, len, size, burst, permittedRegions),
            targetSoCBus,
            targetInvalid
          )
        )
      )
    )
  }
  // AXI读写通道相互独立。读状态按RID分别保存，因此固定的IFU/LSU ID
  // 都可以同时处于下游互连结构的在途状态。
  private val readSlotCount = 1 << IDWidth
  val readSlotStates = Enum(5)
  val readSlotFree = readSlotStates(0)
  val readSlotRequest = readSlotStates(1)
  val readSlotResponse = readSlotStates(2)
  val readSlotDECERR = readSlotStates(3)
  val readSlotLocalLast = readSlotStates(4)
  val readSlotState = RegInit(
    VecInit(Seq.fill(readSlotCount)(readSlotFree))
  )
  val readTargetReg = RegInit(
    VecInit(Seq.fill(readSlotCount)(targetInvalid))
  )
  val arAddressReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(AddressWidth.W))))
  val arLenReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(8.W))))
  val arSizeReg = RegInit(VecInit(Seq.fill(readSlotCount)(2.U(3.W))))
  val arBurstReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(2.W))))
  val arProtReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(3.W))))
  val arCacheReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(4.W))))
  val arQosReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(4.W))))
  val readDECERRBeatReg = RegInit(
    VecInit(Seq.fill(readSlotCount)(0.U(8.W)))
  )

  val writeStates = Enum(6)
  val stateWriteIdle = writeStates(0)
  val stateWriteCollect = writeStates(1)
  val stateWriteRequest = writeStates(2)
  val stateWriteResponse = writeStates(3)
  val stateWriteDECERR = writeStates(4)
  // 不支持转发的写burst仍需吃完全部W beat，之后再返回DECERR。
  val stateWriteDrain = writeStates(5)
  val writeState = RegInit(stateWriteIdle)
  val awValidReg = RegInit(false.B)
  val wValidReg = RegInit(false.B)
  val awIDReg = RegInit(0.U(IDWidth.W))
  val awAddressReg = RegInit(0.U(AddressWidth.W))
  val awLenReg = RegInit(0.U(8.W))
  val awSizeReg = RegInit(2.U(3.W))
  val awBurstReg = RegInit(0.U(2.W))
  val awProtReg = RegInit(0.U(3.W))
  val awCacheReg = RegInit(0.U(4.W))
  val awQosReg = RegInit(0.U(4.W))
  val wDataReg = RegInit(0.U(DataWidth.W))
  val wStrobeReg = RegInit(0.U((DataWidth / 8).W))
  val wLastReg = RegInit(false.B)
  // 写响应B通道回来时也没有地址，所以要记住写请求目标
  val writeTargetReg = RegInit(targetInvalid)
  val downstreamAWDone = RegInit(false.B)
  val downstreamWDone = RegInit(false.B)
  // 用 Wire 控制上游 ready，避免直接读自己驱动的输出端口。
  val inAWReady = WireDefault(false.B)
  val inWReady = WireDefault(false.B)
  val inARReady = WireDefault(false.B)
  io.in.AW.AWREADY := inAWReady
  io.in.W.WREADY := inWReady
  io.in.AR.ARREADY := inARReady

  // 默认值
  io.in.B.BID := 0.U
  io.in.B.BVALID := false.B
  io.in.B.BRESP := OKAY
  io.in.R.RID := 0.U
  io.in.R.RVALID := false.B
  io.in.R.RDATA := 0.U
  io.in.R.RRESP := OKAY
  io.in.R.RLAST := false.B
  io.SoCBus.AW.AWVALID := false.B
  io.SoCBus.AW.AWID := 0.U
  io.SoCBus.AW.AWADDR := 0.U
  io.SoCBus.AW.AWLEN := 0.U
  io.SoCBus.AW.AWSIZE := 2.U
  io.SoCBus.AW.AWBURST := 0.U
  io.SoCBus.AW.AWPROT := 0.U
  io.SoCBus.AW.AWCACHE := 0.U
  io.SoCBus.AW.AWQOS := 0.U
  io.SoCBus.W.WVALID := false.B
  io.SoCBus.W.WDATA := 0.U
  io.SoCBus.W.WSTRB := 0.U
  io.SoCBus.W.WLAST := false.B
  io.SoCBus.B.BREADY := false.B
  io.SoCBus.AR.ARVALID := false.B
  io.SoCBus.AR.ARID := 0.U
  io.SoCBus.AR.ARADDR := 0.U
  io.SoCBus.AR.ARLEN := 0.U
  io.SoCBus.AR.ARSIZE := 2.U
  io.SoCBus.AR.ARBURST := 0.U
  io.SoCBus.AR.ARPROT := 0.U
  io.SoCBus.AR.ARCACHE := 0.U
  io.SoCBus.AR.ARQOS := 0.U
  io.SoCBus.R.RREADY := false.B
  io.CLINT.AW.AWVALID := false.B
  io.CLINT.AW.AWID := 0.U
  io.CLINT.AW.AWADDR := 0.U
  io.CLINT.AW.AWLEN := 0.U
  io.CLINT.AW.AWSIZE := 2.U
  io.CLINT.AW.AWBURST := 0.U
  io.CLINT.AW.AWPROT := 0.U
  io.CLINT.AW.AWCACHE := 0.U
  io.CLINT.AW.AWQOS := 0.U
  io.CLINT.W.WVALID := false.B
  io.CLINT.W.WDATA := 0.U
  io.CLINT.W.WSTRB := 0.U
  io.CLINT.W.WLAST := false.B
  io.CLINT.B.BREADY := false.B
  io.CLINT.AR.ARVALID := false.B
  io.CLINT.AR.ARID := 0.U
  io.CLINT.AR.ARADDR := 0.U
  io.CLINT.AR.ARLEN := 0.U
  io.CLINT.AR.ARSIZE := 2.U
  io.CLINT.AR.ARBURST := 0.U
  io.CLINT.AR.ARPROT := 0.U
  io.CLINT.AR.ARCACHE := 0.U
  io.CLINT.AR.ARQOS := 0.U
  io.CLINT.R.RREADY := false.B
  val inAWFire = io.in.AW.AWVALID && inAWReady
  val inWFire = io.in.W.WVALID && inWReady
  val inARFire = io.in.AR.ARVALID && inARReady

  // 写通道
  val writeTargetAfterAW =
    Mux(
      inAWFire,
      decode(
        io.in.AW.AWADDR,
        io.in.AW.AWLEN,
        io.in.AW.AWSIZE,
        io.in.AW.AWBURST,
        isWrite = true
      ),
      writeTargetReg
    )
  val writeLenAfterAW = Mux(inAWFire, io.in.AW.AWLEN, awLenReg)
  val awCollected = awValidReg || inAWFire
  val wCollected = wValidReg || inWFire
  val firstWLastAfterW = Mux(inWFire, io.in.W.WLAST, wLastReg)
  // CLINT 叶子已支持 AWLEN=1 的 64 位 mtimecmp 写（两拍 W 数据），
  // 因此这里只保留 PLIC 的单拍限制。
  val awBurstAfterAW = Mux(inAWFire, io.in.AW.AWBURST, awBurstReg)
  val writeCannotForward = writeTargetAfterAW === targetInvalid ||
    // CLINT 只支持自然递增的 64 位 mtimecmp 两拍写；FIXED/WRAP 多拍
    // 地址不递增，不能按 INCR 处理，直接本地 DECERR。
    (writeTargetAfterAW === targetCLINT && writeLenAfterAW =/= 0.U &&
      awBurstAfterAW =/= 1.U) ||
    (writeTargetAfterAW === targetPLIC && writeLenAfterAW =/= 0.U)
  when(inAWFire) {
    awIDReg := io.in.AW.AWID
    awAddressReg := io.in.AW.AWADDR
    awLenReg := io.in.AW.AWLEN
    awSizeReg := io.in.AW.AWSIZE
    awBurstReg := io.in.AW.AWBURST
    awProtReg := io.in.AW.AWPROT
    awCacheReg := io.in.AW.AWCACHE
    awQosReg := io.in.AW.AWQOS
    writeTargetReg := decode(
      io.in.AW.AWADDR,
      io.in.AW.AWLEN,
      io.in.AW.AWSIZE,
      io.in.AW.AWBURST,
      isWrite = true
    )
    awValidReg := true.B
  }
  // Idle/Collect阶段只缓存首个W beat；进入转发阶段后其余beat直接流过。
  when(
    inWFire &&
      (writeState === stateWriteIdle || writeState === stateWriteCollect)
  ) {
    wDataReg := io.in.W.WDATA
    wStrobeReg := io.in.W.WSTRB
    wLastReg := io.in.W.WLAST
    wValidReg := true.B
  }
  // 直接把 BREADY 表达成“响应态 + 目标选择 + 上游 ready”的组合条件，
  // 不要放在 B 响应 when 分支里赋值。Chisel/firtool 在后者会生成
  // `~(... | writeState==Response) & (writeState==Response)` 的恒假表达式，
  // 导致 PLIC 写永远等不到 B 握手（读路径不受影响）。
  val inWriteResponse = writeState === stateWriteResponse
  io.SoCBus.B.BREADY :=
    inWriteResponse && writeTargetReg === targetSoCBus && io.in.B.BREADY
  io.CLINT.B.BREADY :=
    inWriteResponse && writeTargetReg === targetCLINT && io.in.B.BREADY
  io.PLIC.B.BREADY :=
    inWriteResponse && writeTargetReg === targetPLIC && io.in.B.BREADY
  // B 通道默认值；各目标分支和下方 PLIC 专用组合逻辑会覆盖它。
  io.in.B.BID := 0.U
  io.in.B.BVALID := false.B
  io.in.B.BRESP := OKAY

  when(writeState === stateWriteIdle) {
    val hasWriteRequest =
      io.in.AW.AWVALID || io.in.W.WVALID || awValidReg || wValidReg
    when(hasWriteRequest) { // 哪个通道还没缓存，就对哪个通道拉ready
      inAWReady := !awValidReg
      inWReady := !wValidReg

      // 如果本周期结束后AW和W都已经收到了，就可以进入转发阶段
      when(awCollected && wCollected) {
        downstreamAWDone := false.B
        downstreamWDone := false.B
        when(writeCannotForward) {
          // 首beat已由收集级接收；若它不是最后一拍，继续排空余下W。
          wValidReg := false.B
          writeState := Mux(
            firstWLastAfterW,
            stateWriteDECERR,
            stateWriteDrain
          )
        }.otherwise {
          writeState := stateWriteRequest
        }
      }.otherwise {
        writeState := stateWriteCollect
      }
    }
  }.elsewhen(writeState === stateWriteCollect) {
    // 已经开始处理写事务，但是AW和W还没有都收到
    // 例如AW先到、W后到，或者W先到、AW后到
    inAWReady := !awValidReg
    inWReady := !wValidReg

    when(awCollected && wCollected) {
      downstreamAWDone := false.B
      downstreamWDone := false.B
      when(writeCannotForward) {
        wValidReg := false.B
        writeState := Mux(
          firstWLastAfterW,
          stateWriteDECERR,
          stateWriteDrain
        )
      }.otherwise {
        writeState := stateWriteRequest
      }
    }
  }.elsewhen(writeState === stateWriteRequest) {
    // AW只发送一次；收集级缓存首个W beat，之后的beat直接以ready/valid
    // 流过，直到真正握手的WLAST为止。
    when(writeTargetReg === targetSoCBus) {
      val sendAW = !downstreamAWDone
      val useBufferedW = wValidReg
      val streamW = !wValidReg && !downstreamWDone

      io.SoCBus.AW.AWVALID := sendAW
      io.SoCBus.AW.AWID := awIDReg
      io.SoCBus.AW.AWADDR := awAddressReg
      io.SoCBus.AW.AWLEN := awLenReg
      io.SoCBus.AW.AWSIZE := awSizeReg
      io.SoCBus.AW.AWBURST := awBurstReg
      io.SoCBus.AW.AWPROT := awProtReg
      io.SoCBus.AW.AWCACHE := awCacheReg
      io.SoCBus.AW.AWQOS := awQosReg

      io.SoCBus.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
      io.SoCBus.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
      io.SoCBus.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
      io.SoCBus.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
      inWReady := streamW && io.SoCBus.W.WREADY

      val awFire = sendAW && io.SoCBus.AW.AWREADY
      val bufferedWFire = useBufferedW && io.SoCBus.W.WREADY
      val streamWFire = streamW && io.in.W.WVALID && io.SoCBus.W.WREADY
      val wFire = bufferedWFire || streamWFire
      val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

      when(awFire) {
        downstreamAWDone := true.B
      }
      when(bufferedWFire) {
        wValidReg := false.B
      }
      when(wLastFire) {
        downstreamWDone := true.B
      }

      when((downstreamAWDone || awFire) && (downstreamWDone || wLastFire)) {
        writeState := stateWriteResponse
      }
    }.elsewhen(writeTargetReg === targetCLINT) {
      val sendAW = !downstreamAWDone
      val useBufferedW = wValidReg
      val streamW = !wValidReg && !downstreamWDone

      io.CLINT.AW.AWVALID := sendAW
      io.CLINT.AW.AWID := awIDReg
      io.CLINT.AW.AWADDR := awAddressReg
      io.CLINT.AW.AWLEN := awLenReg
      io.CLINT.AW.AWSIZE := awSizeReg
      io.CLINT.AW.AWBURST := awBurstReg
      io.CLINT.AW.AWPROT := awProtReg
      io.CLINT.AW.AWCACHE := awCacheReg
      io.CLINT.AW.AWQOS := awQosReg

      io.CLINT.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
      io.CLINT.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
      io.CLINT.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
      io.CLINT.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
      inWReady := streamW && io.CLINT.W.WREADY

      val awFire = sendAW && io.CLINT.AW.AWREADY
      val bufferedWFire = useBufferedW && io.CLINT.W.WREADY
      val streamWFire = streamW && io.in.W.WVALID && io.CLINT.W.WREADY
      val wFire = bufferedWFire || streamWFire
      val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

      when(awFire) {
        downstreamAWDone := true.B
      }
      when(bufferedWFire) {
        wValidReg := false.B
      }
      when(wLastFire) {
        downstreamWDone := true.B
      }

      when((downstreamAWDone || awFire) && (downstreamWDone || wLastFire)) {
        writeState := stateWriteResponse
      }
    }.elsewhen(writeTargetReg === targetPLIC) {
      val sendAW = !downstreamAWDone
      val useBufferedW = wValidReg
      val streamW = !wValidReg && !downstreamWDone

      io.PLIC.AW.AWVALID := sendAW
      io.PLIC.AW.AWID := awIDReg
      io.PLIC.AW.AWADDR := awAddressReg
      io.PLIC.AW.AWLEN := awLenReg
      io.PLIC.AW.AWSIZE := awSizeReg
      io.PLIC.AW.AWBURST := awBurstReg
      io.PLIC.AW.AWPROT := awProtReg
      io.PLIC.AW.AWCACHE := awCacheReg
      io.PLIC.AW.AWQOS := awQosReg

      io.PLIC.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
      io.PLIC.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
      io.PLIC.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
      io.PLIC.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
      inWReady := streamW && io.PLIC.W.WREADY

      val awFire = sendAW && io.PLIC.AW.AWREADY
      val bufferedWFire = useBufferedW && io.PLIC.W.WREADY
      val streamWFire = streamW && io.in.W.WVALID && io.PLIC.W.WREADY
      val wFire = bufferedWFire || streamWFire
      val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

      when(awFire) {
        downstreamAWDone := true.B
      }
      when(bufferedWFire) {
        wValidReg := false.B
      }
      when(wLastFire) {
        downstreamWDone := true.B
      }

      when((downstreamAWDone || awFire) && (downstreamWDone || wLastFire)) {
        writeState := stateWriteResponse
      }
    }.otherwise {
      writeState := Mux(
        wValidReg && wLastReg,
        stateWriteDECERR,
        stateWriteDrain
      )
    }
  }.elsewhen(writeState === stateWriteDrain) {
    // 地址无效或本地PLIC不支持burst：首beat已在收集级被接受，
    // 继续吞掉剩余beat，避免上游停在W通道而永远等不到错误响应。
    inWReady := true.B
    when(io.in.W.WVALID && io.in.W.WLAST) {
      writeState := stateWriteDECERR
    }
  }.elsewhen(writeState === stateWriteResponse) {
    // B响应回来时没有地址，所以要根据之前保存的writeTargetReg选择下游
    when(writeTargetReg === targetSoCBus) {
      io.in.B.BID := io.SoCBus.B.BID
      io.in.B.BVALID := io.SoCBus.B.BVALID
      io.in.B.BRESP := io.SoCBus.B.BRESP
      // B响应没有地址，ID必须与本级发出的AWID一致；错配说明从设备违规，
      // 提前在交叉开关处报错，避免把故障点误定位到上一级仲裁器。
      when(io.SoCBus.B.BVALID) {
        assert(
          io.SoCBus.B.BID === awIDReg,
          "SoCBus returned a write response with an unknown ID"
        )
      }

      when(io.SoCBus.B.BVALID && io.in.B.BREADY) {
        awValidReg := false.B
        wValidReg := false.B
        downstreamAWDone := false.B
        downstreamWDone := false.B
        writeState := stateWriteIdle
      }
    }.elsewhen(writeTargetReg === targetCLINT) {
      io.in.B.BID := io.CLINT.B.BID
      io.in.B.BVALID := io.CLINT.B.BVALID
      io.in.B.BRESP := io.CLINT.B.BRESP
      when(io.CLINT.B.BVALID) {
        assert(
          io.CLINT.B.BID === awIDReg,
          "CLINT returned a write response with an unknown ID"
        )
      }

      when(io.CLINT.B.BVALID && io.in.B.BREADY) {
        awValidReg := false.B
        wValidReg := false.B
        downstreamAWDone := false.B
        downstreamWDone := false.B
        writeState := stateWriteIdle
      }
    }
    // PLIC 的 B 在状态机外的 plicWriteResponse 中转发；无效目标由下方
    // targetInvalid 防御分支转 DECERR。这里不能再写 otherwise->DECERR，
    // 否则会在 PLIC 的 B 尚未返回时把合法 PLIC 事务提前判错。
  }.elsewhen(writeState === stateWriteDECERR) {
    // 地址空洞和不支持的burst在CPU内部完成，不访问任何下游设备。
    io.in.B.BID := awIDReg
    io.in.B.BVALID := true.B
    io.in.B.BRESP := DECERR

    when(io.in.B.BREADY) {
      awValidReg := false.B
      wValidReg := false.B
      downstreamAWDone := false.B
      downstreamWDone := false.B
      writeState := stateWriteIdle
    }
  }

  // PLIC 的 B 响应单独放在写状态机之外。把它写在 stateWriteResponse 的
  // 最后一个 elsewhen 分支里会触发 firtool 生成恒假的 BREADY（该分支的
  // 条件被同时并入取反项与正向项），表现为 PLIC 写永远等不到 B 握手。
  // 这里用简单的“响应态 + 目标 + 下游有效”组合条件，避免该代码生成问题。
  val plicWriteResponse =
    writeState === stateWriteResponse && writeTargetReg === targetPLIC
  when(plicWriteResponse) {
    io.in.B.BID := io.PLIC.B.BID
    io.in.B.BVALID := io.PLIC.B.BVALID
    io.in.B.BRESP := io.PLIC.B.BRESP
    when(io.PLIC.B.BVALID) {
      assert(
        io.PLIC.B.BID === awIDReg,
        "PLIC returned a write response with an unknown ID"
      )
    }
  }
  when(plicWriteResponse && io.PLIC.B.BVALID && io.in.B.BREADY) {
    awValidReg := false.B
    wValidReg := false.B
    downstreamAWDone := false.B
    downstreamWDone := false.B
    writeState := stateWriteIdle
  }
  // 防御：无效目标本应在进入转发前走 DECERR，不能停在 Response 等 B。
  when(inWriteResponse && writeTargetReg === targetInvalid) {
    writeState := stateWriteDECERR
  }

  // 读通道
  // 每个AXI ID独占一个槽位，直到其RLAST被上游消费。仲裁器为IFU/LSU/DMA
  // 分别使用ID 0/1/2，而包含16项的表让此交叉开关能明确定义每个可表示ID的行为。
  val incomingReadID = io.in.AR.ARID
  inARReady := readSlotState(incomingReadID) === readSlotFree

  when(inARFire) {
    val target = decode(
      io.in.AR.ARADDR,
      io.in.AR.ARLEN,
      io.in.AR.ARSIZE,
      io.in.AR.ARBURST,
      isWrite = false
    )
    readTargetReg(incomingReadID) := target
    arAddressReg(incomingReadID) := io.in.AR.ARADDR
    arLenReg(incomingReadID) := io.in.AR.ARLEN
    arSizeReg(incomingReadID) := io.in.AR.ARSIZE
    arBurstReg(incomingReadID) := io.in.AR.ARBURST
    arProtReg(incomingReadID) := io.in.AR.ARPROT
    arCacheReg(incomingReadID) := io.in.AR.ARCACHE
    arQosReg(incomingReadID) := io.in.AR.ARQOS
    readDECERRBeatReg(incomingReadID) := 0.U
    readSlotState(incomingReadID) := Mux(
      target === targetInvalid,
      readSlotDECERR,
      readSlotRequest
    )
  }

  // SoCBus与CLINT拥有独立的AR通道，因此可以在同一周期分别向两个目标各发射
  // 一项等待请求。共享目标的请求每周期发送一项，并且可以在任一R响应到来前
  // 都完成握手。
  val socRequestMask = VecInit((0 until readSlotCount).map { id =>
    readSlotState(id) === readSlotRequest &&
    readTargetReg(id) === targetSoCBus
  }).asUInt
  val clintRequestMask = VecInit((0 until readSlotCount).map { id =>
    readSlotState(id) === readSlotRequest &&
    readTargetReg(id) === targetCLINT
  }).asUInt
  val plicRequestMask = VecInit((0 until readSlotCount).map { id =>
    readSlotState(id) === readSlotRequest &&
    readTargetReg(id) === targetPLIC
  }).asUInt
  val socARHeld = RegInit(false.B)
  val socARHeldID = RegInit(0.U(IDWidth.W))
  val clintARHeld = RegInit(false.B)
  val clintARHeldID = RegInit(0.U(IDWidth.W))
  val plicARHeld = RegInit(false.B)
  val plicARHeldID = RegInit(0.U(IDWidth.W))
  val socRequestCandidateID = PriorityEncoder(socRequestMask)
  val clintRequestCandidateID = PriorityEncoder(clintRequestMask)
  val plicRequestCandidateID = PriorityEncoder(plicRequestMask)
  val socRequestValid = socARHeld || socRequestMask.orR
  val clintRequestValid = clintARHeld || clintRequestMask.orR
  val plicRequestValid = plicARHeld || plicRequestMask.orR
  val socRequestID =
    Mux(socARHeld, socARHeldID, socRequestCandidateID)
  val clintRequestID =
    Mux(clintARHeld, clintARHeldID, clintRequestCandidateID)
  val plicRequestID =
    Mux(plicARHeld, plicARHeldID, plicRequestCandidateID)
  val socRequestFire = socRequestValid && io.SoCBus.AR.ARREADY
  val clintRequestFire = clintRequestValid && io.CLINT.AR.ARREADY
  val plicRequestFire = plicRequestValid && io.PLIC.AR.ARREADY

  when(socRequestValid) {
    io.SoCBus.AR.ARVALID := true.B
    io.SoCBus.AR.ARID := socRequestID
    io.SoCBus.AR.ARADDR := arAddressReg(socRequestID)
    io.SoCBus.AR.ARLEN := arLenReg(socRequestID)
    io.SoCBus.AR.ARSIZE := arSizeReg(socRequestID)
    io.SoCBus.AR.ARBURST := arBurstReg(socRequestID)
    io.SoCBus.AR.ARPROT := arProtReg(socRequestID)
    io.SoCBus.AR.ARCACHE := arCacheReg(socRequestID)
    io.SoCBus.AR.ARQOS := arQosReg(socRequestID)
    when(socRequestFire) {
      readSlotState(socRequestID) := readSlotResponse
    }
  }
  when(clintRequestValid) {
    io.CLINT.AR.ARVALID := true.B
    io.CLINT.AR.ARID := clintRequestID
    io.CLINT.AR.ARADDR := arAddressReg(clintRequestID)
    io.CLINT.AR.ARLEN := arLenReg(clintRequestID)
    io.CLINT.AR.ARSIZE := arSizeReg(clintRequestID)
    io.CLINT.AR.ARBURST := arBurstReg(clintRequestID)
    io.CLINT.AR.ARPROT := arProtReg(clintRequestID)
    io.CLINT.AR.ARCACHE := arCacheReg(clintRequestID)
    io.CLINT.AR.ARQOS := arQosReg(clintRequestID)
    when(clintRequestFire) {
      readSlotState(clintRequestID) := readSlotResponse
    }
  }
  when(plicRequestValid) {
    io.PLIC.AR.ARVALID := true.B
    io.PLIC.AR.ARID := plicRequestID
    io.PLIC.AR.ARADDR := arAddressReg(plicRequestID)
    io.PLIC.AR.ARLEN := arLenReg(plicRequestID)
    io.PLIC.AR.ARSIZE := arSizeReg(plicRequestID)
    io.PLIC.AR.ARBURST := arBurstReg(plicRequestID)
    io.PLIC.AR.ARPROT := arProtReg(plicRequestID)
    io.PLIC.AR.ARCACHE := arCacheReg(plicRequestID)
    io.PLIC.AR.ARQOS := arQosReg(plicRequestID)
    when(plicRequestFire) {
      readSlotState(plicRequestID) := readSlotResponse
    }
  }
  when(!socARHeld && socRequestValid && !io.SoCBus.AR.ARREADY) {
    socARHeld := true.B
    socARHeldID := socRequestID
  }.elsewhen(socRequestFire) {
    socARHeld := false.B
  }
  when(!clintARHeld && clintRequestValid && !io.CLINT.AR.ARREADY) {
    clintARHeld := true.B
    clintARHeldID := clintRequestID
  }.elsewhen(clintRequestFire) {
    clintARHeld := false.B
  }
  when(!plicARHeld && plicRequestValid && !io.PLIC.AR.ARREADY) {
    plicARHeld := true.B
    plicARHeldID := plicRequestID
  }.elsewhen(plicRequestFire) {
    plicARHeld := false.B
  }

  // 响应小FIFO（4拍）：既对同时到达的SoCBus/CLINT响应进行仲裁，也保证
  // 上游主设备施加反压时RID/RDATA/RRESP/RLAST保持稳定。此前是单拍缓冲，
  // 被反压的响应会独占通道、阻塞无关owner的响应（队头阻塞）；深度取4与
  // 仲裁器CPU侧队列一致，正常路径仍为1拍穿透。
  private class RPayload extends Bundle {
    val id = UInt(IDWidth.W)
    val data = UInt(DataWidth.W)
    val response = UInt(2.W)
    val last = Bool()
  }
  private val rQueue =
    Module(new Queue(new RPayload, ResponseQueueDepth, pipe = true, flow = false))
  io.in.R.RID := rQueue.io.deq.bits.id
  io.in.R.RVALID := rQueue.io.deq.valid
  io.in.R.RDATA := rQueue.io.deq.bits.data
  io.in.R.RRESP := rQueue.io.deq.bits.response
  io.in.R.RLAST := rQueue.io.deq.bits.last
  rQueue.io.deq.ready := io.in.R.RREADY

  rQueue.io.enq.valid := false.B
  rQueue.io.enq.bits.id := 0.U
  rQueue.io.enq.bits.data := 0.U
  rQueue.io.enq.bits.response := OKAY
  rQueue.io.enq.bits.last := false.B

  val rBufferPop = rQueue.io.deq.fire
  val rBufferAvailable = rQueue.io.enq.ready || rBufferPop
  val socResponseExpected =
    (readSlotState(io.SoCBus.R.RID) === readSlotResponse &&
      readTargetReg(io.SoCBus.R.RID) === targetSoCBus) ||
      (socRequestFire && socRequestID === io.SoCBus.R.RID)
  val clintResponseExpected =
    (readSlotState(io.CLINT.R.RID) === readSlotResponse &&
      readTargetReg(io.CLINT.R.RID) === targetCLINT) ||
      (clintRequestFire && clintRequestID === io.CLINT.R.RID)
  val plicResponseExpected =
    (readSlotState(io.PLIC.R.RID) === readSlotResponse &&
      readTargetReg(io.PLIC.R.RID) === targetPLIC) ||
      (plicRequestFire && plicRequestID === io.PLIC.R.RID)
  val socResponseValid = io.SoCBus.R.RVALID && socResponseExpected
  val clintResponseValid = io.CLINT.R.RVALID && clintResponseExpected
  val plicResponseValid = io.PLIC.R.RVALID && plicResponseExpected

  val localResponseMask = VecInit((0 until readSlotCount).map { id =>
    readSlotState(id) === readSlotDECERR
  }).asUInt
  val localResponseValid = localResponseMask.orR
  val localResponseID = PriorityEncoder(localResponseMask)
  val localResponseLast =
    readDECERRBeatReg(localResponseID) === arLenReg(localResponseID)

  // 因为AXI突发传输长度有限，所以固定源优先级是安全的。仲裁失败的源会看到
  // RREADY为低，并且必须保持其有效载荷。
  val selectSoC = socResponseValid
  val selectCLINT = !selectSoC && clintResponseValid
  val selectPLIC = !selectSoC && !selectCLINT && plicResponseValid
  val selectLocal = !selectSoC && !selectCLINT && !selectPLIC && localResponseValid
  io.SoCBus.R.RREADY := rBufferAvailable && selectSoC
  io.CLINT.R.RREADY := rBufferAvailable && selectCLINT
  io.PLIC.R.RREADY := rBufferAvailable && selectPLIC
  val captureSoC = io.SoCBus.R.RVALID && io.SoCBus.R.RREADY
  val captureCLINT = io.CLINT.R.RVALID && io.CLINT.R.RREADY
  val capturePLIC = io.PLIC.R.RVALID && io.PLIC.R.RREADY
  val captureLocal = rBufferAvailable && selectLocal

  when(captureSoC) {
    rQueue.io.enq.valid := true.B
    rQueue.io.enq.bits.id := io.SoCBus.R.RID
    rQueue.io.enq.bits.data := io.SoCBus.R.RDATA
    rQueue.io.enq.bits.response := io.SoCBus.R.RRESP
    rQueue.io.enq.bits.last := io.SoCBus.R.RLAST
  }.elsewhen(captureCLINT) {
    rQueue.io.enq.valid := true.B
    rQueue.io.enq.bits.id := io.CLINT.R.RID
    rQueue.io.enq.bits.data := io.CLINT.R.RDATA
    rQueue.io.enq.bits.response := io.CLINT.R.RRESP
    rQueue.io.enq.bits.last := io.CLINT.R.RLAST
  }.elsewhen(capturePLIC) {
    rQueue.io.enq.valid := true.B
    rQueue.io.enq.bits.id := io.PLIC.R.RID
    rQueue.io.enq.bits.data := io.PLIC.R.RDATA
    rQueue.io.enq.bits.response := io.PLIC.R.RRESP
    rQueue.io.enq.bits.last := io.PLIC.R.RLAST
  }.elsewhen(captureLocal) {
    rQueue.io.enq.valid := true.B
    rQueue.io.enq.bits.id := localResponseID
    rQueue.io.enq.bits.data := 0.U
    rQueue.io.enq.bits.response := DECERR
    rQueue.io.enq.bits.last := localResponseLast
    when(localResponseLast) {
      readSlotState(localResponseID) := readSlotLocalLast
    }.otherwise {
      readDECERRBeatReg(localResponseID) :=
        readDECERRBeatReg(localResponseID) + 1.U
    }
  }

  // 本地DECERR状态在数据拍被捕获进FIFO时推进，而不是在该拍随后被消费时
  // 推进。如果SoC/CLINT响应在本地数据拍出队的周期替换它，再次推进旧槽位
  // 会跳过一个错误响应拍，还可能跳过携带RLAST的ARLEN对应数据拍。

  when(rBufferPop && rQueue.io.deq.bits.last) {
    readSlotState(rQueue.io.deq.bits.id) := readSlotFree
  }

  when(io.SoCBus.R.RVALID) {
    assert(socResponseExpected, "SoCBus returned an inactive or misrouted RID")
  }
  when(io.CLINT.R.RVALID) {
    assert(clintResponseExpected, "CLINT returned an inactive or misrouted RID")
  }
  when(io.PLIC.R.RVALID) {
    assert(plicResponseExpected, "PLIC returned an inactive or misrouted RID")
  }
}
