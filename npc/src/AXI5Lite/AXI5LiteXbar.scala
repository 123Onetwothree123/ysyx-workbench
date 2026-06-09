package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteXbar(AddressWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    // 先做过笔记，因为这个是接裁决器的，裁决器是master，所以这里的对反
    val in = Flipped(new AXI5LiteIO(AddressWidth))
    val SRAM = new AXI5LiteIO(AddressWidth)
    val UART = new AXI5LiteIO(AddressWidth)
  })
  // 我真的是烦死了ARM的命名规则
  val OKAY = "b00".U(2.W)
  val DECERR = "b11".U(2.W)
  // 不按讲义来了，按实际AM的128MiB内存来
  val UARTBase = "h10000000".U(32.W)
  val UARTEnd = "h10001000".U(32.W)
  val SRAMBase = "h80000000".U(32.W)
  val SRAMEnd = "h88000000".U(32.W)
  val TargetSRAM = 0.U(2.W)
  val TargetUART = 1.U(2.W)
  val TargetDECERR = 2.U(2.W)
  def IsSRAM(address: UInt): Bool = {
    address >= SRAMBase && address < SRAMEnd
  }
  def IsUART(address: UInt): Bool = {
    address >= UARTBase && address < UARTEnd
  }
  def decode(address: UInt): UInt = {
    Mux(
      IsSRAM(address),
      TargetSRAM,
      Mux(IsUART(address), TargetUART, TargetDECERR)
    )
  }
  val states = Enum(8)
  val StateIdle = states(0)
  val StateReadRequest = states(1)
  val StateReadResponse = states(2)
  val StateReadDECERR = states(3)
  val StateWriteCollect = states(4)
  val StateWriteRequest = states(5)
  val StateWriteResponse = states(6)
  val StateWriteDECERR = states(7)
  val state = RegInit(StateIdle)
  // 读请求被接收后，R回来时已经没有地址了
  // 所以必须记住这次读请求被发给哪个下游
  val ReadTargetReg = RegInit(TargetDECERR)
  val ARAddressReg = RegInit(0.U(32.W))
  val ARPROTReg = RegInit(0.U(3.W))
  val AWValidReg = RegInit(false.B)
  val WValidReg = RegInit(false.B)
  val AWAddressReg = RegInit(0.U(32.W))
  val AWPROTReg = RegInit(0.U(3.W))
  val WDataReg = RegInit(0.U(32.W))
  val WSTRBReg = RegInit(0.U(4.W))
  // 写响应B通道回来时也没有地址，所以要记住写请求目标
  val WriteTargetReg = RegInit(TargetDECERR)
  val DownstreamAWDone = RegInit(false.B)
  val DownstreamWDone = RegInit(false.B)
  // 这段是AI写的
  // 用 Wire 控制上游 ready，避免直接读自己驱动的输出端口。
  val InAWReady = WireDefault(false.B)
  val InWReady = WireDefault(false.B)
  val InARReady = WireDefault(false.B)
  io.in.AW.AWREADY := InAWReady
  io.in.W.WREADY := InWReady
  io.in.AR.ARREADY := InARReady

  // 默认值
  io.in.B.BVALID := false.B
  io.in.B.BRESP := OKAY
  io.in.R.RVALID := false.B
  io.in.R.RDATA := 0.U
  io.in.R.RRESP := OKAY
  io.SRAM.AW.AWVALID := false.B
  io.SRAM.AW.AWADDR := 0.U
  io.SRAM.AW.AWPROT := 0.U
  io.SRAM.W.WVALID := false.B
  io.SRAM.W.WDATA := 0.U
  io.SRAM.W.WSTRB := 0.U
  io.SRAM.B.BREADY := false.B
  io.SRAM.AR.ARVALID := false.B
  io.SRAM.AR.ARADDR := 0.U
  io.SRAM.AR.ARPROT := 0.U
  io.SRAM.R.RREADY := false.B
  io.UART.AW.AWVALID := false.B
  io.UART.AW.AWADDR := 0.U
  io.UART.AW.AWPROT := 0.U
  io.UART.W.WVALID := false.B
  io.UART.W.WDATA := 0.U
  io.UART.W.WSTRB := 0.U
  io.UART.B.BREADY := false.B
  io.UART.AR.ARVALID := false.B
  io.UART.AR.ARADDR := 0.U
  io.UART.AR.ARPROT := 0.U
  io.UART.R.RREADY := false.B
  val InAWFire = io.in.AW.AWVALID && InAWReady
  val InWFire = io.in.W.WVALID && InWReady
  val InARFire = io.in.AR.ARVALID && InARReady
  val WriteTargetAfterAW =
    Mux(InAWFire, decode(io.in.AW.AWADDR), WriteTargetReg)
  val AWCollected = AWValidReg || InAWFire
  val WCollected = WValidReg || InWFire
  when(InAWFire) {
    AWAddressReg := io.in.AW.AWADDR
    AWPROTReg := io.in.AW.AWPROT
    WriteTargetReg := decode(io.in.AW.AWADDR)
    AWValidReg := true.B
  }
  when(InWFire) {
    WDataReg := io.in.W.WDATA
    WSTRBReg := io.in.W.WSTRB
    WValidReg := true.B
  }
  // 写不动了剩下状态机代码都是ai写的
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
        when(WriteTargetAfterAW === TargetDECERR) {
          state := StateWriteDECERR
        }.otherwise {
          state := StateWriteRequest
        }
      }.otherwise {
        state := StateWriteCollect
      }
    }.otherwise { // 没有写请求时，才接收读请求
      InARReady := true.B
      when(InARFire) {
        val target = decode(io.in.AR.ARADDR)

        ARAddressReg := io.in.AR.ARADDR
        ARPROTReg := io.in.AR.ARPROT
        ReadTargetReg := target

        when(target === TargetDECERR) {
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
      when(WriteTargetAfterAW === TargetDECERR) {
        state := StateWriteDECERR
      }.otherwise {
        state := StateWriteRequest
      }
    }
  }.elsewhen(state === StateWriteRequest) {
    // AW和W都收齐后，根据写地址译码结果转发到对应下游
    when(WriteTargetReg === TargetSRAM) {
      val SendAW = !DownstreamAWDone
      val SendW = !DownstreamWDone

      io.SRAM.AW.AWVALID := SendAW
      io.SRAM.AW.AWADDR := AWAddressReg
      io.SRAM.AW.AWPROT := AWPROTReg

      io.SRAM.W.WVALID := SendW
      io.SRAM.W.WDATA := WDataReg
      io.SRAM.W.WSTRB := WSTRBReg

      val AWFire = SendAW && io.SRAM.AW.AWREADY
      val WFire = SendW && io.SRAM.W.WREADY

      when(AWFire) {
        DownstreamAWDone := true.B
      }
      when(WFire) {
        DownstreamWDone := true.B
      }

      // 下游AW/W都握手完成后，进入B响应阶段
      when((DownstreamAWDone || AWFire) && (DownstreamWDone || WFire)) {
        state := StateWriteResponse
      }
    }.elsewhen(WriteTargetReg === TargetUART) {
      val SendAW = !DownstreamAWDone
      val SendW = !DownstreamWDone

      io.UART.AW.AWVALID := SendAW
      io.UART.AW.AWADDR := AWAddressReg
      io.UART.AW.AWPROT := AWPROTReg

      io.UART.W.WVALID := SendW
      io.UART.W.WDATA := WDataReg
      io.UART.W.WSTRB := WSTRBReg

      val AWFire = SendAW && io.UART.AW.AWREADY
      val WFire = SendW && io.UART.W.WREADY

      when(AWFire) {
        DownstreamAWDone := true.B
      }
      when(WFire) {
        DownstreamWDone := true.B
      }

      // 下游AW/W都握手完成后，进入B响应阶段
      when((DownstreamAWDone || AWFire) && (DownstreamWDone || WFire)) {
        state := StateWriteResponse
      }
    }.otherwise {
      state := StateWriteDECERR
    }
  }.elsewhen(state === StateWriteResponse) {
    // B响应回来时没有地址，所以要根据之前保存的WriteTargetReg选择下游
    when(WriteTargetReg === TargetSRAM) {
      io.in.B.BVALID := io.SRAM.B.BVALID
      io.in.B.BRESP := io.SRAM.B.BRESP
      io.SRAM.B.BREADY := io.in.B.BREADY

      when(io.SRAM.B.BVALID && io.in.B.BREADY) {
        AWValidReg := false.B
        WValidReg := false.B
        DownstreamAWDone := false.B
        DownstreamWDone := false.B
        state := StateIdle
      }
    }.elsewhen(WriteTargetReg === TargetUART) {
      io.in.B.BVALID := io.UART.B.BVALID
      io.in.B.BRESP := io.UART.B.BRESP
      io.UART.B.BREADY := io.in.B.BREADY

      when(io.UART.B.BVALID && io.in.B.BREADY) {
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
    // 写地址没有命中SRAM/UART，Xbar自己返回DECERR
    // 这里不访问任何下游设备
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
    when(ReadTargetReg === TargetSRAM) {
      io.SRAM.AR.ARVALID := true.B
      io.SRAM.AR.ARADDR := ARAddressReg
      io.SRAM.AR.ARPROT := ARPROTReg

      when(io.SRAM.AR.ARREADY) {
        state := StateReadResponse
      }
    }.elsewhen(ReadTargetReg === TargetUART) {
      io.UART.AR.ARVALID := true.B
      io.UART.AR.ARADDR := ARAddressReg
      io.UART.AR.ARPROT := ARPROTReg

      when(io.UART.AR.ARREADY) {
        state := StateReadResponse
      }
    }.otherwise {
      state := StateReadDECERR
    }
  }.elsewhen(state === StateReadResponse) {
    // R响应回来时没有地址，所以根据之前保存的ReadTargetReg选择下游
    when(ReadTargetReg === TargetSRAM) {
      io.in.R.RVALID := io.SRAM.R.RVALID
      io.in.R.RDATA := io.SRAM.R.RDATA
      io.in.R.RRESP := io.SRAM.R.RRESP
      io.SRAM.R.RREADY := io.in.R.RREADY

      when(io.SRAM.R.RVALID && io.in.R.RREADY) {
        state := StateIdle
      }
    }.elsewhen(ReadTargetReg === TargetUART) {
      io.in.R.RVALID := io.UART.R.RVALID
      io.in.R.RDATA := io.UART.R.RDATA
      io.in.R.RRESP := io.UART.R.RRESP
      io.UART.R.RREADY := io.in.R.RREADY

      when(io.UART.R.RVALID && io.in.R.RREADY) {
        state := StateIdle
      }
    }.otherwise {
      state := StateReadDECERR
    }
  }.elsewhen(state === StateReadDECERR) {
    // 读地址没有命中SRAM/UART，Xbar自己返回DECERR
    // RDATA没有实际意义，固定返回0
    io.in.R.RVALID := true.B
    io.in.R.RDATA := 0.U
    io.in.R.RRESP := DECERR

    when(io.in.R.RREADY) {
      state := StateIdle
    }
  }
}
