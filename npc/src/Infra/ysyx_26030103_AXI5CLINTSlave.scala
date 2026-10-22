package ysyx_26030103.infra
import chisel3._
import chisel3.util._
class ysyx_26030103_CLINTIO(
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IdWidth) {
  val timer_irq = Output(Bool())
  val mtime = Output(UInt(64.W))
}

/** CLINT 从设备。地址寄存器跟随 AddressWidth，读数据仍为 32 位；
  * RV64 通过两次 32 位访问读写 mtime/mtimecmp。
  */
class ysyx_26030103_AXI5CLINTSlave(
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends Module {
  val io = IO(new ysyx_26030103_CLINTIO(AddressWidth, DataWidth, IdWidth))
  private val AddrW = AddressWidth
  private val DataW = DataWidth
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool
  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val MtimeLowAddress = "h0200bff8".U(AddrW.W) // RISC-V标准CLINT的mtime寄存器
  val MtimeHighAddress = "h0200bffc".U(AddrW.W)
  val MtimecmpLowAddress = "h02004000".U(AddrW.W)
  val MtimecmpHighAddress = "h02004004".U(AddrW.W)
  val Mtime = Module(new ysyx_26030103_mtime)
  val Mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  io.timer_irq := Mtime.io.value >= Mtimecmp
  io.mtime := Mtime.io.value
  // 日常默认值，都复制粘贴了
  val AWValidReg = RegInit(false.B)
  val WValidReg = RegInit(false.B)
  val WLastReg = RegInit(false.B)
  val BValidReg = RegInit(false.B)
  val AWIDReg = RegInit(0.U(IdWidth.W))
  val AWAddrReg = RegInit(0.U(AddrW.W))
  val WDataReg = RegInit(0.U(DataW.W))
  val WStrbReg = RegInit(0.U((DataW / 8).W))
  val BRESPReg = RegInit(OKAY)
  val RValidReg = RegInit(false.B)
  val ARIDReg = RegInit(0.U(IdWidth.W))
  val ARAddrReg = RegInit(0.U(AddrW.W))
  val ARLenReg = RegInit(0.U(8.W))
  val ARSizeReg = RegInit(2.U(3.W))
  val ARBurstReg = RegInit(0.U(2.W))
  val ARBeatReg = RegInit(0.U(8.W))
  val ARConfigErrorReg = RegInit(false.B)
  val RDataReg = RegInit(0.U(DataW.W))
  val RRESPReg = RegInit(OKAY)
  val RLastReg = RegInit(false.B)
  io.AW.AWREADY := !AWValidReg && !BValidReg
  io.W.WREADY := !WValidReg && !BValidReg
  io.B.BID := AWIDReg
  io.B.BVALID := BValidReg
  io.B.BRESP := BRESPReg
  io.AR.ARREADY := !RValidReg
  io.R.RID := ARIDReg
  io.R.RVALID := RValidReg
  io.R.RDATA := RDataReg
  io.R.RRESP := RRESPReg
  io.R.RLAST := RValidReg && RLastReg
  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val WFire = io.W.WVALID && io.W.WREADY
  val BFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val RFire = io.R.RVALID && io.R.RREADY
  when(AWFire) {
    AWIDReg := io.AW.AWID
    AWAddrReg := io.AW.AWADDR
    AWValidReg := true.B
  }
  when(WFire) {
    WDataReg := io.W.WDATA
    WStrbReg := io.W.WSTRB
    WValidReg := true.B
    WLastReg := io.W.WLAST
  }
  when(AWValidReg && WValidReg && !BValidReg) {
    when(WLastReg) {
      when(AWAddrReg === MtimecmpLowAddress && WStrbReg =/= 0.U) {
        Mtimecmp := Cat(Mtimecmp(63, 32), WDataReg)
        BRESPReg := OKAY
      }.elsewhen(AWAddrReg === MtimecmpHighAddress && WStrbReg =/= 0.U) {
        Mtimecmp := Cat(WDataReg, Mtimecmp(31, 0))
        BRESPReg := OKAY
      }.otherwise {
        BRESPReg := SLVERR
      }
      BValidReg := true.B
      AWValidReg := false.B
      WValidReg := false.B
      WLastReg := false.B
    }.otherwise {
      // 多拍写事务逐拍排空。保持AWValid，使后续W数据拍仍属于同一事务。
      WValidReg := false.B
      WLastReg := false.B
    }
  }
  when(BFire) {
    BValidReg := false.B
    WLastReg := false.B
  }
  def IsMtime(address: UInt): Bool =
    address === MtimeLowAddress || address === MtimeHighAddress
  def IsMtimecmp(address: UInt): Bool =
    address === MtimecmpLowAddress || address === MtimecmpHighAddress
  def ReadData(address: UInt): UInt =
    Mux(address === MtimeLowAddress, Mtime.io.rdata,
      Mux(address === MtimeHighAddress, Mtime.io.rdata,
        Mux(address === MtimecmpLowAddress, Mtimecmp(31, 0),
          Mux(address === MtimecmpHighAddress, Mtimecmp(63, 32), 0.U))))

  val ARStep = MuxLookup(ARSizeReg, 0.U(AddrW.W))(
    Seq(
      0.U -> 1.U(AddrW.W),
      1.U -> 2.U(AddrW.W),
      2.U -> 4.U(AddrW.W)
    )
  )
  val NextARAddr = Mux(ARBurstReg === 1.U, ARAddrReg + ARStep, ARAddrReg)
  // 选择其响应在此时钟沿被寄存的地址。数据本身也必须被捕获，因为主设备
  // 可以保持RREADY为低，而mtime会在此期间持续变化。
  val ResponseAddress = Mux(ARFire, io.AR.ARADDR, NextARAddr)
  Mtime.io.SelectHigh := ResponseAddress === MtimeHighAddress

  when(ARFire) {
    ARIDReg := io.AR.ARID
    ARAddrReg := io.AR.ARADDR
    ARLenReg := io.AR.ARLEN
    ARSizeReg := io.AR.ARSIZE
    ARBurstReg := io.AR.ARBURST
    ARBeatReg := 0.U
    ARConfigErrorReg := io.AR.ARSIZE > 2.U || io.AR.ARBURST > 1.U
    val ConfigError = io.AR.ARSIZE > 2.U || io.AR.ARBURST > 1.U
    val AddressValid = IsMtime(io.AR.ARADDR) || IsMtimecmp(io.AR.ARADDR)
    RDataReg := Mux(!ConfigError && AddressValid, ReadData(io.AR.ARADDR), 0.U)
    RRESPReg := Mux(!ConfigError && AddressValid, OKAY, SLVERR)
    RLastReg := io.AR.ARLEN === 0.U
    RValidReg := true.B
  }
  // 读拍计数不得越过 ARLEN：越界意味着响应拍数计算错误（会多发拍）。
  assert(ARBeatReg <= ARLenReg, "CLINT read beat counter exceeded ARLEN")
  when(RFire) {
    when(ARBeatReg === ARLenReg) {
      RValidReg := false.B
      RLastReg := false.B
    }.otherwise {
      val NextBeat = ARBeatReg + 1.U
      val AddressValid = IsMtime(NextARAddr) || IsMtimecmp(NextARAddr)
      ARAddrReg := NextARAddr
      ARBeatReg := NextBeat
      RDataReg := Mux(
        !ARConfigErrorReg && AddressValid,
        ReadData(NextARAddr),
        0.U
      )
      RRESPReg := Mux(
        !ARConfigErrorReg && AddressValid,
        OKAY,
        SLVERR
      )
      RLastReg := NextBeat === ARLenReg
      RValidReg := true.B
    }
  }
}
