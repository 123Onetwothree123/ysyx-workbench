package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

/** Shared DCache allocation policy.
  *
  * The legacy base/mask and 0x8/0xa RAM filter still select which data ranges
  * are worth caching.  PMA additionally proves that the *whole* refill line is
  * readable inside one region.  If it is not, the demand is issued as a
  * one-beat uncached read so a legal access near a PMA edge is not widened into
  * an illegal burst.
  */
object ysyx_26030103_DCachePolicy {
  def LineContainedInReadableRegion(
      Address: UInt,
      AddressWidth: Int,
      BlockSizeLog2: Int,
      PMARegions: Seq[ysyx_26030103_PMARegion]
  ): Bool = {
    require(
      BlockSizeLog2 >= 2,
      "DCache cache line must contain at least one 32-bit word"
    )
    require(
      BlockSizeLog2 < AddressWidth,
      "DCache cache line must fit in the address space"
    )
    val LineBase = Cat(
      0.U(1.W),
      Address(AddressWidth - 1, BlockSizeLog2),
      0.U(BlockSizeLog2.W)
    )
    val LineLast = LineBase +
      ((BigInt(1) << BlockSizeLog2) - 1).U((AddressWidth + 1).W)
    PMARegions
      .filter(_.Readable)
      .map { Region =>
        LineBase >= BigInt(Region.Base).U((AddressWidth + 1).W) &&
        LineLast < BigInt(Region.EndExclusive).U((AddressWidth + 1).W)
      }
      .reduceOption(_ || _)
      .getOrElse(false.B)
  }

  def IsCacheable(
      Address: UInt,
      Enable: Boolean,
      AddressWidth: Int,
      BlockSizeLog2: Int,
      CacheableBase: Long,
      CacheableMask: Long,
      PMARegions: Seq[ysyx_26030103_PMARegion]
  ): Bool = {
    if (Enable) {
      val Configured =
        (Address & CacheableMask.U(AddressWidth.W)) ===
          CacheableBase.U(AddressWidth.W)
      val NormalRAM =
        Address(31, 28) === "h8".U || Address(31, 28) === "ha".U
      Configured && NormalRAM && LineContainedInReadableRegion(
        Address,
        AddressWidth,
        BlockSizeLog2,
        PMARegions
      )
    } else false.B
  }
}

