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
    val addr = UInt(AddressWidth.W)
    val WidthSelect = UInt(2.W) // 0=字节，1=半字，2=字
    val signed = Bool()
  }
  class Response extends Bundle {
    val data = UInt(32.W)
    val fault = Bool()
    val FaultResp = UInt(2.W)
  }
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Request))
    val resp = Decoupled(new Response)
    val StoreValid = Input(Bool()) // LSU写缓冲入队时通知DCache
    val StoreAddr = Input(UInt(AddressWidth.W))
    val StoreData = Input(UInt(32.W))
    val StoreStrb = Input(UInt(4.W))
    val AXI = new ysyx_26030103_AXI5IO(AddressWidth) // DCache只使用AR/R通道
    val flush = Input(Bool())
    // 性能计数器(约定与ICache一致: 不可缓存访问也计为缺失)
    val perf_hit = Output(Bool())
    val perf_miss = Output(Bool())
    val perf_refill_req = Output(Bool())
    val perf_refill_resp = Output(Bool())
  })
  val valid = RegInit(VecInit(Seq.fill(ArrayBlocks)(false.B)))
  val tag = Reg(Vec(ArrayBlocks, UInt(TagBits.W)))
  val data = Reg(Vec(ArrayBlocks, Vec(WordsPerBlock, UInt(32.W))))
  val index = io.req.bits.addr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val IndexSafe = if (Enable) index else 0.U(IndexWidth.W)
  val WordOffset =
    if (WordsPerBlock > 1) io.req.bits.addr(BlockSizeLog2 - 1, 2)
    else 0.U(OffsetWidth.W)
  val ReqTag = io.req.bits.addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val ConfiguredCacheable =
    if (Enable) {
      (io.req.bits.addr & CacheableMask.U(AddressWidth.W)) ===
        CacheableBase.U(AddressWidth.W)
    } else false.B
  val NormalRAM = io.req.bits.addr(31, 28) === "h8".U ||
    io.req.bits.addr(31, 28) === "ha".U
  val cacheable = ConfiguredCacheable && NormalRAM
  val hit =
    cacheable && valid(IndexSafe) && tag(IndexSafe) === ReqTag // 这个直接拿来做一个命中条件
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
  val states = Enum(4)
  val SIdle = states(0)
  val SReadReq = states(1)
  val SReadResp = states(2)
  val SResp = states(3)
  val state = RegInit(SIdle)
  val discard = RegInit(false.B)
  val ReqIndexSafe = if (Enable) ReqIndexReg else 0.U(IndexWidth.W)
  def FormatLoad(word: UInt, address: UInt, width: UInt, signed: Bool): UInt = {
    val ByteData = MuxLookup(address(1, 0), word(7, 0))(
      Seq(
        1.U -> word(15, 8),
        2.U -> word(23, 16),
        3.U -> word(31, 24)
      )
    )
    val HalfData = Mux(address(1), word(31, 16), word(15, 0))
    val ByteResult = Mux(
      signed,
      Cat(Fill(24, ByteData(7)), ByteData),
      Cat(0.U(24.W), ByteData)
    )
    val HalfResult = Mux(
      signed,
      Cat(Fill(16, HalfData(15)), HalfData),
      Cat(0.U(16.W), HalfData)
    )
    MuxLookup(width, word)(
      Seq(
        0.U -> ByteResult,
        1.U -> HalfResult,
        2.U -> word
      )
    )
  }
  def StoreMaskWord(strb: UInt): UInt = Cat(
    Mux(strb(3), "hff".U(8.W), 0.U(8.W)),
    Mux(strb(2), "hff".U(8.W), 0.U(8.W)),
    Mux(strb(1), "hff".U(8.W), 0.U(8.W)),
    Mux(strb(0), "hff".U(8.W), 0.U(8.W))
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
  io.req.ready := state === SIdle && !io.flush
  io.resp.valid := state === SResp
  io.resp.bits.data := ResponseData
  io.resp.bits.fault := ResponseFault
  io.resp.bits.FaultResp := ResponseFaultResp
  // 性能计数器: 受理当拍判定命中/缺失; 回填AR/R阶段计数
  io.perf_hit := io.req.fire && hit
  io.perf_miss := io.req.fire && !hit
  io.perf_refill_req := RefillActive && state === SReadReq
  io.perf_refill_resp := RefillActive && state === SReadResp
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
    valid(StoreIndexSafe) && tag(StoreIndexSafe) === StoreTag
  when(io.StoreValid && StoreCacheable) { // 与LSU写缓冲入队同拍更新缓存
    when(StoreHit) {
      if (WordsPerBlock > 1) {
        data(StoreIndexSafe)(StoreOffset) :=
          (data(StoreIndexSafe)(StoreOffset) & ~StoreMaskWord(io.StoreStrb)) |
            (io.StoreData & StoreMaskWord(io.StoreStrb))
      } else {
        data(StoreIndexSafe)(0) :=
          (data(StoreIndexSafe)(0) & ~StoreMaskWord(io.StoreStrb)) |
            (io.StoreData & StoreMaskWord(io.StoreStrb))
      }
    }.otherwise {
      valid(StoreIndexSafe) := false.B
    }
  }
  when(io.req.fire) {
    ReqAddrReg := io.req.bits.addr
    ReqWidthReg := io.req.bits.WidthSelect
    ReqSignedReg := io.req.bits.signed
    ReqIndexReg := index
    ReqTagReg := ReqTag
    ReqOffsetReg := WordOffset
    ResponseFault := false.B
    ResponseFaultResp := 0.U
    discard := false.B
    when(hit) {
      if (WordsPerBlock > 1) {
        ResponseData := FormatLoad(
          data(IndexSafe)(WordOffset),
          io.req.bits.addr,
          io.req.bits.WidthSelect,
          io.req.bits.signed
        )
      } else {
        ResponseData := FormatLoad(
          data(IndexSafe)(0),
          io.req.bits.addr,
          io.req.bits.WidthSelect,
          io.req.bits.signed
        )
      }
      state := SResp
    }.otherwise {
      RefillActive := cacheable
      RefillCount := 0.U
      RefillError := false.B
      RefillBaseReg := Cat(
        io.req.bits.addr(AddressWidth - 1, BlockSizeLog2),
        0.U(BlockSizeLog2.W)
      )
      when(cacheable) { valid(IndexSafe) := false.B }
      state := SReadReq
    }
  }
  switch(state) {
    is(SReadReq) {
      io.AXI.AR.ARVALID := true.B
      io.AXI.AR.ARADDR := Mux(
        RefillActive,
        RefillBaseReg + Cat(RefillCount, 0.U(2.W)),
        ReqAddrReg
      )
      io.AXI.AR.ARSIZE := Mux(RefillActive, 2.U, ReqWidthReg)
      when(io.flush) {
        when(io.AXI.AR.ARREADY) {
          discard := true.B // AR已握手时继续等待并丢弃返回数据
          state := SReadResp
        }.otherwise {
          RefillActive := false.B
          state := SIdle
        }
      }.elsewhen(io.AXI.AR.ARREADY) {
        state := SReadResp
      }
    }
    is(SReadResp) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID) {
        when(io.flush || discard) {
          RefillActive := false.B
          discard := false.B
          state := SIdle
        }.elsewhen(RefillActive) {
          val BeatError = io.AXI.R.RRESP =/= 0.U
          when(BeatError) {
            RefillError := true.B
            when(RefillCount === ReqOffsetReg) {
              ResponseFault := true.B
              ResponseFaultResp := io.AXI.R.RRESP
            }
          }
          tag(ReqIndexSafe) := ReqTagReg
          when(!BeatError) {
            if (WordsPerBlock > 1) {
              data(ReqIndexSafe)(RefillCount) := io.AXI.R.RDATA
            } else {
              data(ReqIndexSafe)(0) := io.AXI.R.RDATA
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
            valid(ReqIndexSafe) := !RefillError && !BeatError // 任一回填出错都不置有效
            RefillActive := false.B
            state := SResp
          }.otherwise {
            RefillCount := RefillCount + 1.U
            state := SReadReq
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
          state := SResp
        }
      }
    }
    is(SResp) {
      when(io.resp.fire) { state := SIdle }
    }
  }
  when(io.flush) { // fence.i使全部缓存行失效
    valid.foreach(_ := false.B)
    when(state === SResp) { state := SIdle }
    when(state === SReadResp && !io.AXI.R.RVALID) { discard := true.B }
  }
}
