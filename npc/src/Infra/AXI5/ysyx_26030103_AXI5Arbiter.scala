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
    // DMASnoopDone 后才释放 B。关闭时保持原有“B 到达即释放”的时序。
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
    val LSU = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    val DMA = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    // CPU slave口带有AXI exclusive属性，而内部精简AXI bundle没有LOCK字段。
    // 这两个旁带输入来自 slave-side WidthBridge 在外部地址握手拍锁存的值，
    // 不是外部实时引脚；任何exclusive DMA请求都必须在本地以DECERR终止，
    // 绝不能静默降级成普通下游访问。
    val DMAARLock = Input(Bool())
    val DMAAWLock = Input(Bool())
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
    val DMASnoopValid = Output(Bool())
    val DMASnoopStart = Output(UInt(AddressWidth.W))
    val DMASnoopEnd = Output(UInt(AddressWidth.W))
    val DMASnoopDone = Input(Bool())
  })

  // 与 ysyx_26030103_AXIReadId 保持一致：0=ICache，1=LSU，2=DMA，
  // 3..15=PTW 页表读。IFU/LSU 端口保留各自的局部 ID，仲裁器只把 LSU
  // 局部 ID 0 映射到下游 1，其余 PTW ID 原样透传。
  private val IFUReadID = ysyx_26030103_AXIReadID.IFU.U(4.W)
  private val LSUReadID = ysyx_26030103_AXIReadID.LSU.U(4.W)
  private val DMAReadID = ysyx_26030103_AXIReadID.DMA.U(4.W)
  private val PTWReadBaseID = ysyx_26030103_AXIReadID.PTWBase.U(4.W)
  private val PTWReadLastID = ysyx_26030103_AXIReadID.PTWLast.U(4.W)
  private val DMAWriteID = 2.U(4.W)
  private val GrantIFU = 0.U(2.W)
  private val GrantLSU = 1.U(2.W)
  private val GrantDMA = 2.U(2.W)
  private val DECERR = 3.U(2.W)

  private class ReadResponsePayload extends Bundle {
    val ID = UInt(IDWidth.W)
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
      length: UInt,
      size: UInt,
      burst: UInt
  ): Bool = {
    val extendedWidth = AddressWidth + 1
    val extendedAddress = Cat(0.U(1.W), address)
    val lastByte =
      ysyx_26030103_AXI5Burst.lastByte(address, length, size, burst)
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

  io.LSU.AW.AWREADY := false.B
  io.LSU.W.WREADY := false.B
  io.LSU.B.BID := 0.U
  io.LSU.B.BRESP := 0.U
  io.LSU.B.BVALID := false.B
  io.LSU.AR.ARREADY := false.B
  io.LSU.R.RID := 0.U
  io.LSU.R.RDATA := 0.U
  io.LSU.R.RRESP := 0.U
  io.LSU.R.RLAST := false.B
  io.LSU.R.RVALID := false.B

  io.DMA.AW.AWREADY := false.B
  io.DMA.W.WREADY := false.B
  io.DMA.B.BID := 0.U
  io.DMA.B.BRESP := 0.U
  io.DMA.B.BVALID := false.B
  io.DMA.AR.ARREADY := false.B
  io.DMA.R.RID := 0.U
  io.DMA.R.RDATA := 0.U
  io.DMA.R.RRESP := 0.U
  io.DMA.R.RLAST := false.B
  io.DMA.R.RVALID := false.B
  io.DMAWriteInvalidate := false.B
  io.DMASnoopValid := false.B
  io.DMASnoopStart := 0.U
  io.DMASnoopEnd := 0.U

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
  val IFUReadBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val LSUReadBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  val IFUReadIngressDone = RegInit(VecInit(Seq.fill(16)(false.B)))
  val LSUReadIngressDone = RegInit(VecInit(Seq.fill(16)(false.B)))
  val DMAReadOutstanding = RegInit(false.B)
  val DMAReadIngressDone = RegInit(false.B)
  val DMAReadOriginalID = RegInit(0.U(4.W))
  val DMAReadLocalError = RegInit(false.B)
  val DMAReadErrorLength = RegInit(0.U(8.W))
  val DMAReadErrorBeat = RegInit(0.U(8.W))
  // 初始值设为DMA，使复位后的首次三方竞争从IFU开始。
  val lastReadGrant = RegInit(GrantDMA)
  val readGrantHeld = RegInit(false.B)
  val heldReadGrant = RegInit(GrantIFU)

  // IFU 局部 ID 只允许 0（ICache）或 3..15（PTW）；LSU 另外允许局部 0
  // 映射到下游 1。1/2 是 LSU/DMA 的固定下游 ID，不能作为 PTW 槽位。
  val IFULocalID = io.ifu.AR.ARID
  val LSULocalID = io.LSU.AR.ARID
  val IFUIDFormValid = IFULocalID === IFUReadID ||
    (IFULocalID >= PTWReadBaseID && IFULocalID <= PTWReadLastID)
  val LSUIDFormValid = LSULocalID === 0.U ||
    (LSULocalID >= PTWReadBaseID && LSULocalID <= PTWReadLastID)
  val IFUDownstreamID = IFULocalID
  val LSUDownstreamID = Mux(LSULocalID === 0.U, LSUReadID, LSULocalID)
  val IFUReadRequest = io.ifu.AR.ARVALID && IFUIDFormValid &&
    !IFUReadBusy(IFUDownstreamID) && !LSUReadBusy(IFUDownstreamID) &&
    IFUDownstreamID =/= DMAReadID
  val LSUReadRequest = io.LSU.AR.ARVALID && LSUIDFormValid &&
    !LSUReadBusy(LSUDownstreamID) && !IFUReadBusy(LSUDownstreamID) &&
    LSUDownstreamID =/= DMAReadID
  // 原子事务期间不接受新的 DMA 读；已经在途的 DMA 读由 busQuiet 等待排空。
  val DMAReadRequest =
    io.DMA.AR.ARVALID && !DMAReadOutstanding && !io.atomicBusy
  when(io.ifu.AR.ARVALID) {
    assert(IFUIDFormValid,
      "IFU read ID must be 0 or in the PTW 3..15 range")
  }
  when(io.LSU.AR.ARVALID) {
    assert(LSUIDFormValid,
      "LSU read ID must be local 0 or in the PTW 3..15 range")
  }
  // 三方round-robin：从上次授权的下一方开始找有效请求。
  val ChooseNewIFU = WireDefault(false.B)
  val ChooseNewLSU = WireDefault(false.B)
  val ChooseNewDMA = WireDefault(false.B)
  when(lastReadGrant === GrantIFU) {
    when(LSUReadRequest) { ChooseNewLSU := true.B }
      .elsewhen(DMAReadRequest) { ChooseNewDMA := true.B }
      .elsewhen(IFUReadRequest) { ChooseNewIFU := true.B }
  }.elsewhen(lastReadGrant === GrantLSU) {
    when(DMAReadRequest) { ChooseNewDMA := true.B }
      .elsewhen(IFUReadRequest) { ChooseNewIFU := true.B }
      .elsewhen(LSUReadRequest) { ChooseNewLSU := true.B }
  }.otherwise {
    when(IFUReadRequest) { ChooseNewIFU := true.B }
      .elsewhen(LSUReadRequest) { ChooseNewLSU := true.B }
      .elsewhen(DMAReadRequest) { ChooseNewDMA := true.B }
  }
  // ARVALID拉高但READY未拉高后，AXI要求AR的每个有效载荷字段保持稳定。
  // 因此，在受阻请求完成握手前，新到达的竞争主设备不能改变授权结果。
  val ChooseLSU = Mux(readGrantHeld, heldReadGrant === GrantLSU, ChooseNewLSU)
  val ChooseIFU = Mux(readGrantHeld, heldReadGrant === GrantIFU, ChooseNewIFU)
  val ChooseDMA = Mux(readGrantHeld, heldReadGrant === GrantDMA, ChooseNewDMA)
  val DMAARPermitted = !io.DMAARLock && dmaRequestPermitted(
    io.DMA.AR.ARADDR,
    io.DMA.AR.ARLEN,
    io.DMA.AR.ARSIZE,
    io.DMA.AR.ARBURST
  )

  when(ChooseLSU) {
    io.memory.AR.ARVALID := io.LSU.AR.ARVALID
    io.memory.AR.ARID := LSUDownstreamID
    io.memory.AR.ARADDR := io.LSU.AR.ARADDR
    io.memory.AR.ARLEN := io.LSU.AR.ARLEN
    io.memory.AR.ARSIZE := io.LSU.AR.ARSIZE
    io.memory.AR.ARBURST := io.LSU.AR.ARBURST
    io.memory.AR.ARPROT := io.LSU.AR.ARPROT
    io.memory.AR.ARCACHE := io.LSU.AR.ARCACHE
    io.memory.AR.ARQOS := io.LSU.AR.ARQOS
    io.LSU.AR.ARREADY := io.memory.AR.ARREADY
  }.elsewhen(ChooseDMA) {
    when(DMAARPermitted) {
      io.memory.AR.ARVALID := io.DMA.AR.ARVALID
      io.memory.AR.ARID := DMAReadID
      io.memory.AR.ARADDR := io.DMA.AR.ARADDR
      io.memory.AR.ARLEN := io.DMA.AR.ARLEN
      io.memory.AR.ARSIZE := io.DMA.AR.ARSIZE
      io.memory.AR.ARBURST := io.DMA.AR.ARBURST
      io.memory.AR.ARPROT := io.DMA.AR.ARPROT
      io.memory.AR.ARCACHE := io.DMA.AR.ARCACHE
      io.memory.AR.ARQOS := io.DMA.AR.ARQOS
      io.DMA.AR.ARREADY := io.memory.AR.ARREADY
    }.otherwise {
      // 窗口外请求在本地接受并生成等长DECERR burst，绝不进入CPU Xbar。
      io.DMA.AR.ARREADY := true.B
    }
  }.elsewhen(ChooseIFU) {
    io.memory.AR.ARVALID := io.ifu.AR.ARVALID
    io.memory.AR.ARID := IFUDownstreamID
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
  val DMALocalARFire = ChooseDMA && io.DMA.AR.ARVALID &&
    io.DMA.AR.ARREADY && !DMAARPermitted
  val readAddressAccepted = readAddressFire || DMALocalARFire
  when(!readGrantHeld && io.memory.AR.ARVALID && !io.memory.AR.ARREADY) {
    readGrantHeld := true.B
    heldReadGrant := Mux(ChooseLSU, GrantLSU, Mux(ChooseDMA, GrantDMA, GrantIFU))
  }.elsewhen(readGrantHeld && readAddressAccepted) {
    readGrantHeld := false.B
  }
  // AXI 要求 VALID 一旦提出必须保持到 READY：被授权方若在地址握手前撤销
  // ARVALID，授权会永久锁死并静默阻塞其余两方。仿真断言把它变成显式错误；
  // 断言在综合后消失，因此再加一个可综合兜底：持续 1024 拍仍看不到
  // ARVALID 就释放授权（此时本级 ARVALID 已为低，撤销不违反稳定性），
  // 让其余主设备继续推进而不是整机挂死。
  val GrantedARValid = MuxCase(
    false.B,
    Seq(
      (heldReadGrant === GrantIFU) -> io.ifu.AR.ARVALID,
      (heldReadGrant === GrantLSU) -> io.LSU.AR.ARVALID,
      (heldReadGrant === GrantDMA) -> io.DMA.AR.ARVALID
    )
  )
  when(readGrantHeld) {
    assert(
      GrantedARValid,
      "granted master dropped ARVALID before its address handshake"
    )
  }
  val grantStallTimer = RegInit(0.U(10.W))
  when(!readGrantHeld || GrantedARValid) {
    grantStallTimer := 0.U
  }.otherwise {
    grantStallTimer := grantStallTimer + 1.U
  }
  when(readGrantHeld && grantStallTimer === GrantTimeout.U) {
    readGrantHeld := false.B
  }
  when(readAddressFire && ChooseLSU) {
    LSUReadBusy(LSUDownstreamID) := true.B
    LSUReadIngressDone(LSUDownstreamID) := false.B
    lastReadGrant := GrantLSU
  }
  when(readAddressFire && ChooseIFU) {
    IFUReadBusy(IFUDownstreamID) := true.B
    IFUReadIngressDone(IFUDownstreamID) := false.B
    lastReadGrant := GrantIFU
  }
  when((readAddressFire && ChooseDMA) || DMALocalARFire) {
    DMAReadOutstanding := true.B
    DMAReadOriginalID := io.DMA.AR.ARID
    DMAReadLocalError := DMALocalARFire
    DMAReadIngressDone := false.B
    when(DMALocalARFire) {
      DMAReadErrorLength := io.DMA.AR.ARLEN
      DMAReadErrorBeat := 0.U
    }
    lastReadGrant := GrantDMA
  }

  // 每个owner使用独立响应队列。DMA队列能容纳AXI4允许的一整笔最大
  // 256-beat burst；因此即使CPU slave端永久RREADY=0，该笔DMA响应也
  // 不会占住共享memory.R并阻止IFU/LSU响应。CPU队列按 ID 保存多笔
  // 在途响应，深度覆盖 4-bit ID 的全部槽位，避免 PTW 多 outstanding
  // 时响应在队列口互相阻塞。
  private val IFURQueue = Module(
    new Queue(new ReadResponsePayload, CpuResponseQueueDepth,
      pipe = true, flow = false)
  )
  private val LSURQueue = Module(
    new Queue(new ReadResponsePayload, CpuResponseQueueDepth,
      pipe = true, flow = false)
  )
  private val DMARQueue = Module(
    new Queue(new ReadResponsePayload, DMAResponseQueueDepth,
      pipe = true, flow = false)
  )
  for (queue <- Seq(IFURQueue, LSURQueue, DMARQueue)) {
    queue.io.enq.valid := false.B
    queue.io.enq.bits.ID := io.memory.R.RID
    queue.io.enq.bits.data := io.memory.R.RDATA
    queue.io.enq.bits.response := io.memory.R.RRESP
    queue.io.enq.bits.last := io.memory.R.RLAST
  }

  // 有些零延迟从机会在AR握手同拍给出第一拍R。把本拍新接受的 ID 也
  // 纳入 expected；Queue是非flow-through，响应真正出队时原DMA ID已经
  // 在同一上升沿锁存完成。
  val IFUARAcceptedNow = readAddressFire && ChooseIFU
  val LSUARAcceptedNow = readAddressFire && ChooseLSU
  val DMAARAcceptedNow = readAddressFire && ChooseDMA
  val rid = io.memory.R.RID
  val IncomingForIFU =
    (IFUReadBusy(rid) && !IFUReadIngressDone(rid)) ||
      (IFUARAcceptedNow && IFUDownstreamID === rid)
  val IncomingForLSU =
    (LSUReadBusy(rid) && !LSUReadIngressDone(rid)) ||
      (LSUARAcceptedNow && LSUDownstreamID === rid)
  val IncomingForDMA = rid === DMAReadID &&
    ((DMAReadOutstanding && !DMAReadLocalError && !DMAReadIngressDone) ||
      DMAARAcceptedNow)
  val incomingRIDExpected = IncomingForIFU || IncomingForLSU || IncomingForDMA

  IFURQueue.io.enq.valid := io.memory.R.RVALID && IncomingForIFU
  LSURQueue.io.enq.valid := io.memory.R.RVALID && IncomingForLSU
  DMARQueue.io.enq.valid := io.memory.R.RVALID && IncomingForDMA
  io.memory.R.RREADY := MuxCase(
    false.B,
    Seq(
      IncomingForIFU -> IFURQueue.io.enq.ready,
      IncomingForLSU -> LSURQueue.io.enq.ready,
      IncomingForDMA -> DMARQueue.io.enq.ready
    )
  )

  IFURQueue.io.deq.ready := io.ifu.R.RREADY
  io.ifu.R.RID := IFURQueue.io.deq.bits.ID
  io.ifu.R.RDATA := IFURQueue.io.deq.bits.data
  io.ifu.R.RRESP := IFURQueue.io.deq.bits.response
  io.ifu.R.RLAST := IFURQueue.io.deq.bits.last
  io.ifu.R.RVALID := IFURQueue.io.deq.valid

  LSURQueue.io.deq.ready := io.LSU.R.RREADY
  // 下游 LSU ID 1 在端口上恢复成局部 ID 0；reuse_dcache 路径的 AXIReadMux
  // 用它区分 DCache 与 PTW 的 3..15 响应。
  io.LSU.R.RID := Mux(LSURQueue.io.deq.bits.ID === LSUReadID,
    0.U, LSURQueue.io.deq.bits.ID)
  io.LSU.R.RDATA := LSURQueue.io.deq.bits.data
  io.LSU.R.RRESP := LSURQueue.io.deq.bits.response
  io.LSU.R.RLAST := LSURQueue.io.deq.bits.last
  io.LSU.R.RVALID := LSURQueue.io.deq.valid

  DMARQueue.io.deq.ready := io.DMA.R.RREADY && !DMAReadLocalError
  when(!DMAReadLocalError) {
    io.DMA.R.RID := DMAReadOriginalID
    io.DMA.R.RDATA := DMARQueue.io.deq.bits.data
    io.DMA.R.RRESP := DMARQueue.io.deq.bits.response
    io.DMA.R.RLAST := DMARQueue.io.deq.bits.last
    io.DMA.R.RVALID := DMARQueue.io.deq.valid
  }
  when(DMAReadLocalError) {
    io.DMA.R.RID := DMAReadOriginalID
    io.DMA.R.RDATA := 0.U
    io.DMA.R.RRESP := DECERR
    io.DMA.R.RLAST := DMAReadErrorBeat === DMAReadErrorLength
    io.DMA.R.RVALID := true.B
  }

  val memoryRFire = io.memory.R.RVALID && io.memory.R.RREADY
  when(memoryRFire && io.memory.R.RLAST && IncomingForLSU) {
    LSUReadIngressDone(rid) := true.B
  }
  when(memoryRFire && io.memory.R.RLAST && IncomingForIFU) {
    IFUReadIngressDone(rid) := true.B
  }
  when(memoryRFire && io.memory.R.RLAST && IncomingForDMA) {
    DMAReadIngressDone := true.B
  }
  when(LSURQueue.io.deq.fire && LSURQueue.io.deq.bits.last) {
    LSUReadBusy(LSURQueue.io.deq.bits.ID) := false.B
    LSUReadIngressDone(LSURQueue.io.deq.bits.ID) := false.B
  }
  when(IFURQueue.io.deq.fire && IFURQueue.io.deq.bits.last) {
    IFUReadBusy(IFURQueue.io.deq.bits.ID) := false.B
    IFUReadIngressDone(IFURQueue.io.deq.bits.ID) := false.B
  }
  when(DMARQueue.io.deq.fire && DMARQueue.io.deq.bits.last) {
    DMAReadOutstanding := false.B
    DMAReadIngressDone := false.B
  }
  when(DMAReadLocalError && io.DMA.R.RREADY) {
    when(DMAReadErrorBeat === DMAReadErrorLength) {
      DMAReadLocalError := false.B
      DMAReadOutstanding := false.B
      DMAReadIngressDone := false.B
    }.otherwise {
      DMAReadErrorBeat := DMAReadErrorBeat + 1.U
    }
  }
  when(io.memory.R.RVALID) {
    assert(
      incomingRIDExpected,
      "AXI arbiter received an unknown read response ID"
    )
    assert(
      !(IncomingForIFU && IncomingForLSU),
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
  val AWDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeOwner = RegInit(GrantLSU)
  // 初始让LSU在同时请求时先获得写通道，之后在LSU/DMA之间轮转。
  val lastWriteGrant = RegInit(GrantDMA)
  val DMAWriteOriginalID = RegInit(0.U(4.W))
  val DMAWritePermitted = RegInit(false.B)
  val DMAWriteOutstanding = RegInit(false.B)
  val DownstreamWriteID = RegInit(0.U(4.W))
  // DMA AW 载荷快照：B 到达时用 [address, address + beats*bytes - 1] 生成
  // snoop 范围，因此 B 不再需要反查已经推进的 W 通道。
  val DMAWriteAddress = RegInit(0.U(AddressWidth.W))
  val DMAWriteLength = RegInit(0.U(8.W))
  val DMAWriteSize = RegInit(0.U(3.W))
  val DMAWriteBurst = RegInit(0.U(2.W))
  val DMASnoopPending = RegInit(false.B)
  val DMASnoopStartReg = RegInit(0.U(AddressWidth.W))
  val DMASnoopEndReg = RegInit(0.U(AddressWidth.W))
  // DMA专属B弹性槽。下游B一旦进入此槽，写仲裁器立即可服务LSU；
  // CPU slave的BREADY只控制该槽出队，不再反压共享memory.B。
  val DMABValid = RegInit(false.B)
  val DMABID = RegInit(0.U(4.W))
  val DMABResponse = RegInit(0.U(2.W))
  // hardware_snoop 下下游 B 先进入该槽，等 Cache 失效完成后再搬到
  // DMABValid（对外 BVALID）。软件看到的 B 因此是“失效后”的完成事件。
  val DMABPendingID = RegInit(0.U(4.W))
  val DMABPendingResponse = RegInit(0.U(2.W))
  io.DMA.B.BVALID := DMABValid
  io.DMA.B.BID := DMABID
  io.DMA.B.BRESP := DMABResponse
  val DMABFire = DMABValid && io.DMA.B.BREADY
  when(DMABFire) {
    DMABValid := false.B
    DMAWriteOutstanding := false.B
  }
  // 一次 burst 实际覆盖的字节范围。FIXED burst 每拍都写同一组字节，
  // INCR 按 LEN+1 个 beat 展开；dmaRequestPermitted 已保证不越过 4GiB，
  // 因此 AddressWidth+1 位计算不会回绕。
  private val snoopExtendedWidth = AddressWidth + 1
  private val DMABeatBytes = 1.U(snoopExtendedWidth.W) << DMAWriteSize
  private val DMABeats = (DMAWriteLength +& 1.U).pad(snoopExtendedWidth)
  private val DMABurstBytes =
    Mux(DMAWriteBurst === 0.U, DMABeatBytes, DMABeats * DMABeatBytes)
  private val DMARangeStart = Cat(0.U(1.W), DMAWriteAddress)
  private val DMARangeEnd = DMARangeStart + DMABurstBytes - 1.U
  if (HardwareSnoop) {
    io.DMASnoopValid := DMASnoopPending
    io.DMASnoopStart := DMASnoopStartReg
    io.DMASnoopEnd := DMASnoopEndReg
    when(DMASnoopPending && io.DMASnoopDone) {
      assert(!DMABValid, "DMA B slot occupied while snoop completes")
      DMASnoopPending := false.B
      DMABValid := true.B
      DMABID := DMABPendingID
      DMABResponse := DMABPendingResponse
    }
  }

  switch(writeState) {
    is(writeIdle) {
      AWDone := false.B
      wDone := false.B
      val LSURequest = io.LSU.AW.AWVALID || io.LSU.W.WVALID
      // 原子读改写期间不接受新的 DMA 写，避免在 AMO 的读与写之间插入
      // 外部 store；已在途的 DMA 写由 busQuiet 在原子事务开始前排空。
      val DMARequest = !io.atomicBusy && !DMAWriteOutstanding &&
        (io.DMA.AW.AWVALID || io.DMA.W.WVALID)
      val ChooseDMA = DMARequest && (!LSURequest || lastWriteGrant === GrantLSU)
      when(LSURequest || DMARequest) {
        writeOwner := Mux(ChooseDMA, GrantDMA, GrantLSU)
        lastWriteGrant := Mux(ChooseDMA, GrantDMA, GrantLSU)
        writeState := writeRequest
      }
    }
    is(writeRequest) {
      val OwnerDMA = writeOwner === GrantDMA
      val SelectedAWValid = Mux(OwnerDMA, io.DMA.AW.AWVALID, io.LSU.AW.AWVALID)
      val selectedWValid = Mux(OwnerDMA, io.DMA.W.WVALID, io.LSU.W.WVALID)
      val selectedWLast = Mux(OwnerDMA, io.DMA.W.WLAST, io.LSU.W.WLAST)
      val DMAAWPermitted = !io.DMAAWLock && dmaRequestPermitted(
        io.DMA.AW.AWADDR,
        io.DMA.AW.AWLEN,
        io.DMA.AW.AWSIZE,
        io.DMA.AW.AWBURST
      )

      io.memory.AW.AWVALID := !AWDone && SelectedAWValid &&
        (!OwnerDMA || DMAAWPermitted)
      io.memory.AW.AWID := Mux(OwnerDMA, DMAWriteID, io.LSU.AW.AWID)
      io.memory.AW.AWADDR := Mux(OwnerDMA, io.DMA.AW.AWADDR, io.LSU.AW.AWADDR)
      io.memory.AW.AWLEN := Mux(OwnerDMA, io.DMA.AW.AWLEN, io.LSU.AW.AWLEN)
      io.memory.AW.AWSIZE := Mux(OwnerDMA, io.DMA.AW.AWSIZE, io.LSU.AW.AWSIZE)
      io.memory.AW.AWBURST := Mux(OwnerDMA, io.DMA.AW.AWBURST, io.LSU.AW.AWBURST)
      io.memory.AW.AWPROT := Mux(OwnerDMA, io.DMA.AW.AWPROT, io.LSU.AW.AWPROT)
      io.memory.AW.AWCACHE :=
        Mux(OwnerDMA, io.DMA.AW.AWCACHE, io.LSU.AW.AWCACHE)
      io.memory.AW.AWQOS := Mux(OwnerDMA, io.DMA.AW.AWQOS, io.LSU.AW.AWQOS)
      io.LSU.AW.AWREADY := io.memory.AW.AWREADY && !AWDone && !OwnerDMA
      io.DMA.AW.AWREADY := !AWDone && OwnerDMA &&
        Mux(DMAAWPermitted, io.memory.AW.AWREADY, true.B)
      val SelectedAWReady = Mux(OwnerDMA, io.DMA.AW.AWREADY, io.LSU.AW.AWREADY)
      val AWFire = !AWDone && SelectedAWValid && SelectedAWReady

      // DMA的W通道在AW被分类前保持反压。这样即使W先到，也不会在得知
      // 地址合法性之前把数据泄漏到下游；合法AW确认后的各拍再直接流过。
      val DMAWReady = AWDone && Mux(DMAWritePermitted, io.memory.W.WREADY, true.B)
      io.memory.W.WVALID := selectedWValid && !wDone &&
        (!OwnerDMA || (AWDone && DMAWritePermitted))
      io.memory.W.WDATA := Mux(OwnerDMA, io.DMA.W.WDATA, io.LSU.W.WDATA)
      io.memory.W.WSTRB := Mux(OwnerDMA, io.DMA.W.WSTRB, io.LSU.W.WSTRB)
      io.memory.W.WLAST := selectedWLast
      io.LSU.W.WREADY := io.memory.W.WREADY && !wDone && !OwnerDMA
      io.DMA.W.WREADY := DMAWReady && !wDone && OwnerDMA
      val selectedWReady = Mux(OwnerDMA, io.DMA.W.WREADY, io.LSU.W.WREADY)
      val wFire = !wDone && selectedWValid && selectedWReady
      val wLastFire = wFire && selectedWLast

      when(AWFire) {
        AWDone := true.B
        DownstreamWriteID := Mux(OwnerDMA, DMAWriteID, io.LSU.AW.AWID)
        when(OwnerDMA) {
          DMAWriteOriginalID := io.DMA.AW.AWID
          DMAWritePermitted := DMAAWPermitted
          DMAWriteOutstanding := true.B
          DMAWriteAddress := io.DMA.AW.AWADDR
          DMAWriteLength := io.DMA.AW.AWLEN
          DMAWriteSize := io.DMA.AW.AWSIZE
          DMAWriteBurst := io.DMA.AW.AWBURST
        }
      }
      when(wLastFire) { wDone := true.B }
      when((AWDone || AWFire) && (wDone || wLastFire)) {
        when(OwnerDMA && !DMAWritePermitted) {
          assert(!DMABValid, "local DMA write error collided with a pending B")
          DMABValid := true.B
          DMABID := DMAWriteOriginalID
          DMABResponse := DECERR
          writeState := writeIdle
        }.otherwise {
          writeState := writeResponse
        }
      }
    }
    is(writeResponse) {
      val OwnerDMA = writeOwner === GrantDMA
      val bidMatches = io.memory.B.BID === DownstreamWriteID
      // 错误BID不属于当前事务，不能吞掉并让本仲裁器与下游
      // fabric对在途事务的认识脱节。仿真assert会立即指出违规从设备。
      io.memory.B.BREADY := bidMatches &&
        Mux(OwnerDMA, !DMABValid, io.LSU.B.BREADY)
      when(OwnerDMA) {
        // DMA响应在MatchingBFire时写入独立B槽，下一拍即可释放共享写FSM。
      }.otherwise {
        io.LSU.B.BID := io.memory.B.BID
        io.LSU.B.BRESP := io.memory.B.BRESP
        io.LSU.B.BVALID := io.memory.B.BVALID && bidMatches
      }
      val matchingBFire = io.memory.B.BVALID && io.memory.B.BREADY && bidMatches
      when(io.memory.B.BVALID) {
        assert(bidMatches, "AXI arbiter received a write response with an unknown ID")
      }
      when(matchingBFire) {
        when(OwnerDMA) {
          io.DMAWriteInvalidate := true.B
          if (HardwareSnoop) {
            // B 已证明下游写完成。先锁存地址级失效请求，等 ICache/DCache
            // 都确认失效后再把 B 交给 CPU slave；这保证“看到 B”时缓存里
            // 不可能还留着 DMA 之前的旧副本。
            assert(!DMASnoopPending,
              "new DMA B arrived while previous snoop is still pending")
            DMASnoopStartReg := DMARangeStart(AddressWidth - 1, 0)
            DMASnoopEndReg := DMARangeEnd(AddressWidth - 1, 0)
            DMASnoopPending := true.B
            DMABPendingID := DMAWriteOriginalID
            DMABPendingResponse := io.memory.B.BRESP
          } else {
            DMABValid := true.B
            DMABID := DMAWriteOriginalID
            DMABResponse := io.memory.B.BRESP
          }
        }
        writeState := writeIdle
      }
    }
  }
  // 只有写通道空闲且没有 DMA 读写事务在途时才允许 LSU 开始原子事务。
  // 该信号与 atomicBusy 配合：先等 quiet，再拉 busy 锁住新的 DMA 请求。
  io.busQuiet := writeState === writeIdle && !DMAWriteOutstanding &&
    !DMAReadOutstanding
}