// 单请求直映射DCache，读通道回填，写通道由LSU写缓冲负责
class ysyx_26030103_DCache(
    Enable: Boolean = true,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    AddressWidth: Int = 32,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
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
    val StoreValid = Input(Bool()) // LSU收到成功B响应时通知DCache
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
    // Expose the single source of truth to LSU's write-buffer ordering logic.
    val req_cacheable = Output(Bool())
    // The demand response may be returned at the critical word while the
    // remaining line refill is still draining.  LSU must keep routing AXI R
    // traffic until this flag falls.
    val axi_active = Output(Bool())
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
  val cacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    io.req.bits.addr,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  io.req_cacheable := cacheable
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
  // 正常回填使用一笔 INCR burst。仅当不符合请求长度的从机提前给出
  // RLAST 时，才退化成逐字单拍补齐剩余数据，避免需求 load 永久挂起。
  val RefillBurst = RegInit(false.B)
  val RefillError = RegInit(false.B)
  val ResponseData = RegInit(0.U(32.W))
  val ResponseFault = RegInit(false.B)
  val ResponseFaultResp = RegInit(0.U(2.W))
  val ResponseValid = RegInit(false.B)
  val DemandResponseDone = RegInit(false.B)
  val states = Enum(4)
  val SIdle = states(0)
  val SReadReq = states(1)
  val SReadResp = states(2)
  val SDrain = states(3)
  val state = RegInit(SIdle)
  // flush invalidates cached state, but it must not cancel an already accepted
  // demand request: LSU has no cancellation handshake and is waiting for exactly
  // one response.  suppressFill keeps the outstanding request alive while
  // preventing a line that straddled the flush from becoming valid again.
  val suppressFill = RegInit(false.B)
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
  io.req.ready := state === SIdle && !ResponseValid && !io.flush
  // Response state is decoupled from refill state so the critical word can
  // restart the pipeline before the rest of the line has arrived.
  io.resp.valid := ResponseValid
  io.resp.bits.data := ResponseData
  io.resp.bits.fault := ResponseFault
  io.resp.bits.FaultResp := ResponseFaultResp
  io.axi_active := state === SReadReq || state === SReadResp || state === SDrain
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
  val StoreCacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    io.StoreAddr,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  val StoreHit = StoreCacheable &&
    valid(StoreIndexSafe) && tag(StoreIndexSafe) === StoreTag
  val StoreConflictsRefill = io.StoreValid && RefillActive && StoreCacheable &&
    io.StoreAddr(AddressWidth - 1, BlockSizeLog2) ===
      ReqAddrReg(AddressWidth - 1, BlockSizeLog2)
  when(io.StoreValid && StoreCacheable) { // 只在外部写成功后更新写直达镜像
    // A demand may have early-restarted while its line is still arriving.
    // A younger successful store to that line makes any remaining read beat
    // potentially stale, so finish draining but never install this refill.
    when(StoreConflictsRefill) {
      suppressFill := true.B
      valid(StoreIndexSafe) := false.B
    }
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
    ResponseValid := false.B
    DemandResponseDone := false.B
    suppressFill := false.B
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
      ResponseValid := true.B
      state := SIdle
    }.otherwise {
      RefillActive := cacheable
      RefillBurst := cacheable
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
  when(io.resp.fire) {
    ResponseValid := false.B
    DemandResponseDone := true.B
  }
  switch(state) {
    is(SReadReq) {
      io.AXI.AR.ARVALID := true.B
      io.AXI.AR.ARADDR := Mux(
        RefillActive,
        RefillBaseReg + Cat(RefillCount, 0.U(2.W)),
        ReqAddrReg
      )
      io.AXI.AR.ARLEN := Mux(
        RefillActive && RefillBurst,
        (WordsPerBlock - 1).U,
        0.U
      )
      io.AXI.AR.ARSIZE := Mux(RefillActive, 2.U, ReqWidthReg)
      // AXI要求ARVALID一旦提出就保持到握手。即使flush到来，也继续完成
      // 地址和R通道事务；响应仍交付demand，但不安装该次回填的cache line。
      when(io.AXI.AR.ARREADY) {
        state := SReadResp
      }
    }
    is(SReadResp) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID) {
        when(RefillActive) {
          val BeatError = io.AXI.R.RRESP =/= 0.U
          val KeywordBeat = RefillCount === ReqOffsetReg
          when(BeatError) {
            RefillError := true.B
            when(KeywordBeat && !DemandResponseDone) {
              ResponseFault := true.B
              ResponseFaultResp := io.AXI.R.RRESP
              ResponseValid := true.B
            }
          }
          when(!BeatError) {
            // Data that returns during/after a flush may satisfy the demand
            // load, but must not repopulate the invalidated cache line.
            when(!suppressFill && !io.flush) {
              tag(ReqIndexSafe) := ReqTagReg
              if (WordsPerBlock > 1) {
                data(ReqIndexSafe)(RefillCount) := io.AXI.R.RDATA
              } else {
                data(ReqIndexSafe)(0) := io.AXI.R.RDATA
              }
            }
            when(KeywordBeat && !DemandResponseDone) {
              ResponseData := FormatLoad(
                io.AXI.R.RDATA,
                ReqAddrReg,
                ReqWidthReg,
                ReqSignedReg
              )
              ResponseFault := false.B
              ResponseFaultResp := 0.U
              ResponseValid := true.B
            }
          }
          val LastExpectedBeat = RefillCount === (WordsPerBlock - 1).U
          when(LastExpectedBeat) {
            when(io.AXI.R.RLAST) {
              valid(ReqIndexSafe) :=
                !suppressFill && !io.flush && !StoreConflictsRefill &&
                  !RefillError && !BeatError
              RefillActive := false.B
              RefillBurst := false.B
              suppressFill := false.B
              state := SIdle
            }.otherwise {
              // The expected final beat arrived without RLAST.  Do not let
              // the load retire while the arbiter still owns this response;
              // invalidate the line and drain until a late RLAST appears.
              valid(ReqIndexSafe) := false.B
              RefillError := true.B
              // Never mutate or recreate an already-visible Decoupled
              // response.  If the critical word was consumed earlier, this
              // late protocol error only invalidates/drains the line.
              when(!DemandResponseDone && !ResponseValid) {
                ResponseFault := true.B
                ResponseFaultResp := Mux(BeatError, io.AXI.R.RRESP, 2.U)
                ResponseValid := true.B
              }
              state := SDrain
            }
          }.elsewhen(RefillBurst) {
            when(io.AXI.R.RLAST) {
              // Early RLAST: retain received words and fetch each remainder as
              // a separate one-beat transaction.
              RefillBurst := false.B
              RefillCount := RefillCount + 1.U
              state := SReadReq
            }.otherwise {
              RefillCount := RefillCount + 1.U
            }
          }.otherwise {
            // A fallback request is one beat and therefore must end in RLAST.
            when(io.AXI.R.RLAST) {
              RefillCount := RefillCount + 1.U
              state := SReadReq
            }.otherwise {
              valid(ReqIndexSafe) := false.B
              RefillError := true.B
              when(!DemandResponseDone && !ResponseValid) {
                ResponseFault := true.B
                ResponseFaultResp := Mux(BeatError, io.AXI.R.RRESP, 2.U)
                ResponseValid := true.B
              }
              state := SDrain
            }
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
          ResponseValid := true.B
          when(io.AXI.R.RLAST) {
            suppressFill := false.B
            state := SIdle
          }.otherwise {
            // Every uncached request is a one-beat AXI transaction.  Missing
            // RLAST is a protocol fault and must be drained before another
            // LSU read can reuse the fixed read ID.
            ResponseFault := true.B
            ResponseFaultResp := Mux(
              io.AXI.R.RRESP =/= 0.U,
              io.AXI.R.RRESP,
              2.U
            )
            state := SDrain
          }
        }
      }
    }
    is(SDrain) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RLAST) {
        RefillActive := false.B
        RefillBurst := false.B
        suppressFill := false.B
        state := SIdle
      }
    }
  }
  when(io.flush) { // fence.i使全部缓存行失效
    valid.foreach(_ := false.B)
    when(state =/= SIdle) { suppressFill := true.B }
  }
}
