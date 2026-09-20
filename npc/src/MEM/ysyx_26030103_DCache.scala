package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.infra._
// 单请求直映射DCache，读通道回填，写通道由LSU写缓冲负责
class ysyx_26030103_DCache(
    Enable: Boolean = true,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    AddressWidth: Int = 32,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L
) extends Module {
  private val TagBits = AddressWidth - IndexBits - BlockSizeLog2
  private val NumBlocks = 1 << IndexBits
  private val WordsPerBlock = 1 << (BlockSizeLog2 - 2)
  private val IndexWidth = IndexBits.max(1)
  private val OffsetWidth = (BlockSizeLog2 - 2).max(1)
  private val CountWidth = log2Ceil(WordsPerBlock).max(1)
  private val ArrayBlocks = if (Enable) NumBlocks else 1
  class Request extends Bundle {
    val Addr = UInt(AddressWidth.W)
    val WidthSelect = UInt(2.W) // 0=字节，1=半字，2=字
    val Signed = Bool()
  }
  class Response extends Bundle {
    val Data = UInt(32.W)
    val Fault = Bool()
    val FaultResp = UInt(2.W)
  }
  val io = IO(new Bundle {
    val Req = Flipped(Decoupled(new Request))
    val Resp = Decoupled(new Response)
    val StoreValid = Input(Bool()) // LSU写缓冲入队时通知DCache
    val StoreAddr = Input(UInt(AddressWidth.W))
    val StoreData = Input(UInt(32.W))
    val StoreStrb = Input(UInt(4.W))
    val AXI = new ysyx_26030103_AXI5IO(AddressWidth) // DCache只使用AR/R通道
    val Flush = Input(Bool())
  })
  val Valid = RegInit(VecInit(Seq.fill(ArrayBlocks)(false.B)))
  val Tag = Reg(Vec(ArrayBlocks, UInt(TagBits.W)))
  val Data = Reg(Vec(ArrayBlocks, Vec(WordsPerBlock, UInt(32.W))))
  val Index = io.Req.bits.Addr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val IndexSafe = if (Enable) Index else 0.U(IndexWidth.W)
  val WordOffset =
    if (WordsPerBlock > 1) io.Req.bits.Addr(BlockSizeLog2 - 1, 2)
    else 0.U(OffsetWidth.W)
  val ReqTag = io.Req.bits.Addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val ConfiguredCacheable =
    if (Enable) {
      (io.Req.bits.Addr & CacheableMask.U(AddressWidth.W)) ===
        CacheableBase.U(AddressWidth.W)
    } else false.B
  val NormalRAM = io.Req.bits.Addr(31, 28) === "h8".U ||
    io.Req.bits.Addr(31, 28) === "ha".U
  val Cacheable = ConfiguredCacheable && NormalRAM
  val Hit =
    Cacheable && Valid(IndexSafe) && Tag(IndexSafe) === ReqTag // 这个直接拿来做一个命中条件
  val ReqAddrReg = Reg(UInt(AddressWidth.W))
  val ReqWidthReg = Reg(UInt(2.W))
  val ReqSignedReg = Reg(Bool())
  val ReqIndexReg = Reg(UInt(IndexWidth.W))
  val ReqTagReg = Reg(UInt(TagBits.W))
  val ReqOffsetReg = Reg(UInt(CountWidth.W))
  val RefillBaseReg = Reg(UInt(AddressWidth.W))
  val RefillCount = RegInit(0.U(CountWidth.W))
  val RefillActive = RegInit(false.B)
  val RefillError = RegInit(false.B)
  val ResponseData = RegInit(0.U(32.W))
  val ResponseFault = RegInit(false.B)
  val ResponseFaultResp = RegInit(0.U(2.W))
  val States = Enum(4)
  val SIdle = States(0)
  val SReadReq = States(1)
  val SReadResp = States(2)
  val SResp = States(3)
  val State = RegInit(SIdle)
  val Discard = RegInit(false.B)
  val ReqIndexSafe = if (Enable) ReqIndexReg else 0.U(IndexWidth.W)
  def FormatLoad(word: UInt, Address: UInt, Width: UInt, Signed: Bool): UInt = {
    val ByteData = MuxLookup(Address(1, 0), word(7, 0))(
      Seq(
        1.U -> word(15, 8),
        2.U -> word(23, 16),
        3.U -> word(31, 24)
      )
    )
    val HalfData = Mux(Address(1), word(31, 16), word(15, 0))
    val ByteResult = Mux(
      Signed,
      Cat(Fill(24, ByteData(7)), ByteData),
      Cat(0.U(24.W), ByteData)
    )
    val HalfResult = Mux(
      Signed,
      Cat(Fill(16, HalfData(15)), HalfData),
      Cat(0.U(16.W), HalfData)
    )
    MuxLookup(Width, word)(
      Seq(
        0.U -> ByteResult,
        1.U -> HalfResult,
        2.U -> word
      )
    )
  }
  def StoreMaskWord(Strb: UInt): UInt = Cat(
    Mux(Strb(3), "hff".U(8.W), 0.U(8.W)),
    Mux(Strb(2), "hff".U(8.W), 0.U(8.W)),
    Mux(Strb(1), "hff".U(8.W), 0.U(8.W)),
    Mux(Strb(0), "hff".U(8.W), 0.U(8.W))
  )
  io.AXI.AW.AWVALID := false.B // 写通道由LSU负责
  io.AXI.AW.AWID := 0.U
  io.AXI.AW.AWADDR := 0.U
  io.AXI.AW.AWLEN := 0.U
  io.AXI.AW.AWSIZE := 2.U
  io.AXI.AW.AWBURST := 1.U
  io.AXI.AW.AWPROT := 0.U
  io.AXI.W.WVALID := false.B
  io.AXI.W.WDATA := 0.U
  io.AXI.W.WSTRB := 0.U
  io.AXI.W.WLAST := false.B
  io.AXI.B.BREADY := false.B
  io.AXI.AR.ARVALID := false.B
  io.AXI.AR.ARID := 0.U
  io.AXI.AR.ARADDR := 0.U
  io.AXI.AR.ARLEN := 0.U
  io.AXI.AR.ARSIZE := 2.U
  io.AXI.AR.ARBURST := 1.U
  io.AXI.AR.ARPROT := 0.U
  io.AXI.R.RREADY := false.B
  io.Req.ready := State === SIdle && !io.Flush
  io.Resp.valid := State === SResp
  io.Resp.bits.Data := ResponseData
  io.Resp.bits.Fault := ResponseFault
  io.Resp.bits.FaultResp := ResponseFaultResp
  val StoreIndex = io.StoreAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val StoreIndexSafe = if (Enable) StoreIndex else 0.U(IndexWidth.W)
  val StoreOffset =
    if (WordsPerBlock > 1) io.StoreAddr(BlockSizeLog2 - 1, 2)
    else 0.U(OffsetWidth.W)
  val StoreTag = io.StoreAddr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val StoreConfigured =
    if (Enable) {
      (io.StoreAddr & CacheableMask.U(AddressWidth.W)) ===
        CacheableBase.U(AddressWidth.W)
    } else false.B
  val StoreCacheable = StoreConfigured &&
    (io.StoreAddr(31, 28) === "h8".U || io.StoreAddr(31, 28) === "ha".U)
  val StoreHit = StoreCacheable &&
    Valid(StoreIndexSafe) && Tag(StoreIndexSafe) === StoreTag
  when(io.StoreValid && StoreCacheable) { // 与LSU写缓冲入队同拍更新缓存
    when(StoreHit) {
      if (WordsPerBlock > 1) {
        Data(StoreIndexSafe)(StoreOffset) :=
          (Data(StoreIndexSafe)(StoreOffset) & ~StoreMaskWord(io.StoreStrb)) |
            (io.StoreData & StoreMaskWord(io.StoreStrb))
      } else {
        Data(StoreIndexSafe)(0) :=
          (Data(StoreIndexSafe)(0) & ~StoreMaskWord(io.StoreStrb)) |
            (io.StoreData & StoreMaskWord(io.StoreStrb))
      }
    }.otherwise {
      Valid(StoreIndexSafe) := false.B
    }
  }
  when(io.Req.fire) {
    ReqAddrReg := io.Req.bits.Addr
    ReqWidthReg := io.Req.bits.WidthSelect
    ReqSignedReg := io.Req.bits.Signed
    ReqIndexReg := Index
    ReqTagReg := ReqTag
    ReqOffsetReg := WordOffset
    ResponseFault := false.B
    ResponseFaultResp := 0.U
    Discard := false.B
    when(Hit) {
      if (WordsPerBlock > 1) {
        ResponseData := FormatLoad(
          Data(IndexSafe)(WordOffset),
          io.Req.bits.Addr,
          io.Req.bits.WidthSelect,
          io.Req.bits.Signed
        )
      } else {
        ResponseData := FormatLoad(
          Data(IndexSafe)(0),
          io.Req.bits.Addr,
          io.Req.bits.WidthSelect,
          io.Req.bits.Signed
        )
      }
      State := SResp
    }.otherwise {
      RefillActive := Cacheable
      RefillCount := 0.U
      RefillError := false.B
      RefillBaseReg := Cat(
        io.Req.bits.Addr(AddressWidth - 1, BlockSizeLog2),
        0.U(BlockSizeLog2.W)
      )
      when(Cacheable) { Valid(IndexSafe) := false.B }
      State := SReadReq
    }
  }
  switch(State) {
    is(SReadReq) {
      io.AXI.AR.ARVALID := true.B
      io.AXI.AR.ARADDR := Mux(
        RefillActive,
        RefillBaseReg + Cat(RefillCount, 0.U(2.W)),
        ReqAddrReg
      )
      io.AXI.AR.ARSIZE := Mux(RefillActive, 2.U, ReqWidthReg)
      when(io.Flush) {
        when(io.AXI.AR.ARREADY) {
          Discard := true.B // AR已握手时继续等待并丢弃返回数据
          State := SReadResp
        }.otherwise {
          RefillActive := false.B
          State := SIdle
        }
      }.elsewhen(io.AXI.AR.ARREADY) {
        State := SReadResp
      }
    }
    is(SReadResp) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID) {
        when(io.Flush || Discard) {
          RefillActive := false.B
          Discard := false.B
          State := SIdle
        }.elsewhen(RefillActive) {
          val BeatError = io.AXI.R.RRESP =/= 0.U
          when(BeatError) {
            RefillError := true.B
            when(RefillCount === ReqOffsetReg) {
              ResponseFault := true.B
              ResponseFaultResp := io.AXI.R.RRESP
            }
          }
          Tag(ReqIndexSafe) := ReqTagReg
          when(!BeatError) {
            if (WordsPerBlock > 1) {
              Data(ReqIndexSafe)(RefillCount) := io.AXI.R.RDATA
            } else {
              Data(ReqIndexSafe)(0) := io.AXI.R.RDATA
            }
            when(RefillCount === ReqOffsetReg) {
              ResponseData := FormatLoad(
                io.AXI.R.RDATA,
                ReqAddrReg,
                ReqWidthReg,
                ReqSignedReg
              )
            }
          }
          when(RefillCount === (WordsPerBlock - 1).U) {
            Valid(ReqIndexSafe) := !RefillError && !BeatError // 任一回填出错都不置有效
            RefillActive := false.B
            State := SResp
          }.otherwise {
            RefillCount := RefillCount + 1.U
            State := SReadReq
          }
        }.otherwise {
          when(io.AXI.R.RRESP =/= 0.U) {
            ResponseFault := true.B
            ResponseFaultResp := io.AXI.R.RRESP
          }.otherwise {
            ResponseData := FormatLoad(
              io.AXI.R.RDATA,
              ReqAddrReg,
              ReqWidthReg,
              ReqSignedReg
            )
          }
          State := SResp
        }
      }
    }
    is(SResp) {
      when(io.Resp.fire) { State := SIdle }
    }
  }
  when(io.Flush) { // fence.i使全部缓存行失效
    Valid.foreach(_ := false.B)
    when(State === SResp) { State := SIdle }
    when(State === SReadResp && !io.AXI.R.RVALID) { Discard := true.B }
  }
}
