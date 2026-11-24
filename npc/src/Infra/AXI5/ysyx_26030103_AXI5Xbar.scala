package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_AXI5Xbar(
    private[infra] val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    private[infra] val PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
    private[infra] val PLICBaseValue: BigInt =
      BigInt(ysyx_26030103_PhysicalMemoryMap.DefaultPLICBase),
    private[infra] val PLICSizeBytes: BigInt =
      BigInt(ysyx_26030103_PhysicalMemoryMap.DefaultPLICSize),
    private[infra] val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    private[infra] val IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth,
    private[infra] val ResponseQueueDepth: Int =
      ysyx_26030103_KconfigDefaults.AXIXbarResponseQueueDepth,
    private[infra] val CLINTBaseValue: BigInt =
      BigInt(ysyx_26030103_PMAOptions.Default.CLINTBase),
    private[infra] val CLINTSizeBytes: BigInt =
      BigInt(ysyx_26030103_PMAOptions.Default.CLINTSize)
) extends Module
    with AXI5XbarDecode
    with AXI5XbarWrite
    with AXI5XbarRead
    with AXI5XbarResponse {
  require(DataWidth == 32 || DataWidth == 64,
    s"AXI5Xbar DataWidth 非法: $DataWidth")
  require(ResponseQueueDepth >= 2 && ResponseQueueDepth <= 16,
    s"AXI5Xbar RespQueueDepth 非法: $ResponseQueueDepth")
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
  val CLINTBase = CLINTBaseValue.U(AddressWidth.W)
  val CLINTEnd = (CLINTBaseValue + CLINTSizeBytes).U(AddressWidth.W)
  require(PLICBaseValue >= 0 && PLICBaseValue < (BigInt(1) << AddressWidth),
    "PLIC 基地址超出地址宽度")
  require(PLICSizeBytes > 0 && PLICBaseValue + PLICSizeBytes <= (BigInt(1) << AddressWidth),
    "PLIC 地址窗口超出地址宽度")
  require(PLICBaseValue % BigInt(4096) == BigInt(0),
    "PLIC 基地址必须 4KiB 对齐")
  require(PLICSizeBytes % BigInt(4096) == BigInt(0),
    "PLIC 窗口大小必须为 4KiB 的整数倍")
  val PLICBase = PLICBaseValue.U(AddressWidth.W)
  val PLICEnd = (PLICBaseValue + PLICSizeBytes).U(AddressWidth.W)
  val targetSoCBus = 0.U(2.W)
  val TargetCLINT = 1.U(2.W)
  val TargetPLIC = 2.U(2.W)
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
  require(PMARegions.nonEmpty, "AXI Xbar物理地址图不能为空")
  require(
    PMARegions.forall(r => BigInt(r.EndExclusive) <= (BigInt(1) << AddressWidth)),
    "AXI Xbar PMA区域超出地址宽度"
  )

  // 读写通道独立；读状态按RID分槽保存，IFU/LSU 的固定ID可同时在下游在途。
  private[infra] val readSlotCount = 1 << IDWidth
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
  val ARAddressReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(AddressWidth.W))))
  val ARLengthReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(8.W))))
  val ARSizeReg = RegInit(VecInit(Seq.fill(readSlotCount)(2.U(3.W))))
  val ARBurstReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(2.W))))
  val ARProtReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(3.W))))
  val ARCacheReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(4.W))))
  val ARQosReg = RegInit(VecInit(Seq.fill(readSlotCount)(0.U(4.W))))
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
  val AWValidReg = RegInit(false.B)
  val wValidReg = RegInit(false.B)
  val AWIDReg = RegInit(0.U(IDWidth.W))
  val AWAddressReg = RegInit(0.U(AddressWidth.W))
  val AWLengthReg = RegInit(0.U(8.W))
  val AWSizeReg = RegInit(2.U(3.W))
  val AWBurstReg = RegInit(0.U(2.W))
  val AWProtReg = RegInit(0.U(3.W))
  val AWCacheReg = RegInit(0.U(4.W))
  val AWQosReg = RegInit(0.U(4.W))
  val wDataReg = RegInit(0.U(DataWidth.W))
  val wStrobeReg = RegInit(0.U((DataWidth / 8).W))
  val wLastReg = RegInit(false.B)
  // 写响应B通道回来时也没有地址，所以要记住写请求目标
  val writeTargetReg = RegInit(targetInvalid)
  val DownstreamAWDone = RegInit(false.B)
  val downstreamWDone = RegInit(false.B)
  // 用 Wire 控制上游 ready，避免直接读自己驱动的输出端口。
  val InAWReady = WireDefault(false.B)
  val inWReady = WireDefault(false.B)
  val InARReady = WireDefault(false.B)
  io.in.AW.AWREADY := InAWReady
  io.in.W.WREADY := inWReady
  io.in.AR.ARREADY := InARReady

  // 默认值
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
  io.SoCBus.AR.ARVALID := false.B
  io.SoCBus.AR.ARID := 0.U
  io.SoCBus.AR.ARADDR := 0.U
  io.SoCBus.AR.ARLEN := 0.U
  io.SoCBus.AR.ARSIZE := 2.U
  io.SoCBus.AR.ARBURST := 0.U
  io.SoCBus.AR.ARPROT := 0.U
  io.SoCBus.AR.ARCACHE := 0.U
  io.SoCBus.AR.ARQOS := 0.U
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
  io.CLINT.AR.ARVALID := false.B
  io.CLINT.AR.ARID := 0.U
  io.CLINT.AR.ARADDR := 0.U
  io.CLINT.AR.ARLEN := 0.U
  io.CLINT.AR.ARSIZE := 2.U
  io.CLINT.AR.ARBURST := 0.U
  io.CLINT.AR.ARPROT := 0.U
  io.CLINT.AR.ARCACHE := 0.U
  io.CLINT.AR.ARQOS := 0.U
  val InAWFire = io.in.AW.AWVALID && InAWReady
  val inWFire = io.in.W.WVALID && inWReady
  val InARFire = io.in.AR.ARVALID && InARReady

  // 写通道
  writeBody()

  // 读通道：每个AXI ID独占一个槽位，直到其RLAST被上游消费。
  val IncomingReadID = io.in.AR.ARID
  InARReady := readSlotState(IncomingReadID) === readSlotFree

  // SoCBus/CLINT/PLIC 各有独立AR通道，可同拍各发一项，共享目标每周期一项。
  // 这些仲裁信号同时被 readBody 与 responseBody 引用，声明留在主文件。
  val SOCRequestMask = VecInit((0 until readSlotCount).map { ID =>
    readSlotState(ID) === readSlotRequest &&
    readTargetReg(ID) === targetSoCBus
  }).asUInt
  val CLINTRequestMask = VecInit((0 until readSlotCount).map { ID =>
    readSlotState(ID) === readSlotRequest &&
    readTargetReg(ID) === TargetCLINT
  }).asUInt
  val PLICRequestMask = VecInit((0 until readSlotCount).map { ID =>
    readSlotState(ID) === readSlotRequest &&
    readTargetReg(ID) === TargetPLIC
  }).asUInt
  val SOCARHeld = RegInit(false.B)
  val SOCARHeldID = RegInit(0.U(IDWidth.W))
  val CLINTARHeld = RegInit(false.B)
  val CLINTARHeldID = RegInit(0.U(IDWidth.W))
  val PLICARHeld = RegInit(false.B)
  val PLICARHeldID = RegInit(0.U(IDWidth.W))
  val SOCRequestCandidateID = PriorityEncoder(SOCRequestMask)
  val CLINTRequestCandidateID = PriorityEncoder(CLINTRequestMask)
  val PLICRequestCandidateID = PriorityEncoder(PLICRequestMask)
  val SOCRequestValid = SOCARHeld || SOCRequestMask.orR
  val CLINTRequestValid = CLINTARHeld || CLINTRequestMask.orR
  val PLICRequestValid = PLICARHeld || PLICRequestMask.orR
  val SOCRequestID =
    Mux(SOCARHeld, SOCARHeldID, SOCRequestCandidateID)
  val CLINTRequestID =
    Mux(CLINTARHeld, CLINTARHeldID, CLINTRequestCandidateID)
  val PLICRequestID =
    Mux(PLICARHeld, PLICARHeldID, PLICRequestCandidateID)
  val SOCRequestFire = SOCRequestValid && io.SoCBus.AR.ARREADY
  val CLINTRequestFire = CLINTRequestValid && io.CLINT.AR.ARREADY
  val PLICRequestFire = PLICRequestValid && io.PLIC.AR.ARREADY

  readBody()

  // 响应小FIFO（4拍）：仲裁同拍到达的响应，并在上游反压时保持RID/RDATA/
  // RRESP/RLAST稳定；单拍缓冲会让被反压的响应阻塞无关owner（队头阻塞）。
  private[infra] class RPayload extends Bundle {
    val ID = UInt(IDWidth.W)
    val data = UInt(DataWidth.W)
    val response = UInt(2.W)
    val last = Bool()
  }
  private[infra] val rQueue =
    Module(new Queue(new RPayload, ResponseQueueDepth, pipe = true, flow = false))
  io.in.R.RID := rQueue.io.deq.bits.ID
  io.in.R.RVALID := rQueue.io.deq.valid
  io.in.R.RDATA := rQueue.io.deq.bits.data
  io.in.R.RRESP := rQueue.io.deq.bits.response
  io.in.R.RLAST := rQueue.io.deq.bits.last
  rQueue.io.deq.ready := io.in.R.RREADY

  rQueue.io.enq.valid := false.B
  rQueue.io.enq.bits.ID := 0.U
  rQueue.io.enq.bits.data := 0.U
  rQueue.io.enq.bits.response := OKAY
  rQueue.io.enq.bits.last := false.B

  responseBody()
}
