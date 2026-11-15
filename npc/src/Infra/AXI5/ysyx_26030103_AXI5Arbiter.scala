package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_PMAOptions
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

// IFU、LSU与入站DMA端口的AXI仲裁器：读写相互独立，LSU写等待B响应时
// 取指/load仍可在途；三路读各用独立下游ID（DMA统一用ID 2，返回CPU
// slave端口时恢复上游原ID）。
class ysyx_26030103_AXI5Arbiter(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth,
    // DMA_COHERENCE=hardware_snoop：DMA 写 B 先不交给 CPU slave，而是把
    // 本次 burst 覆盖的字节范围送给 ICache/DCache 做行失效，等到
    // dmaSnoopDone 后才释放 B。关闭时保持原有“B 到达即释放”的时序。
    HardwareSnoop: Boolean = false,
    DMAWindowBase: BigInt =
      BigInt(ysyx_26030103_PMAOptions.Default.chipLinkMemBase),
    DMAWindowSize: BigInt =
      BigInt(ysyx_26030103_PMAOptions.Default.chipLinkMemSize),
    GrantTimeout: Int = ysyx_26030103_KconfigDefaults.ArbiterGrantTimeout,
    CpuResponseQueueDepth: Int =
      ysyx_26030103_KconfigDefaults.AXIArbCpuResponseQueueDepth,
    DMAResponseQueueDepth: Int =
      ysyx_26030103_KconfigDefaults.AXIArbDMAResponseQueueDepth
) extends Module {
  // 固定 ID 分配（ICache=0/LSU=1/DMA=2/PTW=3..15）按 4 位 ID 设计；
  // 地址/数据宽度可参数化，但 ID 宽度暂不支持其它值。
  require(IDWidth == 4, s"AXI5Arbiter IdWidth 只支持 4，收到 $IDWidth")
  require(CpuResponseQueueDepth >= 2 && CpuResponseQueueDepth <= 16,
    s"AXI5Arbiter CpuRespQueueDepth 非法: $CpuResponseQueueDepth")
  require(DMAResponseQueueDepth >= 16 && DMAResponseQueueDepth <= 256,
    s"AXI5Arbiter DmaRespQueueDepth 非法: $DMAResponseQueueDepth")
  val io = IO(new Bundle {
    val ifu = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    val lsu = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    val dma = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    // CPU slave口带有AXI exclusive属性，而内部精简AXI bundle没有LOCK字段。
    // 这两个旁带输入来自 slave-side WidthBridge 在外部地址握手拍锁存的值，
    // 不是外部实时引脚；任何exclusive DMA请求都必须在本地以DECERR终止，
    // 绝不能静默降级成普通下游访问。
    val dmaARLock = Input(Bool())
    val dmaAWLock = Input(Bool())
    // LSU 正在执行 LR/SC/AMO 的读改写事务。期间暂停新的 DMA 读写，
    // 保证原子事务内部不会被外部写插入；busQuiet 让 LSU 在发起原子
    // 事务前等待已在途的 DMA 排空。
    val atomicBusy = Input(Bool())
    val busQuiet = Output(Bool())
    val memory = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
    // 匹配的DMA写B响应首次出现，表示下游写已终止。顶层用这个单拍事件
    // 使写直达DCache失效；后续B反压不重复阻塞CPU load。错误响应也必须
    // 失效，因为SLVERR/DECERR并不保证从设备没有产生部分副作用。
    val DMAWriteInvalidate = Output(Bool())
    // hardware_snoop 的地址级失效请求。B 到达后 valid 保持到 done，
    // [start, end] 是本次 burst 实际可能写入的字节范围（含端点）。
    // 缓存按各自 line 大小把范围展开成所有重叠行。
    val dmaSnoopValid = Output(Bool())
    val dmaSnoopStart = Output(UInt(AddressWidth.W))
    val dmaSnoopEnd = Output(UInt(AddressWidth.W))
    val dmaSnoopDone = Input(Bool())
  })

  // 与 ysyx_26030103_AXIReadId 保持一致：0=ICache，1=LSU，2=DMA，
  // 3..15=PTW 页表读。IFU/LSU 端口保留各自的局部 ID，仲裁器只把 LSU
  // 局部 ID 0 映射到下游 1，其余 PTW ID 原样透传。
  private val ifuReadID = ysyx_26030103_AXIReadID.IFU.U(4.W)
  private val lsuReadID = ysyx_26030103_AXIReadID.LSU.U(4.W)
  private val dmaReadID = ysyx_26030103_AXIReadID.DMA.U(4.W)
  private val ptwReadBaseID = ysyx_26030103_AXIReadID.PTWBase.U(4.W)
  private val ptwReadLastID = ysyx_26030103_AXIReadID.PTWLast.U(4.W)
  private val dmaWriteID = 2.U(4.W)
  private val grantIFU = 0.U(2.W)
  private val grantLSU = 1.U(2.W)
  private val grantDMA = 2.U(2.W)
  private val DECERR = 3.U(2.W)

  private class ReadResponsePayload extends Bundle {
    val id = UInt(IDWidth.W)
    val data = UInt(DataWidth.W)
    val response = UInt(2.W)
    val last = Bool()
  }

  /** CPU slave只代理ChipLink MEM窗口。使用 AddressWidth+1 位末字节计算
    * 同时拒绝越过 4GiB 边界、超出数据总线拍宽以及不支持的 burst 类型。
    * 64 位内部地址路径下，DMA 端口也可能携带高位；任何超出 0xc0000000
    * ..0xffffffff 的请求都在本地 DECERR，绝不截断后转发。
    */
  private def dmaRequestPermitted(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt
  ): Bool = {
    val extendedWidth = AddressWidth + 1
    val extendedAddress = Cat(0.U(1.W), address)
    val lastByte =
      ysyx_26030103_AXI5Burst.lastByte(address, len, size, burst)
    val supported = size <= 2.U && (burst === 0.U || burst === 1.U)
    supported && extendedAddress >= DMAWindowBase.U(extendedWidth.W) &&
    lastByte < (DMAWindowBase + DMAWindowSize).U(extendedWidth.W)
  }

  io.ifu.AW.AWREADY := false.B
  io.ifu.W.WREADY := false.B
  io.ifu.B.BID := 0.U
  io.ifu.B.BRESP := 0.U
  io.ifu.B.BVALID := false.B
  io.ifu.AR.ARREADY := false.B
  io.ifu.R.RID := 0.U
  io.ifu.R.RDATA := 0.U
  io.ifu.R.RRESP := 0.U
  io.ifu.R.RLAST := false.B
  io.ifu.R.RVALID := false.B

  io.lsu.AW.AWREADY := false.B
  io.lsu.W.WREADY := false.B
  io.lsu.B.BID := 0.U
  io.lsu.B.BRESP := 0.U
  io.lsu.B.BVALID := false.B
  io.lsu.AR.ARREADY := false.B
  io.lsu.R.RID := 0.U
  io.lsu.R.RDATA := 0.U
  io.lsu.R.RRESP := 0.U
  io.lsu.R.RLAST := false.B
  io.lsu.R.RVALID := false.B

  io.dma.AW.AWREADY := false.B
  io.dma.W.WREADY := false.B
  io.dma.B.BID := 0.U
  io.dma.B.BRESP := 0.U
  io.dma.B.BVALID := false.B
  io.dma.AR.ARREADY := false.B
  io.dma.R.RID := 0.U
  io.dma.R.RDATA := 0.U
  io.dma.R.RRESP := 0.U
  io.dma.R.RLAST := false.B
  io.dma.R.RVALID := false.B
  io.DMAWriteInvalidate := false.B
  io.dmaSnoopValid := false.B
  io.dmaSnoopStart := 0.U
  io.dmaSnoopEnd := 0.U

  io.memory.AW.AWVALID := false.B
  io.memory.AW.AWID := 0.U
  io.memory.AW.AWADDR := 0.U
  io.memory.AW.AWLEN := 0.U
  io.memory.AW.AWSIZE := 2.U
  io.memory.AW.AWBURST := 0.U
  io.memory.AW.AWPROT := 0.U
  io.memory.AW.AWCACHE := 0.U
  io.memory.AW.AWQOS := 0.U
  io.memory.W.WVALID := false.B
  io.memory.W.WDATA := 0.U
  io.memory.W.WSTRB := 0.U
  io.memory.W.WLAST := false.B
  io.memory.B.BREADY := false.B
  io.memory.AR.ARVALID := false.B
  io.memory.AR.ARID := 0.U
  io.memory.AR.ARADDR := 0.U
  io.memory.AR.ARLEN := 0.U
  io.memory.AR.ARSIZE := 2.U
  io.memory.AR.ARBURST := 0.U
  io.memory.AR.ARPROT := 0.U
  io.memory.AR.ARCACHE := 0.U
  io.memory.AR.ARQOS := 0.U

  // 读地址仲裁和基于ID的响应路由。
  //
  // IFU/LSU 端口不再限制“整个端口只有一笔在途”，而是按下游 AXI ID 逐槽
  // 记录。ICache 固定使用 IFU ID 0；PTW 适配器使用 3..15，因此同一个 IFU
  // 端口可以同时容纳 ICache burst 和多笔页表单拍读。LSU 端口在普通路径
  // 只有局部 ID 0，在 reuse_dcache 路径则同时承载 DCache(0) 与 PTW(3..15)。
  val ifuReadBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val lsuReadBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val ifuReadIngressDone = RegInit(VecInit(Seq.fill(16)(false.B)))
  val lsuReadIngressDone = RegInit(VecInit(Seq.fill(16)(false.B)))
  val dmaReadOutstanding = RegInit(false.B)
  val dmaReadIngressDone = RegInit(false.B)
  val dmaReadOriginalID = RegInit(0.U(4.W))
  val dmaReadLocalError = RegInit(false.B)
  val dmaReadErrorLen = RegInit(0.U(8.W))
  val dmaReadErrorBeat = RegInit(0.U(8.W))
  // 初始值设为DMA，使复位后的首次三方竞争从IFU开始。
  val lastReadGrant = RegInit(grantDMA)
  val readGrantHeld = RegInit(false.B)
  val heldReadGrant = RegInit(grantIFU)

  // IFU 局部 ID 只允许 0（ICache）或 3..15（PTW）；LSU 另外允许局部 0
  // 映射到下游 1。1/2 是 LSU/DMA 的固定下游 ID，不能作为 PTW 槽位。
  val ifuLocalID = io.ifu.AR.ARID
  val lsuLocalID = io.lsu.AR.ARID
  val ifuIDFormValid = ifuLocalID === ifuReadID ||
    (ifuLocalID >= ptwReadBaseID && ifuLocalID <= ptwReadLastID)
  val lsuIDFormValid = lsuLocalID === 0.U ||
    (lsuLocalID >= ptwReadBaseID && lsuLocalID <= ptwReadLastID)
  val ifuDownstreamID = ifuLocalID
  val lsuDownstreamID = Mux(lsuLocalID === 0.U, lsuReadID, lsuLocalID)
  val ifuReadRequest = io.ifu.AR.ARVALID && ifuIDFormValid &&
    !ifuReadBusy(ifuDownstreamID) && !lsuReadBusy(ifuDownstreamID) &&
    ifuDownstreamID =/= dmaReadID
  val lsuReadRequest = io.lsu.AR.ARVALID && lsuIDFormValid &&
    !lsuReadBusy(lsuDownstreamID) && !ifuReadBusy(lsuDownstreamID) &&
    lsuDownstreamID =/= dmaReadID
  // 原子事务期间不接受新的 DMA 读；已经在途的 DMA 读由 busQuiet 等待排空。
  val dmaReadRequest =
    io.dma.AR.ARVALID && !dmaReadOutstanding && !io.atomicBusy
  when(io.ifu.AR.ARVALID) {
    assert(ifuIDFormValid,
      "IFU read ID must be 0 or in the PTW 3..15 range")
  }
  when(io.lsu.AR.ARVALID) {
    assert(lsuIDFormValid,
      "LSU read ID must be local 0 or in the PTW 3..15 range")
  }
  // 三方round-robin：从上次授权的下一方开始找有效请求。
  val chooseNewIFU = WireDefault(false.B)
  val chooseNewLSU = WireDefault(false.B)
  val chooseNewDMA = WireDefault(false.B)
  when(lastReadGrant === grantIFU) {
    when(lsuReadRequest) { chooseNewLSU := true.B }
      .elsewhen(dmaReadRequest) { chooseNewDMA := true.B }
      .elsewhen(ifuReadRequest) { chooseNewIFU := true.B }
  }.elsewhen(lastReadGrant === grantLSU) {
    when(dmaReadRequest) { chooseNewDMA := true.B }
      .elsewhen(ifuReadRequest) { chooseNewIFU := true.B }
      .elsewhen(lsuReadRequest) { chooseNewLSU := true.B }
  }.otherwise {
    when(ifuReadRequest) { chooseNewIFU := true.B }
      .elsewhen(lsuReadRequest) { chooseNewLSU := true.B }
      .elsewhen(dmaReadRequest) { chooseNewDMA := true.B }
  }
  // ARVALID拉高但READY未拉高后，AXI要求AR的每个有效载荷字段保持稳定。
  // 因此，在受阻请求完成握手前，新到达的竞争主设备不能改变授权结果。
  val chooseLSU = Mux(readGrantHeld, heldReadGrant === grantLSU, chooseNewLSU)
  val chooseIFU = Mux(readGrantHeld, heldReadGrant === grantIFU, chooseNewIFU)
  val chooseDMA = Mux(readGrantHeld, heldReadGrant === grantDMA, chooseNewDMA)
  val dmaARPermitted = !io.dmaARLock && dmaRequestPermitted(
    io.dma.AR.ARADDR,
    io.dma.AR.ARLEN,
    io.dma.AR.ARSIZE,
    io.dma.AR.ARBURST
  )

  when(chooseLSU) {
    io.memory.AR.ARVALID := io.lsu.AR.ARVALID
    io.memory.AR.ARID := lsuDownstreamID
    io.memory.AR.ARADDR := io.lsu.AR.ARADDR
    io.memory.AR.ARLEN := io.lsu.AR.ARLEN
    io.memory.AR.ARSIZE := io.lsu.AR.ARSIZE
    io.memory.AR.ARBURST := io.lsu.AR.ARBURST
    io.memory.AR.ARPROT := io.lsu.AR.ARPROT
    io.memory.AR.ARCACHE := io.lsu.AR.ARCACHE
    io.memory.AR.ARQOS := io.lsu.AR.ARQOS
    io.lsu.AR.ARREADY := io.memory.AR.ARREADY
  }.elsewhen(chooseDMA) {
    when(dmaARPermitted) {
      io.memory.AR.ARVALID := io.dma.AR.ARVALID
      io.memory.AR.ARID := dmaReadID
      io.memory.AR.ARADDR := io.dma.AR.ARADDR
      io.memory.AR.ARLEN := io.dma.AR.ARLEN
      io.memory.AR.ARSIZE := io.dma.AR.ARSIZE
      io.memory.AR.ARBURST := io.dma.AR.ARBURST
      io.memory.AR.ARPROT := io.dma.AR.ARPROT
      io.memory.AR.ARCACHE := io.dma.AR.ARCACHE
      io.memory.AR.ARQOS := io.dma.AR.ARQOS
      io.dma.AR.ARREADY := io.memory.AR.ARREADY
    }.otherwise {
      // 窗口外请求在本地接受并生成等长DECERR burst，绝不进入CPU Xbar。
      io.dma.AR.ARREADY := true.B
    }
  }.elsewhen(chooseIFU) {
    io.memory.AR.ARVALID := io.ifu.AR.ARVALID
    io.memory.AR.ARID := ifuDownstreamID
    io.memory.AR.ARADDR := io.ifu.AR.ARADDR
    io.memory.AR.ARLEN := io.ifu.AR.ARLEN
    io.memory.AR.ARSIZE := io.ifu.AR.ARSIZE
    io.memory.AR.ARBURST := io.ifu.AR.ARBURST
    io.memory.AR.ARPROT := io.ifu.AR.ARPROT
    io.memory.AR.ARCACHE := io.ifu.AR.ARCACHE
    io.memory.AR.ARQOS := io.ifu.AR.ARQOS
    io.ifu.AR.ARREADY := io.memory.AR.ARREADY
  }

  val readAddressFire = io.memory.AR.ARVALID && io.memory.AR.ARREADY
  val dmaLocalARFire = chooseDMA && io.dma.AR.ARVALID &&
    io.dma.AR.ARREADY && !dmaARPermitted
  val readAddressAccepted = readAddressFire || dmaLocalARFire
  when(!readGrantHeld && io.memory.AR.ARVALID && !io.memory.AR.ARREADY) {
    readGrantHeld := true.B
    heldReadGrant := Mux(chooseLSU, grantLSU, Mux(chooseDMA, grantDMA, grantIFU))
  }.elsewhen(readGrantHeld && readAddressAccepted) {
    readGrantHeld := false.B
  }
  // AXI 要求 VALID 一旦提出必须保持到 READY：被授权方若在地址握手前撤销
  // ARVALID，授权会永久锁死并静默阻塞其余两方。仿真断言把它变成显式错误；
  // 断言在综合后消失，因此再加一个可综合兜底：持续 1024 拍仍看不到
  // ARVALID 就释放授权（此时本级 ARVALID 已为低，撤销不违反稳定性），
  // 让其余主设备继续推进而不是整机挂死。
  val grantedARValid = MuxCase(
    false.B,
    Seq(
      (heldReadGrant === grantIFU) -> io.ifu.AR.ARVALID,
      (heldReadGrant === grantLSU) -> io.lsu.AR.ARVALID,
      (heldReadGrant === grantDMA) -> io.dma.AR.ARVALID
    )
  )
  when(readGrantHeld) {
    assert(
      grantedARValid,
      "granted master dropped ARVALID before its address handshake"
    )
  }
  val grantStallTimer = RegInit(0.U(10.W))
  when(!readGrantHeld || grantedARValid) {
    grantStallTimer := 0.U
  }.otherwise {
    grantStallTimer := grantStallTimer + 1.U
  }
  when(readGrantHeld && grantStallTimer === GrantTimeout.U) {
    readGrantHeld := false.B
  }
  when(readAddressFire && chooseLSU) {
    lsuReadBusy(lsuDownstreamID) := true.B
    lsuReadIngressDone(lsuDownstreamID) := false.B
    lastReadGrant := grantLSU
  }
  when(readAddressFire && chooseIFU) {
    ifuReadBusy(ifuDownstreamID) := true.B
    ifuReadIngressDone(ifuDownstreamID) := false.B
    lastReadGrant := grantIFU
  }
  when((readAddressFire && chooseDMA) || dmaLocalARFire) {
    dmaReadOutstanding := true.B
    dmaReadOriginalID := io.dma.AR.ARID
    dmaReadLocalError := dmaLocalARFire
    dmaReadIngressDone := false.B
    when(dmaLocalARFire) {
      dmaReadErrorLen := io.dma.AR.ARLEN
      dmaReadErrorBeat := 0.U
    }
    lastReadGrant := grantDMA
  }

  // 每个owner使用独立响应队列。DMA队列能容纳AXI4允许的一整笔最大
  // 256-beat burst；因此即使CPU slave端永久RREADY=0，该笔DMA响应也
  // 不会占住共享memory.R并阻止IFU/LSU响应。CPU队列按 ID 保存多笔
  // 在途响应，深度覆盖 4-bit ID 的全部槽位，避免 PTW 多 outstanding
  // 时响应在队列口互相阻塞。
  private val ifuRQueue = Module(
    new Queue(new ReadResponsePayload, CpuResponseQueueDepth,
      pipe = true, flow = false)
  )
  private val lsuRQueue = Module(
    new Queue(new ReadResponsePayload, CpuResponseQueueDepth,
      pipe = true, flow = false)
  )
  private val dmaRQueue = Module(
    new Queue(new ReadResponsePayload, DMAResponseQueueDepth,
      pipe = true, flow = false)
  )
  for (queue <- Seq(ifuRQueue, lsuRQueue, dmaRQueue)) {
    queue.io.enq.valid := false.B
    queue.io.enq.bits.id := io.memory.R.RID
    queue.io.enq.bits.data := io.memory.R.RDATA
    queue.io.enq.bits.response := io.memory.R.RRESP
    queue.io.enq.bits.last := io.memory.R.RLAST
  }

  // 有些零延迟从机会在AR握手同拍给出第一拍R。把本拍新接受的 ID 也
  // 纳入 expected；Queue是非flow-through，响应真正出队时原DMA ID已经
  // 在同一上升沿锁存完成。
  val ifuARAcceptedNow = readAddressFire && chooseIFU
  val lsuARAcceptedNow = readAddressFire && chooseLSU
  val dmaARAcceptedNow = readAddressFire && chooseDMA
  val rid = io.memory.R.RID
  val incomingForIFU =
    (ifuReadBusy(rid) && !ifuReadIngressDone(rid)) ||
      (ifuARAcceptedNow && ifuDownstreamID === rid)
  val incomingForLSU =
    (lsuReadBusy(rid) && !lsuReadIngressDone(rid)) ||
      (lsuARAcceptedNow && lsuDownstreamID === rid)
  val incomingForDMA = rid === dmaReadID &&
    ((dmaReadOutstanding && !dmaReadLocalError && !dmaReadIngressDone) ||
      dmaARAcceptedNow)
  val incomingRIDExpected = incomingForIFU || incomingForLSU || incomingForDMA

  ifuRQueue.io.enq.valid := io.memory.R.RVALID && incomingForIFU
  lsuRQueue.io.enq.valid := io.memory.R.RVALID && incomingForLSU
  dmaRQueue.io.enq.valid := io.memory.R.RVALID && incomingForDMA
  io.memory.R.RREADY := MuxCase(
    false.B,
    Seq(
      incomingForIFU -> ifuRQueue.io.enq.ready,
      incomingForLSU -> lsuRQueue.io.enq.ready,
      incomingForDMA -> dmaRQueue.io.enq.ready
    )
  )

  ifuRQueue.io.deq.ready := io.ifu.R.RREADY
  io.ifu.R.RID := ifuRQueue.io.deq.bits.id
  io.ifu.R.RDATA := ifuRQueue.io.deq.bits.data
  io.ifu.R.RRESP := ifuRQueue.io.deq.bits.response
  io.ifu.R.RLAST := ifuRQueue.io.deq.bits.last
  io.ifu.R.RVALID := ifuRQueue.io.deq.valid

  lsuRQueue.io.deq.ready := io.lsu.R.RREADY
  // 下游 LSU ID 1 在端口上恢复成局部 ID 0；reuse_dcache 路径的 AXIReadMux
  // 用它区分 DCache 与 PTW 的 3..15 响应。
  io.lsu.R.RID := Mux(lsuRQueue.io.deq.bits.id === lsuReadID,
    0.U, lsuRQueue.io.deq.bits.id)
  io.lsu.R.RDATA := lsuRQueue.io.deq.bits.data
  io.lsu.R.RRESP := lsuRQueue.io.deq.bits.response
  io.lsu.R.RLAST := lsuRQueue.io.deq.bits.last
  io.lsu.R.RVALID := lsuRQueue.io.deq.valid

  dmaRQueue.io.deq.ready := io.dma.R.RREADY && !dmaReadLocalError
  when(!dmaReadLocalError) {
    io.dma.R.RID := dmaReadOriginalID
    io.dma.R.RDATA := dmaRQueue.io.deq.bits.data
    io.dma.R.RRESP := dmaRQueue.io.deq.bits.response
    io.dma.R.RLAST := dmaRQueue.io.deq.bits.last
    io.dma.R.RVALID := dmaRQueue.io.deq.valid
  }
  when(dmaReadLocalError) {
    io.dma.R.RID := dmaReadOriginalID
    io.dma.R.RDATA := 0.U
    io.dma.R.RRESP := DECERR
    io.dma.R.RLAST := dmaReadErrorBeat === dmaReadErrorLen
    io.dma.R.RVALID := true.B
  }

  val memoryRFire = io.memory.R.RVALID && io.memory.R.RREADY
  when(memoryRFire && io.memory.R.RLAST && incomingForLSU) {
    lsuReadIngressDone(rid) := true.B
  }
  when(memoryRFire && io.memory.R.RLAST && incomingForIFU) {
    ifuReadIngressDone(rid) := true.B
  }
  when(memoryRFire && io.memory.R.RLAST && incomingForDMA) {
    dmaReadIngressDone := true.B
  }
  when(lsuRQueue.io.deq.fire && lsuRQueue.io.deq.bits.last) {
    lsuReadBusy(lsuRQueue.io.deq.bits.id) := false.B
    lsuReadIngressDone(lsuRQueue.io.deq.bits.id) := false.B
  }
  when(ifuRQueue.io.deq.fire && ifuRQueue.io.deq.bits.last) {
    ifuReadBusy(ifuRQueue.io.deq.bits.id) := false.B
    ifuReadIngressDone(ifuRQueue.io.deq.bits.id) := false.B
  }
  when(dmaRQueue.io.deq.fire && dmaRQueue.io.deq.bits.last) {
    dmaReadOutstanding := false.B
    dmaReadIngressDone := false.B
  }
  when(dmaReadLocalError && io.dma.R.RREADY) {
    when(dmaReadErrorBeat === dmaReadErrorLen) {
      dmaReadLocalError := false.B
      dmaReadOutstanding := false.B
      dmaReadIngressDone := false.B
    }.otherwise {
      dmaReadErrorBeat := dmaReadErrorBeat + 1.U
    }
  }
  when(io.memory.R.RVALID) {
    assert(
      incomingRIDExpected,
      "AXI arbiter received an unknown read response ID"
    )
    assert(
      !(incomingForIFU && incomingForLSU),
      "AXI arbiter read response matched both IFU and LSU"
    )
  }

  // LSU与DMA共享写通道。由于AXI4的W通道不带ID，一旦选中来源，
  // 必须锁定到该事务的WLAST和B响应完成，不得交错两个主设备的W beat。
  val writeStates = Enum(3)
  val writeIdle = writeStates(0)
  val writeRequest = writeStates(1)
  val writeResponse = writeStates(2)
  val writeState = RegInit(writeIdle)
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeOwner = RegInit(grantLSU)
  // 初始让LSU在同时请求时先获得写通道，之后在LSU/DMA之间轮转。
  val lastWriteGrant = RegInit(grantDMA)
  val dmaWriteOriginalID = RegInit(0.U(4.W))
  val dmaWritePermitted = RegInit(false.B)
  val dmaWriteOutstanding = RegInit(false.B)
  val downstreamWriteID = RegInit(0.U(4.W))
  // DMA AW 载荷快照：B 到达时用 [addr, addr + beats*bytes - 1] 生成
  // snoop 范围，因此 B 不再需要反查已经推进的 W 通道。
  val dmaWriteAddress = RegInit(0.U(AddressWidth.W))
  val dmaWriteLen = RegInit(0.U(8.W))
  val dmaWriteSize = RegInit(0.U(3.W))
  val dmaWriteBurst = RegInit(0.U(2.W))
  val dmaSnoopPending = RegInit(false.B)
  val dmaSnoopStartReg = RegInit(0.U(AddressWidth.W))
  val dmaSnoopEndReg = RegInit(0.U(AddressWidth.W))
  // DMA专属B弹性槽。下游B一旦进入此槽，写仲裁器立即可服务LSU；
  // CPU slave的BREADY只控制该槽出队，不再反压共享memory.B。
  val dmaBValid = RegInit(false.B)
  val dmaBID = RegInit(0.U(4.W))
  val dmaBResponse = RegInit(0.U(2.W))
  // hardware_snoop 下下游 B 先进入该槽，等 Cache 失效完成后再搬到
  // dmaBValid（对外 BVALID）。软件看到的 B 因此是“失效后”的完成事件。
  val dmaBPendingID = RegInit(0.U(4.W))
  val dmaBPendingResponse = RegInit(0.U(2.W))
  io.dma.B.BVALID := dmaBValid
  io.dma.B.BID := dmaBID
  io.dma.B.BRESP := dmaBResponse
  val dmaBFire = dmaBValid && io.dma.B.BREADY
  when(dmaBFire) {
    dmaBValid := false.B
    dmaWriteOutstanding := false.B
  }
  // 一次 burst 实际覆盖的字节范围。FIXED burst 每拍都写同一组字节，
  // INCR 按 LEN+1 个 beat 展开；dmaRequestPermitted 已保证不越过 4GiB，
  // 因此 AddressWidth+1 位计算不会回绕。
  private val snoopExtendedWidth = AddressWidth + 1
  private val dmaBeatBytes = 1.U(snoopExtendedWidth.W) << dmaWriteSize
  private val dmaBeats = (dmaWriteLen +& 1.U).pad(snoopExtendedWidth)
  private val dmaBurstBytes =
    Mux(dmaWriteBurst === 0.U, dmaBeatBytes, dmaBeats * dmaBeatBytes)
  private val dmaRangeStart = Cat(0.U(1.W), dmaWriteAddress)
  private val dmaRangeEnd = dmaRangeStart + dmaBurstBytes - 1.U
  if (HardwareSnoop) {
    io.dmaSnoopValid := dmaSnoopPending
    io.dmaSnoopStart := dmaSnoopStartReg
    io.dmaSnoopEnd := dmaSnoopEndReg
    when(dmaSnoopPending && io.dmaSnoopDone) {
      assert(!dmaBValid, "DMA B slot occupied while snoop completes")
      dmaSnoopPending := false.B
      dmaBValid := true.B
      dmaBID := dmaBPendingID
      dmaBResponse := dmaBPendingResponse
    }
  }

  switch(writeState) {
    is(writeIdle) {
      awDone := false.B
      wDone := false.B
      val lsuRequest = io.lsu.AW.AWVALID || io.lsu.W.WVALID
      // 原子读改写期间不接受新的 DMA 写，避免在 AMO 的读与写之间插入
      // 外部 store；已在途的 DMA 写由 busQuiet 在原子事务开始前排空。
      val dmaRequest = !io.atomicBusy && !dmaWriteOutstanding &&
        (io.dma.AW.AWVALID || io.dma.W.WVALID)
      val chooseDMA = dmaRequest && (!lsuRequest || lastWriteGrant === grantLSU)
      when(lsuRequest || dmaRequest) {
        writeOwner := Mux(chooseDMA, grantDMA, grantLSU)
        lastWriteGrant := Mux(chooseDMA, grantDMA, grantLSU)
        writeState := writeRequest
      }
    }
    is(writeRequest) {
      val ownerDMA = writeOwner === grantDMA
      val selectedAWValid = Mux(ownerDMA, io.dma.AW.AWVALID, io.lsu.AW.AWVALID)
      val selectedWValid = Mux(ownerDMA, io.dma.W.WVALID, io.lsu.W.WVALID)
      val selectedWLast = Mux(ownerDMA, io.dma.W.WLAST, io.lsu.W.WLAST)
      val dmaAWPermitted = !io.dmaAWLock && dmaRequestPermitted(
        io.dma.AW.AWADDR,
        io.dma.AW.AWLEN,
        io.dma.AW.AWSIZE,
        io.dma.AW.AWBURST
      )

      io.memory.AW.AWVALID := !awDone && selectedAWValid &&
        (!ownerDMA || dmaAWPermitted)
      io.memory.AW.AWID := Mux(ownerDMA, dmaWriteID, io.lsu.AW.AWID)
      io.memory.AW.AWADDR := Mux(ownerDMA, io.dma.AW.AWADDR, io.lsu.AW.AWADDR)
      io.memory.AW.AWLEN := Mux(ownerDMA, io.dma.AW.AWLEN, io.lsu.AW.AWLEN)
      io.memory.AW.AWSIZE := Mux(ownerDMA, io.dma.AW.AWSIZE, io.lsu.AW.AWSIZE)
      io.memory.AW.AWBURST := Mux(ownerDMA, io.dma.AW.AWBURST, io.lsu.AW.AWBURST)
      io.memory.AW.AWPROT := Mux(ownerDMA, io.dma.AW.AWPROT, io.lsu.AW.AWPROT)
      io.memory.AW.AWCACHE :=
        Mux(ownerDMA, io.dma.AW.AWCACHE, io.lsu.AW.AWCACHE)
      io.memory.AW.AWQOS := Mux(ownerDMA, io.dma.AW.AWQOS, io.lsu.AW.AWQOS)
      io.lsu.AW.AWREADY := io.memory.AW.AWREADY && !awDone && !ownerDMA
      io.dma.AW.AWREADY := !awDone && ownerDMA &&
        Mux(dmaAWPermitted, io.memory.AW.AWREADY, true.B)
      val selectedAWReady = Mux(ownerDMA, io.dma.AW.AWREADY, io.lsu.AW.AWREADY)
      val awFire = !awDone && selectedAWValid && selectedAWReady

      // DMA的W通道在AW被分类前保持反压。这样即使W先到，也不会在得知
      // 地址合法性之前把数据泄漏到下游；合法AW确认后的各拍再直接流过。
      val dmaWReady = awDone && Mux(dmaWritePermitted, io.memory.W.WREADY, true.B)
      io.memory.W.WVALID := selectedWValid && !wDone &&
        (!ownerDMA || (awDone && dmaWritePermitted))
      io.memory.W.WDATA := Mux(ownerDMA, io.dma.W.WDATA, io.lsu.W.WDATA)
      io.memory.W.WSTRB := Mux(ownerDMA, io.dma.W.WSTRB, io.lsu.W.WSTRB)
      io.memory.W.WLAST := selectedWLast
      io.lsu.W.WREADY := io.memory.W.WREADY && !wDone && !ownerDMA
      io.dma.W.WREADY := dmaWReady && !wDone && ownerDMA
      val selectedWReady = Mux(ownerDMA, io.dma.W.WREADY, io.lsu.W.WREADY)
      val wFire = !wDone && selectedWValid && selectedWReady
      val wLastFire = wFire && selectedWLast

      when(awFire) {
        awDone := true.B
        downstreamWriteID := Mux(ownerDMA, dmaWriteID, io.lsu.AW.AWID)
        when(ownerDMA) {
          dmaWriteOriginalID := io.dma.AW.AWID
          dmaWritePermitted := dmaAWPermitted
          dmaWriteOutstanding := true.B
          dmaWriteAddress := io.dma.AW.AWADDR
          dmaWriteLen := io.dma.AW.AWLEN
          dmaWriteSize := io.dma.AW.AWSIZE
          dmaWriteBurst := io.dma.AW.AWBURST
        }
      }
      when(wLastFire) { wDone := true.B }
      when((awDone || awFire) && (wDone || wLastFire)) {
        when(ownerDMA && !dmaWritePermitted) {
          assert(!dmaBValid, "local DMA write error collided with a pending B")
          dmaBValid := true.B
          dmaBID := dmaWriteOriginalID
          dmaBResponse := DECERR
          writeState := writeIdle
        }.otherwise {
          writeState := writeResponse
        }
      }
    }
    is(writeResponse) {
      val ownerDMA = writeOwner === grantDMA
      val bidMatches = io.memory.B.BID === downstreamWriteID
      // 错误BID不属于当前事务，不能吞掉并让本仲裁器与下游
      // fabric对在途事务的认识脱节。仿真assert会立即指出违规从设备。
      io.memory.B.BREADY := bidMatches &&
        Mux(ownerDMA, !dmaBValid, io.lsu.B.BREADY)
      when(ownerDMA) {
        // DMA响应在MatchingBFire时写入独立B槽，下一拍即可释放共享写FSM。
      }.otherwise {
        io.lsu.B.BID := io.memory.B.BID
        io.lsu.B.BRESP := io.memory.B.BRESP
        io.lsu.B.BVALID := io.memory.B.BVALID && bidMatches
      }
      val matchingBFire = io.memory.B.BVALID && io.memory.B.BREADY && bidMatches
      when(io.memory.B.BVALID) {
        assert(bidMatches, "AXI arbiter received a write response with an unknown ID")
      }
      when(matchingBFire) {
        when(ownerDMA) {
          io.DMAWriteInvalidate := true.B
          if (HardwareSnoop) {
            // B 已证明下游写完成。先锁存地址级失效请求，等 ICache/DCache
            // 都确认失效后再把 B 交给 CPU slave；这保证“看到 B”时缓存里
            // 不可能还留着 DMA 之前的旧副本。
            assert(!dmaSnoopPending,
              "new DMA B arrived while previous snoop is still pending")
            dmaSnoopStartReg := dmaRangeStart(AddressWidth - 1, 0)
            dmaSnoopEndReg := dmaRangeEnd(AddressWidth - 1, 0)
            dmaSnoopPending := true.B
            dmaBPendingID := dmaWriteOriginalID
            dmaBPendingResponse := io.memory.B.BRESP
          } else {
            dmaBValid := true.B
            dmaBID := dmaWriteOriginalID
            dmaBResponse := io.memory.B.BRESP
          }
        }
        writeState := writeIdle
      }
    }
  }
  // 只有写通道空闲且没有 DMA 读写事务在途时才允许 LSU 开始原子事务。
  // 该信号与 atomicBusy 配合：先等 quiet，再拉 busy 锁住新的 DMA 请求。
  io.busQuiet := writeState === writeIdle && !dmaWriteOutstanding &&
    !dmaReadOutstanding
}
