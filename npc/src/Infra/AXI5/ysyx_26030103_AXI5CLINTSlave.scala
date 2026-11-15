package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
class ysyx_26030103_CLINTIO(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth
) extends ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IDWidth) {
  val timer_irq = Output(Bool())
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
  // AXI 字节写：只覆盖 WSTRB 选中的字节，其余字节保留寄存器原值。
  def merge32(old: UInt, data: UInt, strb: UInt): UInt = {
    val oldWord = old.pad(32)
    val dataWord = data.pad(32)
    val strbBits = strb.pad(4)
    Cat(
      Mux(strbBits(3), dataWord(31, 24), oldWord(31, 24)),
      Mux(strbBits(2), dataWord(23, 16), oldWord(23, 16)),
      Mux(strbBits(1), dataWord(15, 8), oldWord(15, 8)),
      Mux(strbBits(0), dataWord(7, 0), oldWord(7, 0))
    )
  }
  private val busWide = DataWidth == 64
  private val busWideBit = busWide.B
  private val busNarrowBit = (!busWide).B
  private val busLaneBits = if (busWide) 3 else 2
  private def laneWord(data: UInt, address: UInt): UInt =
    if (busWide)
      Mux(address(2), Cat(data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), data(31, 0)))
    else data(31, 0)
  val mtime = Module(new ysyx_26030103_mtime)
  val mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  io.timer_irq := mtime.io.value >= mtimecmp
  io.mtime := mtime.io.value
  // 日常默认值，都复制粘贴了
  val awValidReg = RegInit(false.B)
  val wValidReg = RegInit(false.B)
  val wLastReg = RegInit(false.B)
  val bValidReg = RegInit(false.B)
  val awIDReg = RegInit(0.U(IDWidth.W))
  val awLenReg = RegInit(0.U(8.W))
  val awSizeReg = RegInit(2.U(3.W))
  val wRangeErrorReg = RegInit(false.B)
  val wAddressReg = RegInit(0.U(addressWidth.W))
  val wBeatReg = RegInit(0.U(8.W))
  val wDataReg = RegInit(0.U(dataWidth.W))
  val wStrobeReg = RegInit(0.U((dataWidth / 8).W))
  val lowBurstData = RegInit(0.U(dataWidth.W))
  val lowBurstStrobe = RegInit(0.U((dataWidth / 8).W))
  val bResponseReg = RegInit(OKAY)
  val rValidReg = RegInit(false.B)
  val arIDReg = RegInit(0.U(IDWidth.W))
  val arAddressReg = RegInit(0.U(addressWidth.W))
  val arLenReg = RegInit(0.U(8.W))
  val arSizeReg = RegInit(2.U(3.W))
  val arBurstReg = RegInit(0.U(2.W))
  val arBeatReg = RegInit(0.U(8.W))
  val arConfigErrorReg = RegInit(false.B)
  val rDataReg = RegInit(0.U(dataWidth.W))
  val rResponseReg = RegInit(OKAY)
  val rLastReg = RegInit(false.B)
  io.AW.AWREADY := !awValidReg && !bValidReg
  io.W.WREADY := !wValidReg && !bValidReg
  io.B.BID := awIDReg
  io.B.BVALID := bValidReg
  io.B.BRESP := bResponseReg
  io.AR.ARREADY := !rValidReg
  io.R.RID := arIDReg
  io.R.RVALID := rValidReg
  io.R.RDATA := rDataReg
  io.R.RRESP := rResponseReg
  io.R.RLAST := rValidReg && rLastReg
  val awFire = io.AW.AWVALID && io.AW.AWREADY
  val wFire = io.W.WVALID && io.W.WREADY
  val bFire = io.B.BVALID && io.B.BREADY
  val arFire = io.AR.ARVALID && io.AR.ARREADY
  val rFire = io.R.RVALID && io.R.RREADY
  val awSingleWord =
    io.AW.AWADDR === mtimecmpLowAddress ||
      io.AW.AWADDR === mtimecmpHighAddress
  val aw64Write =
    busWideBit && io.AW.AWLEN === 0.U && io.AW.AWSIZE === 3.U &&
      io.AW.AWADDR === mtimecmpLowAddress
  val awRangeValid =
    aw64Write ||
      (io.AW.AWLEN === 0.U && awSingleWord &&
        (busNarrowBit || io.AW.AWSIZE <= 2.U)) ||
      (io.AW.AWLEN === 1.U &&
        io.AW.AWSIZE === 2.U &&
        io.AW.AWADDR === mtimecmpLowAddress)
  when(awFire) {
    awIDReg := io.AW.AWID
    awLenReg := io.AW.AWLEN
    awSizeReg := io.AW.AWSIZE
    wRangeErrorReg := !awRangeValid
    wAddressReg := io.AW.AWADDR
    wBeatReg := 0.U
    awValidReg := true.B
  }
  when(wFire) {
    wDataReg := io.W.WDATA
    wStrobeReg := io.W.WSTRB
    wValidReg := true.B
    wLastReg := io.W.WLAST
  }
  val wStep = MuxLookup(awSizeReg, 0.U(addressWidth.W))(
    Seq(
      0.U -> 1.U(addressWidth.W),
      1.U -> 2.U(addressWidth.W),
      2.U -> 4.U(addressWidth.W),
      3.U -> 8.U(addressWidth.W)
    )
  )
  val wDataWide = wDataReg.pad(64)
  val wStrobeWide = wStrobeReg.pad(8)
  val wDataWord =
    if (busWide) Mux(wAddressReg(2), wDataWide(63, 32), wDataWide(31, 0))
    else wDataReg(31, 0)
  val wStrobeWord =
    if (busWide) Mux(wAddressReg(2), wStrobeWide(7, 4), wStrobeWide(3, 0))
    else wStrobeReg(3, 0)
  val wBeatAddressValid =
    wAddressReg === mtimecmpLowAddress || wAddressReg === mtimecmpHighAddress
  val wBeatExpectedLast = wBeatReg === awLenReg
  // 单拍写沿用旧行为：WSTRB=0 视为非法。多拍写按拍做字节合并，空 WSTRB
  // 只表示该拍不改变目标字，不升级为错误。
  val wBeatError =
    wRangeErrorReg || !wBeatAddressValid ||
      wLastReg =/= wBeatExpectedLast ||
      (awLenReg === 0.U && wStrobeReg === 0.U)
  when(awValidReg && wValidReg && !bValidReg) {
    when(!wBeatError) {
      when(awLenReg === 0.U && awSizeReg === 3.U &&
          wAddressReg === mtimecmpLowAddress) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWide(63, 32), wStrobeWide(7, 4)),
          merge32(mtimecmp(31, 0), wDataWide(31, 0), wStrobeWide(3, 0))
        )
      }.elsewhen(
        awLenReg === 1.U && wBeatReg === 0.U &&
          wAddressReg === mtimecmpLowAddress
      ) {
        lowBurstData := wDataReg
        lowBurstStrobe := wStrobeReg
      }.elsewhen(
        awLenReg === 1.U && wBeatReg === 1.U &&
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
      awValidReg := false.B
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
    val lane = laneWord(word, address)
    if (busWide)
      Mux(size === 3.U,
        Cat(readData(address + 4.U), word),
        lane)
    else lane
  }

  val arStep = MuxLookup(arSizeReg, 0.U(addressWidth.W))(
    Seq(
      0.U -> 1.U(addressWidth.W),
      1.U -> 2.U(addressWidth.W),
      2.U -> 4.U(addressWidth.W),
      3.U -> 8.U(addressWidth.W)
    )
  )
  val nextARAddress = Mux(arBurstReg === 1.U, arAddressReg + arStep, arAddressReg)

  when(arFire) {
    arIDReg := io.AR.ARID
    arAddressReg := io.AR.ARADDR
    arLenReg := io.AR.ARLEN
    arSizeReg := io.AR.ARSIZE
    arBurstReg := io.AR.ARBURST
    arBeatReg := 0.U
    arConfigErrorReg := io.AR.ARSIZE > busLaneBits.U || io.AR.ARBURST > 1.U
    val configError = io.AR.ARSIZE > busLaneBits.U || io.AR.ARBURST > 1.U
    val addressValid = isMtime(io.AR.ARADDR) || isMtimecmp(io.AR.ARADDR)
    rDataReg := Mux(!configError && addressValid,
      readBeatData(io.AR.ARADDR, io.AR.ARSIZE), 0.U)
    rResponseReg := Mux(!configError && addressValid, OKAY, SLVERR)
    rLastReg := io.AR.ARLEN === 0.U
    rValidReg := true.B
  }
  // 读拍计数不得越过 ARLEN：越界意味着响应拍数计算错误（会多发拍）。
  assert(arBeatReg <= arLenReg, "CLINT read beat counter exceeded ARLEN")
  when(rFire) {
    when(arBeatReg === arLenReg) {
      rValidReg := false.B
      rLastReg := false.B
    }.otherwise {
      val nextBeat = arBeatReg + 1.U
      val addressValid = isMtime(nextARAddress) || isMtimecmp(nextARAddress)
      arAddressReg := nextARAddress
      arBeatReg := nextBeat
      rDataReg := Mux(
        !arConfigErrorReg && addressValid,
        readBeatData(nextARAddress, arSizeReg),
        0.U
      )
      rResponseReg := Mux(
        !arConfigErrorReg && addressValid,
        OKAY,
        SLVERR
      )
      rLastReg := nextBeat === arLenReg
      rValidReg := true.B
    }
  }
}
