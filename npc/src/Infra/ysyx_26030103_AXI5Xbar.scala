package ysyx_26030103.infra
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_AXI5Xbar(
    AddressWidth: Int = 32,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends Module {
  val io = IO(new Bundle {
    // 先做过笔记，因为这个是接裁决器的，裁决器是master，所以这里的对反
    val in = Flipped(new ysyx_26030103_AXI5IO(AddressWidth))
    val SoCBus = new ysyx_26030103_AXI5IO(AddressWidth)
    val CLINT = new ysyx_26030103_AXI5IO(AddressWidth)
  })
  // 我真的是烦死了ARM的命名规则
  val OKAY = "b00".U(2.W)
  val DECERR = "b11".U(2.W)
  // RISC-V标准CLINT区间：0x02000000 ~ 0x0200FFFF（64KiB），移出SDRAM窗口(0xa0000000)避免地址重叠
  val CLINTBase = "h02000000".U(32.W)
  val CLINTEnd = "h02010000".U(32.W)
  val TargetSoCBus = 0.U(2.W)
  val TargetCLINT = 1.U(2.W)
  val TargetInvalid = 2.U(2.W)
  require(PMARegions.nonEmpty, "AXI Xbar物理地址图不能为空")
  require(
    PMARegions.forall(r => BigInt(r.EndExclusive) <= (BigInt(1) << AddressWidth)),
    "AXI Xbar PMA区域超出地址宽度"
  )

  private def LastByte(
      Address: UInt,
      Len: UInt,
      Size: UInt,
      Burst: UInt
  ): UInt = {
    val ExtendedAddress = Cat(0.U(1.W), Address)
    val BeatBytes = 1.U((AddressWidth + 1).W) << Size
    val LastBeatOffset = Mux(
      Burst === 1.U,
      Len * BeatBytes,
      0.U
    )
    ExtendedAddress + LastBeatOffset + BeatBytes - 1.U
  }
  private def InRegion(
      Address: UInt,
      Len: UInt,
      Size: UInt,
      Burst: UInt,
      Region: ysyx_26030103_PMARegion
  ): Bool = {
    val ExtendedAddress = Cat(0.U(1.W), Address)
    val End = LastByte(Address, Len, Size, Burst)
    ExtendedAddress >= BigInt(Region.Base).U &&
    End < BigInt(Region.EndExclusive).U
  }
  private def InAnyRegion(
      Address: UInt,
      Len: UInt,
      Size: UInt,
      Burst: UInt,
      Regions: Seq[ysyx_26030103_PMARegion]
  ): Bool =
    Regions
      .map(InRegion(Address, Len, Size, Burst, _))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  def IsCLINT(address: UInt, len: UInt, size: UInt, burst: UInt): Bool = {
    val End = LastByte(address, len, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), CLINTBase) &&
    End < Cat(0.U(1.W), CLINTEnd)
  }
  private val ReadableRegions = PMARegions.filter(_.Readable)
  private val WritableRegions = PMARegions.filter(_.Writable)
  private val ReadableCLINT = ReadableRegions.exists(
    _ == ysyx_26030103_PhysicalMemoryMap.CLINT
  )
  private val WritableCLINT = WritableRegions.exists(
    _ == ysyx_26030103_PhysicalMemoryMap.CLINT
  )

  def decode(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      isWrite: Boolean
  ): UInt = {
    val ConfigSupported = size <= 2.U && (burst === 0.U || burst === 1.U)
    val PermittedRegions = if (isWrite) WritableRegions else ReadableRegions
    val CLINTPermitted = if (isWrite) WritableCLINT else ReadableCLINT
    Mux(
      !ConfigSupported,
      TargetInvalid,
      Mux(
        CLINTPermitted.B && IsCLINT(address, len, size, burst),
        TargetCLINT,
        Mux(
          InAnyRegion(address, len, size, burst, PermittedRegions),
          TargetSoCBus,
          TargetInvalid
        )
      )
    )
  }
  // AXI读写通道相互独立。读状态按RID分别保存，因此固定的IFU/LSU ID
  // 都可以同时处于下游互连结构的在途状态。
  private val ReadSlotCount = 16
  val readSlotStates = Enum(5)
  val ReadSlotFree = readSlotStates(0)
  val ReadSlotRequest = readSlotStates(1)
  val ReadSlotResponse = readSlotStates(2)
  val ReadSlotDECERR = readSlotStates(3)
  val ReadSlotLocalLast = readSlotStates(4)
  val ReadSlotState = RegInit(
    VecInit(Seq.fill(ReadSlotCount)(ReadSlotFree))
  )
  val ReadTargetReg = RegInit(
    VecInit(Seq.fill(ReadSlotCount)(TargetInvalid))
  )
  val ARAddressReg = RegInit(VecInit(Seq.fill(ReadSlotCount)(0.U(32.W))))
  val ARLENReg = RegInit(VecInit(Seq.fill(ReadSlotCount)(0.U(8.W))))
  val ARSIZEReg = RegInit(VecInit(Seq.fill(ReadSlotCount)(2.U(3.W))))
  val ARBURSTReg = RegInit(VecInit(Seq.fill(ReadSlotCount)(0.U(2.W))))
  val ARPROTReg = RegInit(VecInit(Seq.fill(ReadSlotCount)(0.U(3.W))))
  val ReadDECERRBeatReg = RegInit(
    VecInit(Seq.fill(ReadSlotCount)(0.U(8.W)))
  )

  val writeStates = Enum(6)
  val StateWriteIdle = writeStates(0)
  val StateWriteCollect = writeStates(1)
  val StateWriteRequest = writeStates(2)
  val StateWriteResponse = writeStates(3)
  val StateWriteDECERR = writeStates(4)
  // 不支持转发的写burst仍需吃完全部W beat，之后再返回DECERR。
  val StateWriteDrain = writeStates(5)
  val writeState = RegInit(StateWriteIdle)
  val AWValidReg = RegInit(false.B)
  val WValidReg = RegInit(false.B)
  val AWIDReg = RegInit(0.U(4.W))
  val AWAddressReg = RegInit(0.U(32.W))
  val AWLENReg = RegInit(0.U(8.W))
  val AWSIZEReg = RegInit(2.U(3.W))
  val AWBURSTReg = RegInit(0.U(2.W))
  val AWPROTReg = RegInit(0.U(3.W))
  val WDataReg = RegInit(0.U(32.W))
  val WSTRBReg = RegInit(0.U(4.W))
  val WLASTReg = RegInit(false.B)
  // 写响应B通道回来时也没有地址，所以要记住写请求目标
  val WriteTargetReg = RegInit(TargetInvalid)
  val DownstreamAWDone = RegInit(false.B)
  val DownstreamWDone = RegInit(false.B)
  // 用 Wire 控制上游 ready，避免直接读自己驱动的输出端口。
  val InAWReady = WireDefault(false.B)
  val InWReady = WireDefault(false.B)
  val InARReady = WireDefault(false.B)
  io.in.AW.AWREADY := InAWReady
  io.in.W.WREADY := InWReady
  io.in.AR.ARREADY := InARReady

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
  io.SoCBus.R.RREADY := false.B
  io.CLINT.AW.AWVALID := false.B
  io.CLINT.AW.AWID := 0.U
  io.CLINT.AW.AWADDR := 0.U
  io.CLINT.AW.AWLEN := 0.U
  io.CLINT.AW.AWSIZE := 2.U
  io.CLINT.AW.AWBURST := 0.U
  io.CLINT.AW.AWPROT := 0.U
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
  io.CLINT.R.RREADY := false.B
  val InAWFire = io.in.AW.AWVALID && InAWReady
  val InWFire = io.in.W.WVALID && InWReady
  val InARFire = io.in.AR.ARVALID && InARReady

  // -------------------- 写通道 --------------------
  val WriteTargetAfterAW =
    Mux(
      InAWFire,
      decode(
        io.in.AW.AWADDR,
        io.in.AW.AWLEN,
        io.in.AW.AWSIZE,
        io.in.AW.AWBURST,
        isWrite = true
      ),
      WriteTargetReg
    )
  val WriteLenAfterAW = Mux(InAWFire, io.in.AW.AWLEN, AWLENReg)
  val AWCollected = AWValidReg || InAWFire
  val WCollected = WValidReg || InWFire
  val FirstWLastAfterW = Mux(InWFire, io.in.W.WLAST, WLASTReg)
  val WriteCannotForward = WriteTargetAfterAW === TargetInvalid ||
    (WriteTargetAfterAW === TargetCLINT && WriteLenAfterAW =/= 0.U)
  when(InAWFire) {
    AWIDReg := io.in.AW.AWID
    AWAddressReg := io.in.AW.AWADDR
    AWLENReg := io.in.AW.AWLEN
    AWSIZEReg := io.in.AW.AWSIZE
    AWBURSTReg := io.in.AW.AWBURST
    AWPROTReg := io.in.AW.AWPROT
    WriteTargetReg := decode(
      io.in.AW.AWADDR,
      io.in.AW.AWLEN,
      io.in.AW.AWSIZE,
      io.in.AW.AWBURST,
      isWrite = true
    )
    AWValidReg := true.B
  }
  // Idle/Collect阶段只缓存首个W beat；进入转发阶段后其余beat直接流过。
  when(
    InWFire &&
      (writeState === StateWriteIdle || writeState === StateWriteCollect)
  ) {
    WDataReg := io.in.W.WDATA
    WSTRBReg := io.in.W.WSTRB
    WLASTReg := io.in.W.WLAST
    WValidReg := true.B
  }
  when(writeState === StateWriteIdle) {
    val HasWriteRequest =
      io.in.AW.AWVALID || io.in.W.WVALID || AWValidReg || WValidReg
    when(HasWriteRequest) { // 哪个通道还没缓存，就对哪个通道拉ready
      InAWReady := !AWValidReg
      InWReady := !WValidReg

      // 如果本周期结束后AW和W都已经收到了，就可以进入转发阶段
      when(AWCollected && WCollected) {
        DownstreamAWDone := false.B
        DownstreamWDone := false.B
        when(WriteCannotForward) {
          // 首beat已由收集级接收；若它不是最后一拍，继续排空余下W。
          WValidReg := false.B
          writeState := Mux(
            FirstWLastAfterW,
            StateWriteDECERR,
            StateWriteDrain
          )
        }.otherwise {
          writeState := StateWriteRequest
        }
      }.otherwise {
        writeState := StateWriteCollect
      }
    }
  }.elsewhen(writeState === StateWriteCollect) {
    // 已经开始处理写事务，但是AW和W还没有都收到
    // 例如AW先到、W后到，或者W先到、AW后到
    InAWReady := !AWValidReg
    InWReady := !WValidReg

    when(AWCollected && WCollected) {
      DownstreamAWDone := false.B
      DownstreamWDone := false.B
      when(WriteCannotForward) {
        WValidReg := false.B
        writeState := Mux(
          FirstWLastAfterW,
          StateWriteDECERR,
          StateWriteDrain
        )
      }.otherwise {
        writeState := StateWriteRequest
      }
    }
  }.elsewhen(writeState === StateWriteRequest) {
    // AW只发送一次；收集级缓存首个W beat，之后的beat直接以ready/valid
    // 流过，直到真正握手的WLAST为止。
    when(WriteTargetReg === TargetSoCBus) {
      val SendAW = !DownstreamAWDone
      val UseBufferedW = WValidReg
      val StreamW = !WValidReg && !DownstreamWDone

      io.SoCBus.AW.AWVALID := SendAW
      io.SoCBus.AW.AWID := AWIDReg
      io.SoCBus.AW.AWADDR := AWAddressReg
      io.SoCBus.AW.AWLEN := AWLENReg
      io.SoCBus.AW.AWSIZE := AWSIZEReg
      io.SoCBus.AW.AWBURST := AWBURSTReg
      io.SoCBus.AW.AWPROT := AWPROTReg

      io.SoCBus.W.WVALID := Mux(UseBufferedW, true.B, StreamW && io.in.W.WVALID)
      io.SoCBus.W.WDATA := Mux(UseBufferedW, WDataReg, io.in.W.WDATA)
      io.SoCBus.W.WSTRB := Mux(UseBufferedW, WSTRBReg, io.in.W.WSTRB)
      io.SoCBus.W.WLAST := Mux(UseBufferedW, WLASTReg, io.in.W.WLAST)
      InWReady := StreamW && io.SoCBus.W.WREADY

      val AWFire = SendAW && io.SoCBus.AW.AWREADY
      val BufferedWFire = UseBufferedW && io.SoCBus.W.WREADY
      val StreamWFire = StreamW && io.in.W.WVALID && io.SoCBus.W.WREADY
      val WFire = BufferedWFire || StreamWFire
      val WLastFire = WFire && Mux(UseBufferedW, WLASTReg, io.in.W.WLAST)

      when(AWFire) {
        DownstreamAWDone := true.B
      }
      when(BufferedWFire) {
        WValidReg := false.B
      }
      when(WLastFire) {
        DownstreamWDone := true.B
      }

      when((DownstreamAWDone || AWFire) && (DownstreamWDone || WLastFire)) {
        writeState := StateWriteResponse
      }
    }.elsewhen(WriteTargetReg === TargetCLINT) {
      val SendAW = !DownstreamAWDone
      val UseBufferedW = WValidReg
      val StreamW = !WValidReg && !DownstreamWDone

      io.CLINT.AW.AWVALID := SendAW
      io.CLINT.AW.AWID := AWIDReg
      io.CLINT.AW.AWADDR := AWAddressReg
      io.CLINT.AW.AWLEN := AWLENReg
      io.CLINT.AW.AWSIZE := AWSIZEReg
      io.CLINT.AW.AWBURST := AWBURSTReg
      io.CLINT.AW.AWPROT := AWPROTReg

      io.CLINT.W.WVALID := Mux(UseBufferedW, true.B, StreamW && io.in.W.WVALID)
      io.CLINT.W.WDATA := Mux(UseBufferedW, WDataReg, io.in.W.WDATA)
      io.CLINT.W.WSTRB := Mux(UseBufferedW, WSTRBReg, io.in.W.WSTRB)
      io.CLINT.W.WLAST := Mux(UseBufferedW, WLASTReg, io.in.W.WLAST)
      InWReady := StreamW && io.CLINT.W.WREADY

      val AWFire = SendAW && io.CLINT.AW.AWREADY
      val BufferedWFire = UseBufferedW && io.CLINT.W.WREADY
      val StreamWFire = StreamW && io.in.W.WVALID && io.CLINT.W.WREADY
      val WFire = BufferedWFire || StreamWFire
      val WLastFire = WFire && Mux(UseBufferedW, WLASTReg, io.in.W.WLAST)

      when(AWFire) {
        DownstreamAWDone := true.B
      }
      when(BufferedWFire) {
        WValidReg := false.B
      }
      when(WLastFire) {
        DownstreamWDone := true.B
      }

      when((DownstreamAWDone || AWFire) && (DownstreamWDone || WLastFire)) {
        writeState := StateWriteResponse
      }
    }.otherwise {
      writeState := Mux(
        WValidReg && WLASTReg,
        StateWriteDECERR,
        StateWriteDrain
      )
    }
  }.elsewhen(writeState === StateWriteDrain) {
    // 地址无效或本地CLINT不支持burst：首beat已在收集级被接受，
    // 继续吞掉剩余beat，避免上游停在W通道而永远等不到错误响应。
    InWReady := true.B
    when(io.in.W.WVALID && io.in.W.WLAST) {
      writeState := StateWriteDECERR
    }
  }.elsewhen(writeState === StateWriteResponse) {
    // B响应回来时没有地址，所以要根据之前保存的WriteTargetReg选择下游
    when(WriteTargetReg === TargetSoCBus) {
      io.in.B.BID := io.SoCBus.B.BID
      io.in.B.BVALID := io.SoCBus.B.BVALID
      io.in.B.BRESP := io.SoCBus.B.BRESP
      io.SoCBus.B.BREADY := io.in.B.BREADY

      when(io.SoCBus.B.BVALID && io.in.B.BREADY) {
        AWValidReg := false.B
        WValidReg := false.B
        DownstreamAWDone := false.B
        DownstreamWDone := false.B
        writeState := StateWriteIdle
      }
    }.elsewhen(WriteTargetReg === TargetCLINT) {
      io.in.B.BID := io.CLINT.B.BID
      io.in.B.BVALID := io.CLINT.B.BVALID
      io.in.B.BRESP := io.CLINT.B.BRESP
      io.CLINT.B.BREADY := io.in.B.BREADY

      when(io.CLINT.B.BVALID && io.in.B.BREADY) {
        AWValidReg := false.B
        WValidReg := false.B
        DownstreamAWDone := false.B
        DownstreamWDone := false.B
        writeState := StateWriteIdle
      }
    }.otherwise {
      writeState := StateWriteDECERR
    }
  }.elsewhen(writeState === StateWriteDECERR) {
    // 地址空洞和不支持的burst在CPU内部完成，不访问任何下游设备。
    io.in.B.BID := AWIDReg
    io.in.B.BVALID := true.B
    io.in.B.BRESP := DECERR

    when(io.in.B.BREADY) {
      AWValidReg := false.B
      WValidReg := false.B
      DownstreamAWDone := false.B
      DownstreamWDone := false.B
      writeState := StateWriteIdle
    }
  }

  // -------------------- 读通道 --------------------
  // 每个AXI ID独占一个槽位，直到其RLAST被上游消费。仲裁器当前为IFU/LSU
  // 使用ID 0和1，而包含16项的表让此交叉开关能明确定义每个可表示ID的行为。
  val IncomingReadID = io.in.AR.ARID
  InARReady := ReadSlotState(IncomingReadID) === ReadSlotFree

  when(InARFire) {
    val target = decode(
      io.in.AR.ARADDR,
      io.in.AR.ARLEN,
      io.in.AR.ARSIZE,
      io.in.AR.ARBURST,
      isWrite = false
    )
    ReadTargetReg(IncomingReadID) := target
    ARAddressReg(IncomingReadID) := io.in.AR.ARADDR
    ARLENReg(IncomingReadID) := io.in.AR.ARLEN
    ARSIZEReg(IncomingReadID) := io.in.AR.ARSIZE
    ARBURSTReg(IncomingReadID) := io.in.AR.ARBURST
    ARPROTReg(IncomingReadID) := io.in.AR.ARPROT
    ReadDECERRBeatReg(IncomingReadID) := 0.U
    ReadSlotState(IncomingReadID) := Mux(
      target === TargetInvalid,
      ReadSlotDECERR,
      ReadSlotRequest
    )
  }

  // SoCBus与CLINT拥有独立的AR通道，因此可以在同一周期分别向两个目标各发射
  // 一项等待请求。共享目标的请求每周期发送一项，并且可以在任一R响应到来前
  // 都完成握手。
  val SoCRequestMask = VecInit((0 until ReadSlotCount).map { id =>
    ReadSlotState(id) === ReadSlotRequest &&
    ReadTargetReg(id) === TargetSoCBus
  }).asUInt
  val CLINTRequestMask = VecInit((0 until ReadSlotCount).map { id =>
    ReadSlotState(id) === ReadSlotRequest &&
    ReadTargetReg(id) === TargetCLINT
  }).asUInt
  val SoCARHeld = RegInit(false.B)
  val SoCARHeldID = RegInit(0.U(4.W))
  val CLINTARHeld = RegInit(false.B)
  val CLINTARHeldID = RegInit(0.U(4.W))
  val SoCRequestCandidateID = PriorityEncoder(SoCRequestMask)
  val CLINTRequestCandidateID = PriorityEncoder(CLINTRequestMask)
  val SoCRequestValid = SoCARHeld || SoCRequestMask.orR
  val CLINTRequestValid = CLINTARHeld || CLINTRequestMask.orR
  val SoCRequestID =
    Mux(SoCARHeld, SoCARHeldID, SoCRequestCandidateID)
  val CLINTRequestID =
    Mux(CLINTARHeld, CLINTARHeldID, CLINTRequestCandidateID)
  val SoCRequestFire = SoCRequestValid && io.SoCBus.AR.ARREADY
  val CLINTRequestFire = CLINTRequestValid && io.CLINT.AR.ARREADY

  when(SoCRequestValid) {
    io.SoCBus.AR.ARVALID := true.B
    io.SoCBus.AR.ARID := SoCRequestID
    io.SoCBus.AR.ARADDR := ARAddressReg(SoCRequestID)
    io.SoCBus.AR.ARLEN := ARLENReg(SoCRequestID)
    io.SoCBus.AR.ARSIZE := ARSIZEReg(SoCRequestID)
    io.SoCBus.AR.ARBURST := ARBURSTReg(SoCRequestID)
    io.SoCBus.AR.ARPROT := ARPROTReg(SoCRequestID)
    when(SoCRequestFire) {
      ReadSlotState(SoCRequestID) := ReadSlotResponse
    }
  }
  when(CLINTRequestValid) {
    io.CLINT.AR.ARVALID := true.B
    io.CLINT.AR.ARID := CLINTRequestID
    io.CLINT.AR.ARADDR := ARAddressReg(CLINTRequestID)
    io.CLINT.AR.ARLEN := ARLENReg(CLINTRequestID)
    io.CLINT.AR.ARSIZE := ARSIZEReg(CLINTRequestID)
    io.CLINT.AR.ARBURST := ARBURSTReg(CLINTRequestID)
    io.CLINT.AR.ARPROT := ARPROTReg(CLINTRequestID)
    when(CLINTRequestFire) {
      ReadSlotState(CLINTRequestID) := ReadSlotResponse
    }
  }
  when(!SoCARHeld && SoCRequestValid && !io.SoCBus.AR.ARREADY) {
    SoCARHeld := true.B
    SoCARHeldID := SoCRequestID
  }.elsewhen(SoCRequestFire) {
    SoCARHeld := false.B
  }
  when(!CLINTARHeld && CLINTRequestValid && !io.CLINT.AR.ARREADY) {
    CLINTARHeld := true.B
    CLINTARHeldID := CLINTRequestID
  }.elsewhen(CLINTRequestFire) {
    CLINTARHeld := false.B
  }

  // 单拍响应缓冲既对同时到达的SoCBus/CLINT响应进行仲裁，也保证上游主设备
  // 施加反压时RID/RDATA/RRESP/RLAST保持稳定。
  val RBufferValid = RegInit(false.B)
  val RBufferID = RegInit(0.U(4.W))
  val RBufferData = RegInit(0.U(32.W))
  val RBufferResp = RegInit(OKAY)
  val RBufferLast = RegInit(false.B)
  io.in.R.RID := RBufferID
  io.in.R.RVALID := RBufferValid
  io.in.R.RDATA := RBufferData
  io.in.R.RRESP := RBufferResp
  io.in.R.RLAST := RBufferLast

  val RBufferPop = RBufferValid && io.in.R.RREADY
  val RBufferAvailable = !RBufferValid || RBufferPop
  val SoCResponseExpected =
    (ReadSlotState(io.SoCBus.R.RID) === ReadSlotResponse &&
      ReadTargetReg(io.SoCBus.R.RID) === TargetSoCBus) ||
      (SoCRequestFire && SoCRequestID === io.SoCBus.R.RID)
  val CLINTResponseExpected =
    (ReadSlotState(io.CLINT.R.RID) === ReadSlotResponse &&
      ReadTargetReg(io.CLINT.R.RID) === TargetCLINT) ||
      (CLINTRequestFire && CLINTRequestID === io.CLINT.R.RID)
  val SoCResponseValid = io.SoCBus.R.RVALID && SoCResponseExpected
  val CLINTResponseValid = io.CLINT.R.RVALID && CLINTResponseExpected

  val LocalResponseMask = VecInit((0 until ReadSlotCount).map { id =>
    ReadSlotState(id) === ReadSlotDECERR
  }).asUInt
  val LocalResponseValid = LocalResponseMask.orR
  val LocalResponseID = PriorityEncoder(LocalResponseMask)
  val LocalResponseLast =
    ReadDECERRBeatReg(LocalResponseID) === ARLENReg(LocalResponseID)

  // 因为AXI突发传输长度有限，所以固定源优先级是安全的。仲裁失败的源会看到
  // RREADY为低，并且必须保持其有效载荷。
  val SelectSoC = SoCResponseValid
  val SelectCLINT = !SelectSoC && CLINTResponseValid
  val SelectLocal = !SelectSoC && !SelectCLINT && LocalResponseValid
  io.SoCBus.R.RREADY := RBufferAvailable && SelectSoC
  io.CLINT.R.RREADY := RBufferAvailable && SelectCLINT
  val CaptureSoC = io.SoCBus.R.RVALID && io.SoCBus.R.RREADY
  val CaptureCLINT = io.CLINT.R.RVALID && io.CLINT.R.RREADY
  val CaptureLocal = RBufferAvailable && SelectLocal

  when(CaptureSoC) {
    RBufferValid := true.B
    RBufferID := io.SoCBus.R.RID
    RBufferData := io.SoCBus.R.RDATA
    RBufferResp := io.SoCBus.R.RRESP
    RBufferLast := io.SoCBus.R.RLAST
  }.elsewhen(CaptureCLINT) {
    RBufferValid := true.B
    RBufferID := io.CLINT.R.RID
    RBufferData := io.CLINT.R.RDATA
    RBufferResp := io.CLINT.R.RRESP
    RBufferLast := io.CLINT.R.RLAST
  }.elsewhen(CaptureLocal) {
    RBufferValid := true.B
    RBufferID := LocalResponseID
    RBufferData := 0.U
    RBufferResp := DECERR
    RBufferLast := LocalResponseLast
    when(LocalResponseLast) {
      ReadSlotState(LocalResponseID) := ReadSlotLocalLast
    }.otherwise {
      ReadDECERRBeatReg(LocalResponseID) :=
        ReadDECERRBeatReg(LocalResponseID) + 1.U
    }
  }.elsewhen(RBufferPop) {
    RBufferValid := false.B
  }

  // 本地DECERR状态在数据拍被捕获到RBuffer时推进，而不是在该缓冲数据拍随后
  // 被消费时推进。如果SoC/CLINT响应在本地数据拍出队的周期替换它，再次推进
  // 旧槽位会跳过一个错误响应拍，还可能跳过携带RLAST的ARLEN对应数据拍。

  when(RBufferPop && RBufferLast) {
    ReadSlotState(RBufferID) := ReadSlotFree
  }

  when(io.SoCBus.R.RVALID) {
    assert(SoCResponseExpected, "SoCBus returned an inactive or misrouted RID")
  }
  when(io.CLINT.R.RVALID) {
    assert(CLINTResponseExpected, "CLINT returned an inactive or misrouted RID")
  }
}
