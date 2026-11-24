package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_AXI5Lane.{
  LaneWord,
  SelectStrobe,
  SelectWord
}
import ysyx_26030103_AXI5WriteData.{merge32}
class ysyx_26030103_CLINTIO(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth
) extends ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IDWidth) {
  val TimerIRQ = Output(Bool())
  val mtime = Output(UInt(64.W))
}

/** CLINT 从设备。地址寄存器跟随 AddressWidth；
  * RV64 的 64 位 mtimecmp 写在 32 位总线上拆成两拍，64 位总线上为单拍。
  */
class ysyx_26030103_AXI5CLINTSlave(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth
) extends Module {
  val io = IO(new ysyx_26030103_CLINTIO(AddressWidth, DataWidth, IDWidth))
  private val addressWidth = AddressWidth
  private val dataWidth = DataWidth
  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val mtimeLowAddress = "h0200bff8".U(addressWidth.W) // RISC-V标准CLINT的mtime寄存器
  val mtimeHighAddress = "h0200bffc".U(addressWidth.W)
  val mtimecmpLowAddress = "h02004000".U(addressWidth.W)
  val mtimecmpHighAddress = "h02004004".U(addressWidth.W)
  private val busWide = DataWidth == 64
  private val busWideBit = busWide.B
  private val busNarrowBit = (!busWide).B
  private val busLaneBits = if (busWide) 3 else 2
  val mtime = Module(new ysyx_26030103_mtime)
  val mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  io.TimerIRQ := mtime.io.value >= mtimecmp
  io.mtime := mtime.io.value
  // 日常默认值，都复制粘贴了
  val AWValidReg = RegInit(false.B)
  val wValidReg = RegInit(false.B)
  val wLastReg = RegInit(false.B)
  val bValidReg = RegInit(false.B)
  val AWIDReg = RegInit(0.U(IDWidth.W))
  val AWLengthReg = RegInit(0.U(8.W))
  val AWSizeReg = RegInit(2.U(3.W))
  val wRangeErrorReg = RegInit(false.B)
  val wAddressReg = RegInit(0.U(addressWidth.W))
  val wBeatReg = RegInit(0.U(8.W))
  val wDataReg = RegInit(0.U(dataWidth.W))
  val wStrobeReg = RegInit(0.U((dataWidth / 8).W))
  val lowBurstData = RegInit(0.U(dataWidth.W))
  val lowBurstStrobe = RegInit(0.U((dataWidth / 8).W))
  val bResponseReg = RegInit(OKAY)
  val rValidReg = RegInit(false.B)
  val ARIDReg = RegInit(0.U(IDWidth.W))
  val ARAddressReg = RegInit(0.U(addressWidth.W))
  val ARLengthReg = RegInit(0.U(8.W))
  val ARSizeReg = RegInit(2.U(3.W))
  val ARBurstReg = RegInit(0.U(2.W))
  val ARBeatReg = RegInit(0.U(8.W))
  val ARConfigErrorReg = RegInit(false.B)
  val rDataReg = RegInit(0.U(dataWidth.W))
  val rResponseReg = RegInit(OKAY)
  val rLastReg = RegInit(false.B)
  io.AW.AWREADY := !AWValidReg && !bValidReg
  io.W.WREADY := !wValidReg && !bValidReg
  io.B.BID := AWIDReg
  io.B.BVALID := bValidReg
  io.B.BRESP := bResponseReg
  io.AR.ARREADY := !rValidReg
  io.R.RID := ARIDReg
  io.R.RVALID := rValidReg
  io.R.RDATA := rDataReg
  io.R.RRESP := rResponseReg
  io.R.RLAST := rValidReg && rLastReg
  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val wFire = io.W.WVALID && io.W.WREADY
  val bFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val rFire = io.R.RVALID && io.R.RREADY
  val AWSingleWord =
    io.AW.AWADDR === mtimecmpLowAddress ||
      io.AW.AWADDR === mtimecmpHighAddress
  val aw64Write =
    busWideBit && io.AW.AWLEN === 0.U && io.AW.AWSIZE === 3.U &&
      io.AW.AWADDR === mtimecmpLowAddress
  val AWRangeValid =
    aw64Write ||
      (io.AW.AWLEN === 0.U && AWSingleWord &&
        (busNarrowBit || io.AW.AWSIZE <= 2.U)) ||
      (io.AW.AWLEN === 1.U &&
        io.AW.AWSIZE === 2.U &&
        io.AW.AWADDR === mtimecmpLowAddress)
  when(AWFire) {
    AWIDReg := io.AW.AWID
    AWLengthReg := io.AW.AWLEN
    AWSizeReg := io.AW.AWSIZE
    wRangeErrorReg := !AWRangeValid
    wAddressReg := io.AW.AWADDR
    wBeatReg := 0.U
    AWValidReg := true.B
  }
  when(wFire) {
    wDataReg := io.W.WDATA
    wStrobeReg := io.W.WSTRB
    wValidReg := true.B
    wLastReg := io.W.WLAST
  }
  val wStep = MuxLookup(AWSizeReg, 0.U(addressWidth.W))(
    Seq(
      0.U -> 1.U(addressWidth.W),
      1.U -> 2.U(addressWidth.W),
      2.U -> 4.U(addressWidth.W),
      3.U -> 8.U(addressWidth.W)
    )
  )
  val wDataWide = wDataReg.pad(64)
  val wStrobeWide = wStrobeReg.pad(8)
  val wDataWord = SelectWord(wDataWide, wAddressReg, busWide)
  val wStrobeWord = SelectStrobe(wStrobeWide, wAddressReg, busWide)
  val wBeatAddressValid =
    wAddressReg === mtimecmpLowAddress || wAddressReg === mtimecmpHighAddress
  val wBeatExpectedLast = wBeatReg === AWLengthReg
  // 单拍写沿用旧行为：WSTRB=0 视为非法。多拍写按拍做字节合并，空 WSTRB
  // 只表示该拍不改变目标字，不升级为错误。
  val wBeatError =
    wRangeErrorReg || !wBeatAddressValid ||
      wLastReg =/= wBeatExpectedLast ||
      (AWLengthReg === 0.U && wStrobeReg === 0.U)
  when(AWValidReg && wValidReg && !bValidReg) {
    when(!wBeatError) {
      when(AWLengthReg === 0.U && AWSizeReg === 3.U &&
          wAddressReg === mtimecmpLowAddress) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWide(63, 32), wStrobeWide(7, 4)),
          merge32(mtimecmp(31, 0), wDataWide(31, 0), wStrobeWide(3, 0))
        )
      }.elsewhen(
        AWLengthReg === 1.U && wBeatReg === 0.U &&
          wAddressReg === mtimecmpLowAddress
      ) {
        lowBurstData := wDataReg
        lowBurstStrobe := wStrobeReg
      }.elsewhen(
        AWLengthReg === 1.U && wBeatReg === 1.U &&
          wAddressReg === mtimecmpHighAddress
      ) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWord, wStrobeWord),
          merge32(mtimecmp(31, 0), lowBurstData, lowBurstStrobe)
        )
      }.elsewhen(wAddressReg === mtimecmpLowAddress) {
        mtimecmp := Cat(
          mtimecmp(63, 32),
          merge32(mtimecmp(31, 0), wDataWord, wStrobeWord)
        )
      }.elsewhen(wAddressReg === mtimecmpHighAddress) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWord, wStrobeWord),
          mtimecmp(31, 0)
        )
      }
    }
    when(wLastReg) {
      bResponseReg := Mux(wBeatError, SLVERR, OKAY)
      bValidReg := true.B
      AWValidReg := false.B
      wValidReg := false.B
      wLastReg := false.B
    }.otherwise {
      // 保持 AWValid，后续 W 拍仍属于同一事务；推进字地址。
      wValidReg := false.B
      wLastReg := false.B
      wAddressReg := wAddressReg + wStep
      wBeatReg := wBeatReg + 1.U
    }
  }
  when(bFire) {
    bValidReg := false.B
    wLastReg := false.B
  }
  def isMtime(address: UInt): Bool =
    address === mtimeLowAddress || address === mtimeHighAddress
  def isMtimecmp(address: UInt): Bool =
    address === mtimecmpLowAddress || address === mtimecmpHighAddress
  def readData(address: UInt): UInt =
    Mux(address === mtimeLowAddress, mtime.io.value(31, 0),
      Mux(address === mtimeHighAddress, mtime.io.value(63, 32),
        Mux(address === mtimecmpLowAddress, mtimecmp(31, 0),
          Mux(address === mtimecmpHighAddress, mtimecmp(63, 32), 0.U))))
  def readBeatData(address: UInt, size: UInt): UInt = {
    val word = readData(address)
    val lane = LaneWord(word, address, busWide)
    if (busWide)
      Mux(size === 3.U,
        Cat(readData(address + 4.U), word),
        lane)
    else lane
  }

  val ARStep = MuxLookup(ARSizeReg, 0.U(addressWidth.W))(
    Seq(
      0.U -> 1.U(addressWidth.W),
      1.U -> 2.U(addressWidth.W),
      2.U -> 4.U(addressWidth.W),
      3.U -> 8.U(addressWidth.W)
    )
  )
  val NextARAddress = Mux(ARBurstReg === 1.U, ARAddressReg + ARStep, ARAddressReg)

  when(ARFire) {
    ARIDReg := io.AR.ARID
    ARAddressReg := io.AR.ARADDR
    ARLengthReg := io.AR.ARLEN
    ARSizeReg := io.AR.ARSIZE
    ARBurstReg := io.AR.ARBURST
    ARBeatReg := 0.U
    val configError = io.AR.ARSIZE > busLaneBits.U || io.AR.ARBURST > 1.U
    ARConfigErrorReg := configError
    val addressValid = isMtime(io.AR.ARADDR) || isMtimecmp(io.AR.ARADDR)
    rDataReg := Mux(!configError && addressValid,
      readBeatData(io.AR.ARADDR, io.AR.ARSIZE), 0.U)
    rResponseReg := Mux(!configError && addressValid, OKAY, SLVERR)
    rLastReg := io.AR.ARLEN === 0.U
    rValidReg := true.B
  }
  // 读拍计数不得越过 ARLEN：越界意味着响应拍数计算错误（会多发拍）。
  assert(ARBeatReg <= ARLengthReg, "CLINT read beat counter exceeded ARLEN")
  when(rFire) {
    when(ARBeatReg === ARLengthReg) {
      rValidReg := false.B
      rLastReg := false.B
    }.otherwise {
      val nextBeat = ARBeatReg + 1.U
      val addressValid = isMtime(NextARAddress) || isMtimecmp(NextARAddress)
      ARAddressReg := NextARAddress
      ARBeatReg := nextBeat
      rDataReg := Mux(
        !ARConfigErrorReg && addressValid,
        readBeatData(NextARAddress, ARSizeReg),
        0.U
      )
      rResponseReg := Mux(
        !ARConfigErrorReg && addressValid,
        OKAY,
        SLVERR
      )
      rLastReg := nextBeat === ARLengthReg
      rValidReg := true.B
    }
  }
}
