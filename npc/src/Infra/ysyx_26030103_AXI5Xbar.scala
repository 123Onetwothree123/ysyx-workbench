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
  def decode(address: UInt, len: UInt, size: UInt, burst: UInt): UInt = {
    val ConfigSupported = size <= 2.U && (burst === 0.U || burst === 1.U)
    Mux(
      !ConfigSupported,
      TargetInvalid,
      Mux(
        IsCLINT(address, len, size, burst),
        TargetCLINT,
        Mux(
          InAnyRegion(address, len, size, burst, PMARegions),
          TargetSoCBus,
          TargetInvalid
        )
      )
    )
  }
  val states = Enum(9)
  val StateIdle = states(0)
  val StateReadRequest = states(1)
  val StateReadResponse = states(2)
  val StateReadDECERR = states(3)
  val StateWriteCollect = states(4)
  val StateWriteRequest = states(5)
  val StateWriteResponse = states(6)
  val StateWriteDECERR = states(7)
  // 不支持转发的写burst仍需吃完全部W beat，之后再返回DECERR。
  val StateWriteDrain = states(8)
  val state = RegInit(StateIdle)
  // 读请求被接收后，R回来时已经没有地址了
  // 所以必须记住这次读请求被发给哪个下游
  val ReadTargetReg = RegInit(TargetInvalid)
  // AR buffering: accept AR even when xbar is not idle
  val ARPending = RegInit(false.B)
  val ARTargetPending = RegInit(TargetInvalid)
  val ARAddrPending = RegInit(0.U(32.W))
  val ARLenPending = RegInit(0.U(8.W))
  val ARIDReg = RegInit(0.U(4.W))
  val ARAddressReg = RegInit(0.U(32.W))
  val ARLENReg = RegInit(0.U(8.W))
  val ARSIZEReg = RegInit(2.U(3.W))
  val ARBURSTReg = RegInit(0.U(2.W))
  val ARPROTReg = RegInit(0.U(3.W))
  val ReadDECERRBeatReg = RegInit(0.U(8.W))
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
  // AR pending buffer: accept AR even when xbar is not idle
  // 这段是AI写的
  // 用 Wire 控制上游 ready，避免直接读自己驱动的输出端口。
  val InAWReady = WireDefault(false.B)
  val InWReady = WireDefault(false.B)
  val InARReady = WireDefault(false.B)
  val HasWriteReq =
    io.in.AW.AWVALID || io.in.W.WVALID || AWValidReg || WValidReg
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
  val WriteTargetAfterAW =
    Mux(
      InAWFire,
      decode(
        io.in.AW.AWADDR,
        io.in.AW.AWLEN,
        io.in.AW.AWSIZE,
        io.in.AW.AWBURST
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
      io.in.AW.AWBURST
    )
    AWValidReg := true.B
  }
  // Idle/Collect阶段只缓存首个W beat；进入转发阶段后其余beat直接流过。
  when(InWFire && (state === StateIdle || state === StateWriteCollect)) {
    WDataReg := io.in.W.WDATA
    WSTRBReg := io.in.W.WSTRB
    WLASTReg := io.in.W.WLAST
    WValidReg := true.B
  }
  when(state === StateIdle) {
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
          state := Mux(FirstWLastAfterW, StateWriteDECERR, StateWriteDrain)
        }.otherwise {
          state := StateWriteRequest
        }
      }.otherwise {
        state := StateWriteCollect
      }
    }.otherwise { // 没有写请求时，读请求在这里直接处理
      InARReady := true.B
      when(InARFire) {
        val target = decode(
          io.in.AR.ARADDR,
          io.in.AR.ARLEN,
          io.in.AR.ARSIZE,
          io.in.AR.ARBURST
        )
        ARIDReg := io.in.AR.ARID
        ARAddressReg := io.in.AR.ARADDR
        ARLENReg := io.in.AR.ARLEN
        ARSIZEReg := io.in.AR.ARSIZE
        ARBURSTReg := io.in.AR.ARBURST
        ARPROTReg := io.in.AR.ARPROT
        ReadDECERRBeatReg := 0.U
        ReadTargetReg := target
        when(target === TargetInvalid) {
          state := StateReadDECERR
        }.otherwise {
          state := StateReadRequest
        }
      }
    }
  }.elsewhen(state === StateWriteCollect) {
    // 已经开始处理写事务，但是AW和W还没有都收到
    // 例如AW先到、W后到，或者W先到、AW后到
    InAWReady := !AWValidReg
    InWReady := !WValidReg

    when(AWCollected && WCollected) {
      DownstreamAWDone := false.B
      DownstreamWDone := false.B
      when(WriteCannotForward) {
        WValidReg := false.B
        state := Mux(FirstWLastAfterW, StateWriteDECERR, StateWriteDrain)
      }.otherwise {
        state := StateWriteRequest
      }
    }
  }.elsewhen(state === StateWriteRequest) {
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
        state := StateWriteResponse
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
        state := StateWriteResponse
      }
    }.otherwise {
      state := Mux(WValidReg && WLASTReg, StateWriteDECERR, StateWriteDrain)
    }
  }.elsewhen(state === StateWriteDrain) {
    // 地址无效或本地CLINT不支持burst：首beat已在收集级被接受，
    // 继续吞掉剩余beat，避免上游停在W通道而永远等不到错误响应。
    InWReady := true.B
    when(io.in.W.WVALID && io.in.W.WLAST) {
      state := StateWriteDECERR
    }
  }.elsewhen(state === StateWriteResponse) {
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
        state := StateIdle
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
        state := StateIdle
      }
    }.otherwise {
      state := StateWriteDECERR
    }
  }.elsewhen(state === StateWriteDECERR) {
    // 地址空洞和不支持的burst在CPU内部完成，不访问任何下游设备。
    io.in.B.BID := AWIDReg
    io.in.B.BVALID := true.B
    io.in.B.BRESP := DECERR

    when(io.in.B.BREADY) {
      AWValidReg := false.B
      WValidReg := false.B
      DownstreamAWDone := false.B
      DownstreamWDone := false.B
      state := StateIdle
    }
  }.elsewhen(state === StateReadRequest) {
    // 把读地址AR转发到对应下游
    when(ReadTargetReg === TargetSoCBus) {
      io.SoCBus.AR.ARVALID := true.B
      io.SoCBus.AR.ARID := ARIDReg
      io.SoCBus.AR.ARADDR := ARAddressReg
      io.SoCBus.AR.ARLEN := ARLENReg
      io.SoCBus.AR.ARSIZE := ARSIZEReg
      io.SoCBus.AR.ARBURST := ARBURSTReg
      io.SoCBus.AR.ARPROT := ARPROTReg

      when(io.SoCBus.AR.ARREADY) {
        state := StateReadResponse
      }
    }.elsewhen(ReadTargetReg === TargetCLINT) {
      io.CLINT.AR.ARVALID := true.B
      io.CLINT.AR.ARID := ARIDReg
      io.CLINT.AR.ARADDR := ARAddressReg
      io.CLINT.AR.ARLEN := ARLENReg
      io.CLINT.AR.ARSIZE := ARSIZEReg
      io.CLINT.AR.ARBURST := ARBURSTReg
      io.CLINT.AR.ARPROT := ARPROTReg

      when(io.CLINT.AR.ARREADY) {
        state := StateReadResponse
      }
    }.otherwise {
      state := StateReadDECERR
    }
  }.elsewhen(state === StateReadResponse) {
    // R响应回来时没有地址，所以根据之前保存的ReadTargetReg选择下游
    when(ReadTargetReg === TargetSoCBus) {
      io.in.R.RID := io.SoCBus.R.RID
      io.in.R.RVALID := io.SoCBus.R.RVALID
      io.in.R.RDATA := io.SoCBus.R.RDATA
      io.in.R.RRESP := io.SoCBus.R.RRESP
      io.in.R.RLAST := io.SoCBus.R.RLAST
      io.SoCBus.R.RREADY := io.in.R.RREADY

      when(io.SoCBus.R.RVALID && io.in.R.RREADY && io.SoCBus.R.RLAST) {
        state := StateIdle
      }
    }.elsewhen(ReadTargetReg === TargetCLINT) {
      io.in.R.RID := io.CLINT.R.RID
      io.in.R.RVALID := io.CLINT.R.RVALID
      io.in.R.RDATA := io.CLINT.R.RDATA
      io.in.R.RRESP := io.CLINT.R.RRESP
      io.in.R.RLAST := io.CLINT.R.RLAST
      io.CLINT.R.RREADY := io.in.R.RREADY

      when(io.CLINT.R.RVALID && io.in.R.RREADY && io.CLINT.R.RLAST) {
        state := StateIdle
      }
    }.otherwise {
      state := StateReadDECERR
    }
  }.elsewhen(state === StateReadDECERR) {
    // 即使错误请求是burst，也必须返回ARLEN+1拍，避免合法AXI master
    // 因等待RLAST而挂死。RDATA没有实际意义，固定返回0。
    io.in.R.RID := ARIDReg
    io.in.R.RVALID := true.B
    io.in.R.RDATA := 0.U
    io.in.R.RRESP := DECERR
    io.in.R.RLAST := ReadDECERRBeatReg === ARLENReg

    when(io.in.R.RREADY) {
      when(ReadDECERRBeatReg === ARLENReg) {
        state := StateIdle
      }.otherwise {
        ReadDECERRBeatReg := ReadDECERRBeatReg + 1.U
      }
    }
  }
}
