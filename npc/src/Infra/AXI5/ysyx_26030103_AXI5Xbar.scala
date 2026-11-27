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
    val in = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth))
    val SoCBus = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
    val CLINT = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
    val PLIC = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, IDWidth)
  })
  val OKAY = ysyx_26030103_AXI5Resp.OKAY
  val DECERR = ysyx_26030103_AXI5Resp.DECERR
  // RISC-V标准CLINT区间：默认 0x02000000 ~ 0x0200FFFF（64KiB）。
  require(CLINTBaseValue >= 0 && CLINTBaseValue < (BigInt(1) << AddressWidth),
    "CLINT 基地址超出地址宽度")
  require(CLINTSizeBytes > 0 && CLINTBaseValue + CLINTSizeBytes <= (BigInt(1) << AddressWidth),
    "CLINT 地址窗口超出地址宽度")
  require(PLICBaseValue >= 0 && PLICBaseValue < (BigInt(1) << AddressWidth),
    "PLIC 基地址超出地址宽度")
  require(PLICSizeBytes > 0 && PLICBaseValue + PLICSizeBytes <= (BigInt(1) << AddressWidth),
    "PLIC 地址窗口超出地址宽度")
  require(PLICBaseValue % BigInt(4096) == BigInt(0),
    "PLIC 基地址必须 4KiB 对齐")
  require(PLICSizeBytes % BigInt(4096) == BigInt(0),
    "PLIC 窗口大小必须为 4KiB 的整数倍")
  val targetSoCBus = 0.U(2.W)
  val TargetCLINT = 1.U(2.W)
  val TargetPLIC = 2.U(2.W)
  val targetInvalid = 3.U(2.W)
  ysyx_26030103_AXI5Idle.MasterOut(io.PLIC)
  require(PMARegions.nonEmpty, "AXI Xbar物理地址图不能为空")
  require(
    PMARegions.forall(r => BigInt(r.EndExclusive) <= (BigInt(1) << AddressWidth)),
    "AXI Xbar PMA区域超出地址宽度"
  )

  // 读写通道独立；读状态按RID分槽保存，IFU/LSU 的固定ID可同时在下游在途。
  private[infra] val readSlotCount = ysyx_26030103_AXIReadID.SlotCount
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
  val ARBurstShapes = RegInit(VecInit(Seq.fill(readSlotCount)(
    ysyx_26030103_AXI5BurstShape.Default(AddressWidth, IDWidth))))
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
  val AWBurstShape = RegInit(
    ysyx_26030103_AXI5BurstShape.Default(AddressWidth, IDWidth))
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
  ysyx_26030103_AXI5Idle.MasterOut(io.SoCBus)
  ysyx_26030103_AXI5Idle.MasterOut(io.CLINT)
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
  private[infra] case class ReadTarget(
      name: String,
      target: UInt,
      downstream: ysyx_26030103_AXI5IO
  )
  private[infra] val ReadTargets = Seq(
    ReadTarget("SoCBus", targetSoCBus, io.SoCBus),
    ReadTarget("CLINT", TargetCLINT, io.CLINT),
    ReadTarget("PLIC", TargetPLIC, io.PLIC)
  )
  private[infra] val ReadRequestMasks = ReadTargets.map { Entry =>
    VecInit((0 until readSlotCount).map { ID =>
      readSlotState(ID) === readSlotRequest &&
      readTargetReg(ID) === Entry.target
    }).asUInt
  }
  private[infra] val ReadARHelds = ReadTargets.map(_ => RegInit(false.B))
  private[infra] val ReadARHeldIDs =
    ReadTargets.map(_ => RegInit(0.U(IDWidth.W)))
  private[infra] val ReadRequestCandidateIDs =
    ReadRequestMasks.map { RequestMask => PriorityEncoder(RequestMask) }
  private[infra] val ReadRequestValids = ReadTargets.indices.map { Index =>
    ReadARHelds(Index) || ReadRequestMasks(Index).orR
  }
  private[infra] val ReadRequestIDs = ReadTargets.indices.map { Index =>
    Mux(ReadARHelds(Index), ReadARHeldIDs(Index),
      ReadRequestCandidateIDs(Index))
  }
  private[infra] val ReadRequestFires = ReadTargets.indices.map { Index =>
    ReadRequestValids(Index) && ReadTargets(Index).downstream.AR.ARREADY
  }

  readBody()

  // 响应队列深度由 ResponseQueueDepth 配置（2..16）：仲裁同拍到达的响应，
  // 并在上游反压时保持RID/RDATA/RRESP/RLAST稳定；单拍缓冲会让被反压的
  // 响应阻塞无关owner（队头阻塞）。
  private[infra] val rQueue =
    Module(new Queue(new ysyx_26030103_AXI5RPayload(IDWidth, DataWidth),
      ResponseQueueDepth, pipe = true, flow = false))
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
