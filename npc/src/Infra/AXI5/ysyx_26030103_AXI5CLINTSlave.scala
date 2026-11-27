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
import ysyx_26030103_AXI5Burst.{beatBytes}
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
) extends Module with ysyx_26030103_AXI5ChannelFire {
  val io = IO(new ysyx_26030103_CLINTIO(AddressWidth, DataWidth, IDWidth))
  private val addressWidth = AddressWidth
  private val dataWidth = DataWidth
  val OKAY = ysyx_26030103_AXI5Resp.OKAY
  val SLVERR = ysyx_26030103_AXI5Resp.SLVERR
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
  val awRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5AWChannelRegs(addressWidth, IDWidth))
  val wRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5WChannelRegs(dataWidth))
  val bRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5BChannelRegs)
  val rRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5RChannelRegs(dataWidth, IDWidth))
  val AWBurstShape = RegInit(
    ysyx_26030103_AXI5BurstShape.Default(addressWidth, IDWidth))
  val wRangeErrorReg = RegInit(false.B)
  val wBeatReg = RegInit(0.U(8.W))
  val lowBurstData = RegInit(0.U(dataWidth.W))
  val lowBurstStrobe = RegInit(0.U((dataWidth / 8).W))
  val ARBurstShape = RegInit(
    ysyx_26030103_AXI5BurstShape.Default(addressWidth, IDWidth))
  val ARBeatReg = RegInit(0.U(8.W))
  val ARConfigErrorReg = RegInit(false.B)
  io.AW.AWREADY := !awRegs.valid && !bRegs.valid
  io.W.WREADY := !wRegs.valid && !bRegs.valid
  io.B.BID := awRegs.id
  io.B.BVALID := bRegs.valid
  io.B.BRESP := bRegs.response
  io.AR.ARREADY := !rRegs.valid
  io.R.RID := rRegs.id
  io.R.RVALID := rRegs.valid
  io.R.RDATA := rRegs.data
  io.R.RRESP := rRegs.response
  io.R.RLAST := rRegs.valid && rRegs.last
  val AWFire = AwFire(io.AW.AWVALID, io.AW.AWREADY)
  val wFire = WFire(io.W.WVALID, io.W.WREADY)
  val bFire = BFire(io.B.BVALID, io.B.BREADY)
  val ARFire = ArFire(io.AR.ARVALID, io.AR.ARREADY)
  val rFire = RFire(io.R.RVALID, io.R.RREADY)
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
    awRegs.id := io.AW.AWID
    AWBurstShape.length := io.AW.AWLEN
    AWBurstShape.size := io.AW.AWSIZE
    wRangeErrorReg := !AWRangeValid
    AWBurstShape.address := io.AW.AWADDR
    wBeatReg := 0.U
    awRegs.valid := true.B
  }
  when(wFire) {
    wRegs.data := io.W.WDATA
    wRegs.strobe := io.W.WSTRB
    wRegs.valid := true.B
    wRegs.last := io.W.WLAST
  }
  val wStep = beatBytes(AWBurstShape.size, addressWidth)
  val wDataWide = wRegs.data.pad(64)
  val wStrobeWide = wRegs.strobe.pad(8)
  val wDataWord = SelectWord(wDataWide, AWBurstShape.address, busWide)
  val wStrobeWord = SelectStrobe(wStrobeWide, AWBurstShape.address, busWide)
  val wBeatAddressValid =
    AWBurstShape.address === mtimecmpLowAddress || AWBurstShape.address === mtimecmpHighAddress
  val wBeatExpectedLast = wBeatReg === AWBurstShape.length
  // 单拍写沿用旧行为：WSTRB=0 视为非法。多拍写按拍做字节合并，空 WSTRB
  // 只表示该拍不改变目标字，不升级为错误。
  val wBeatError =
    wRangeErrorReg || !wBeatAddressValid ||
      wRegs.last =/= wBeatExpectedLast ||
      (AWBurstShape.length === 0.U && wRegs.strobe === 0.U)
  when(awRegs.valid && wRegs.valid && !bRegs.valid) {
    when(!wBeatError) {
      when(AWBurstShape.length === 0.U && AWBurstShape.size === 3.U &&
          AWBurstShape.address === mtimecmpLowAddress) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWide(63, 32), wStrobeWide(7, 4)),
          merge32(mtimecmp(31, 0), wDataWide(31, 0), wStrobeWide(3, 0))
        )
      }.elsewhen(
        AWBurstShape.length === 1.U && wBeatReg === 0.U &&
          AWBurstShape.address === mtimecmpLowAddress
      ) {
        lowBurstData := wRegs.data
        lowBurstStrobe := wRegs.strobe
      }.elsewhen(
        AWBurstShape.length === 1.U && wBeatReg === 1.U &&
          AWBurstShape.address === mtimecmpHighAddress
      ) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWord, wStrobeWord),
          merge32(mtimecmp(31, 0), lowBurstData, lowBurstStrobe)
        )
      }.elsewhen(AWBurstShape.address === mtimecmpLowAddress) {
        mtimecmp := Cat(
          mtimecmp(63, 32),
          merge32(mtimecmp(31, 0), wDataWord, wStrobeWord)
        )
      }.elsewhen(AWBurstShape.address === mtimecmpHighAddress) {
        mtimecmp := Cat(
          merge32(mtimecmp(63, 32), wDataWord, wStrobeWord),
          mtimecmp(31, 0)
        )
      }
    }
    when(wRegs.last) {
      bRegs.response := Mux(wBeatError, SLVERR, OKAY)
      bRegs.valid := true.B
      awRegs.valid := false.B
      wRegs.valid := false.B
      wRegs.last := false.B
    }.otherwise {
      // 保持 AWValid，后续 W 拍仍属于同一事务；推进字地址。
      wRegs.valid := false.B
      wRegs.last := false.B
      AWBurstShape.address := AWBurstShape.address + wStep
      wBeatReg := wBeatReg + 1.U
    }
  }
  when(bFire) {
    bRegs.valid := false.B
    wRegs.last := false.B
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

  val ARStep = beatBytes(ARBurstShape.size, addressWidth)
  val NextARAddress = Mux(ARBurstShape.burst === 1.U, ARBurstShape.address + ARStep, ARBurstShape.address)

  when(ARFire) {
    rRegs.id := io.AR.ARID
    ARBurstShape.address := io.AR.ARADDR
    ARBurstShape.length := io.AR.ARLEN
    ARBurstShape.size := io.AR.ARSIZE
    ARBurstShape.burst := io.AR.ARBURST
    ARBeatReg := 0.U
    val configError = io.AR.ARSIZE > busLaneBits.U || io.AR.ARBURST > 1.U
    ARConfigErrorReg := configError
    val addressValid = isMtime(io.AR.ARADDR) || isMtimecmp(io.AR.ARADDR)
    rRegs.data := Mux(!configError && addressValid,
      readBeatData(io.AR.ARADDR, io.AR.ARSIZE), 0.U)
    rRegs.response := Mux(!configError && addressValid, OKAY, SLVERR)
    rRegs.last := io.AR.ARLEN === 0.U
    rRegs.valid := true.B
  }
  // 读拍计数不得越过 ARLEN：越界意味着响应拍数计算错误（会多发拍）。
  assert(ARBeatReg <= ARBurstShape.length, "CLINT read beat counter exceeded ARLEN")
  when(rFire) {
    when(ARBeatReg === ARBurstShape.length) {
      rRegs.valid := false.B
      rRegs.last := false.B
    }.otherwise {
      val nextBeat = ARBeatReg + 1.U
      val addressValid = isMtime(NextARAddress) || isMtimecmp(NextARAddress)
      ARBurstShape.address := NextARAddress
      ARBeatReg := nextBeat
      rRegs.data := Mux(
        !ARConfigErrorReg && addressValid,
        readBeatData(NextARAddress, ARBurstShape.size),
        0.U
      )
      rRegs.response := Mux(
        !ARConfigErrorReg && addressValid,
        OKAY,
        SLVERR
      )
      rRegs.last := nextBeat === ARBurstShape.length
      rRegs.valid := true.B
    }
  }
}
