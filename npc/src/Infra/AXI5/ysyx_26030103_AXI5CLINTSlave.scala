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

/** CLINT 从设备。地址寄存器跟随 AddressWidth；
  * RV64 的 64 位 mtimecmp 写在 32 位总线上拆成两拍，64 位总线上为单拍。
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
  // AXI 字节写：只覆盖 WSTRB 选中的字节，其余字节保留寄存器原值。
  def merge32(old: UInt, data: UInt, strb: UInt): UInt = {
    val Old = old.pad(32)
    val Data = data.pad(32)
    val Strb = strb.pad(4)
    Cat(
      Mux(Strb(3), Data(31, 24), Old(31, 24)),
      Mux(Strb(2), Data(23, 16), Old(23, 16)),
      Mux(Strb(1), Data(15, 8), Old(15, 8)),
      Mux(Strb(0), Data(7, 0), Old(7, 0))
    )
  }
  private val BusWide = DataWidth == 64
  private val BusWideBit = BusWide.B
  private val BusNarrowBit = (!BusWide).B
  private val BusLaneBits = if (BusWide) 3 else 2
  private def LaneWord(Data: UInt, Address: UInt): UInt =
    if (BusWide)
      Mux(Address(2), Cat(Data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), Data(31, 0)))
    else Data(31, 0)
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
  val AWLenReg = RegInit(0.U(8.W))
  val AWSizeReg = RegInit(2.U(3.W))
  val WRangeErrorReg = RegInit(false.B)
  val WAddrReg = RegInit(0.U(AddrW.W))
  val WBeatReg = RegInit(0.U(8.W))
  val WDataReg = RegInit(0.U(DataW.W))
  val WStrbReg = RegInit(0.U((DataW / 8).W))
  val LowBurstData = RegInit(0.U(DataW.W))
  val LowBurstStrb = RegInit(0.U((DataW / 8).W))
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
  val AWSingleWord =
    io.AW.AWADDR === MtimecmpLowAddress ||
      io.AW.AWADDR === MtimecmpHighAddress
  val AW64Write =
    BusWideBit && io.AW.AWLEN === 0.U && io.AW.AWSIZE === 3.U &&
      io.AW.AWADDR === MtimecmpLowAddress
  val AWRangeValid =
    AW64Write ||
      (io.AW.AWLEN === 0.U && AWSingleWord &&
        (BusNarrowBit || io.AW.AWSIZE <= 2.U)) ||
      (io.AW.AWLEN === 1.U &&
        io.AW.AWSIZE === 2.U &&
        io.AW.AWADDR === MtimecmpLowAddress)
  when(AWFire) {
    AWIDReg := io.AW.AWID
    AWLenReg := io.AW.AWLEN
    AWSizeReg := io.AW.AWSIZE
    WRangeErrorReg := !AWRangeValid
    WAddrReg := io.AW.AWADDR
    WBeatReg := 0.U
    AWValidReg := true.B
  }
  when(WFire) {
    WDataReg := io.W.WDATA
    WStrbReg := io.W.WSTRB
    WValidReg := true.B
    WLastReg := io.W.WLAST
  }
  val WStep = MuxLookup(AWSizeReg, 0.U(AddrW.W))(
    Seq(
      0.U -> 1.U(AddrW.W),
      1.U -> 2.U(AddrW.W),
      2.U -> 4.U(AddrW.W),
      3.U -> 8.U(AddrW.W)
    )
  )
  val WDataWide = WDataReg.pad(64)
  val WStrbWide = WStrbReg.pad(8)
  val WDataWord =
    if (BusWide) Mux(WAddrReg(2), WDataWide(63, 32), WDataWide(31, 0))
    else WDataReg(31, 0)
  val WStrbWord =
    if (BusWide) Mux(WAddrReg(2), WStrbWide(7, 4), WStrbWide(3, 0))
    else WStrbReg(3, 0)
  val WBeatAddressValid =
    WAddrReg === MtimecmpLowAddress || WAddrReg === MtimecmpHighAddress
  val WBeatExpectedLast = WBeatReg === AWLenReg
  // 单拍写沿用旧行为：WSTRB=0 视为非法。多拍写按拍做字节合并，空 WSTRB
  // 只表示该拍不改变目标字，不升级为错误。
  val WBeatError =
    WRangeErrorReg || !WBeatAddressValid ||
      WLastReg =/= WBeatExpectedLast ||
      (AWLenReg === 0.U && WStrbReg === 0.U)
  when(AWValidReg && WValidReg && !BValidReg) {
    when(!WBeatError) {
      when(AWLenReg === 0.U && AWSizeReg === 3.U &&
          WAddrReg === MtimecmpLowAddress) {
        Mtimecmp := Cat(
          merge32(Mtimecmp(63, 32), WDataWide(63, 32), WStrbWide(7, 4)),
          merge32(Mtimecmp(31, 0), WDataWide(31, 0), WStrbWide(3, 0))
        )
      }.elsewhen(
        AWLenReg === 1.U && WBeatReg === 0.U &&
          WAddrReg === MtimecmpLowAddress
      ) {
        LowBurstData := WDataReg
        LowBurstStrb := WStrbReg
      }.elsewhen(
        AWLenReg === 1.U && WBeatReg === 1.U &&
          WAddrReg === MtimecmpHighAddress
      ) {
        Mtimecmp := Cat(
          merge32(Mtimecmp(63, 32), WDataWord, WStrbWord),
          merge32(Mtimecmp(31, 0), LowBurstData, LowBurstStrb)
        )
      }.elsewhen(WAddrReg === MtimecmpLowAddress) {
        Mtimecmp := Cat(
          Mtimecmp(63, 32),
          merge32(Mtimecmp(31, 0), WDataWord, WStrbWord)
        )
      }.elsewhen(WAddrReg === MtimecmpHighAddress) {
        Mtimecmp := Cat(
          merge32(Mtimecmp(63, 32), WDataWord, WStrbWord),
          Mtimecmp(31, 0)
        )
      }
    }
    when(WLastReg) {
      BRESPReg := Mux(WBeatError, SLVERR, OKAY)
      BValidReg := true.B
      AWValidReg := false.B
      WValidReg := false.B
      WLastReg := false.B
    }.otherwise {
      // 保持 AWValid，后续 W 拍仍属于同一事务；推进字地址。
      WValidReg := false.B
      WLastReg := false.B
      WAddrReg := WAddrReg + WStep
      WBeatReg := WBeatReg + 1.U
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
    Mux(address === MtimeLowAddress, Mtime.io.value(31, 0),
      Mux(address === MtimeHighAddress, Mtime.io.value(63, 32),
        Mux(address === MtimecmpLowAddress, Mtimecmp(31, 0),
          Mux(address === MtimecmpHighAddress, Mtimecmp(63, 32), 0.U))))
  def ReadBeatData(address: UInt, size: UInt): UInt = {
    val Word = ReadData(address)
    val Lane = LaneWord(Word, address)
    if (BusWide)
      Mux(size === 3.U,
        Cat(ReadData(address + 4.U), Word),
        Lane)
    else Lane
  }

  val ARStep = MuxLookup(ARSizeReg, 0.U(AddrW.W))(
    Seq(
      0.U -> 1.U(AddrW.W),
      1.U -> 2.U(AddrW.W),
      2.U -> 4.U(AddrW.W),
      3.U -> 8.U(AddrW.W)
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
    ARConfigErrorReg := io.AR.ARSIZE > BusLaneBits.U || io.AR.ARBURST > 1.U
    val ConfigError = io.AR.ARSIZE > BusLaneBits.U || io.AR.ARBURST > 1.U
    val AddressValid = IsMtime(io.AR.ARADDR) || IsMtimecmp(io.AR.ARADDR)
    RDataReg := Mux(!ConfigError && AddressValid,
      ReadBeatData(io.AR.ARADDR, io.AR.ARSIZE), 0.U)
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
        ReadBeatData(NextARAddr, ARSizeReg),
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
