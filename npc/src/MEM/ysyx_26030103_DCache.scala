package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

/** DCache共用的分配策略。
  *
  * 原有的基址/掩码和0x8/0xa RAM过滤器仍用于选择值得缓存的数据区间。
  * PMA还会确认整条回填缓存行都位于同一个可读区域内。若不满足，需求请求
  * 将作为单拍非缓存读发出，避免PMA边界附近的合法访问被扩展成非法突发传输。
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
    // 向LSU的写缓冲排序逻辑提供唯一的可缓存性判定源。
    val req_cacheable = Output(Bool())
    // 需求响应可能在关键字返回时就发出，而缓存行的其余回填仍在排空。
    // 在此标志拉低前，LSU必须继续路由AXI R通道流量。
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
  // flush会使缓存状态失效，但不能取消已经接受的需求请求：LSU没有取消握手，
  // 并且正在等待恰好一个响应。suppressFill在维持在途请求的同时，防止跨越
  // 本次flush的缓存行重新变为有效。
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
  // 响应状态与回填状态解耦，使关键字能在缓存行其余数据到达前重启流水线。
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
    // 需求请求可能已在其缓存行仍在到达时提前重启。若更年轻的store成功写入
    // 该缓存行，则剩余读数据拍都可能已过期，因此应完成排空但绝不安装此次回填。
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
            // flush期间或之后返回的数据可以满足需求load，但不得重新填充
            // 已失效的缓存行。
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
              // 预期的最后一拍到达时没有RLAST。仲裁器仍持有此响应期间，
              // 不得让load退休；应使缓存行失效并持续排空，直到迟到的RLAST出现。
              valid(ReqIndexSafe) := false.B
              RefillError := true.B
              // 绝不修改或重新产生已经对外可见的Decoupled响应。若关键字先前已被
              // 消费，这个迟到的协议错误只会使缓存行失效并将其排空。
              when(!DemandResponseDone && !ResponseValid) {
                ResponseFault := true.B
                ResponseFaultResp := Mux(BeatError, io.AXI.R.RRESP, 2.U)
                ResponseValid := true.B
              }
              state := SDrain
            }
          }.elsewhen(RefillBurst) {
            when(io.AXI.R.RLAST) {
              // RLAST提前到达：保留已接收的数据字，并将每个剩余数据字作为
              // 独立的单拍事务获取。
              RefillBurst := false.B
              RefillCount := RefillCount + 1.U
              state := SReadReq
            }.otherwise {
              RefillCount := RefillCount + 1.U
            }
          }.otherwise {
            // 退化请求只有一拍，因此必须以RLAST结束。
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
            // 每个非缓存请求都是单拍AXI事务。缺少RLAST属于协议故障，必须先将其
            // 排空，另一个LSU读请求才能复用固定的读ID。
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
