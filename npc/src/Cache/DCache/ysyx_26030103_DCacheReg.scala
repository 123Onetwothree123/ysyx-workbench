package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

// 单请求直映射DCache，读通道回填，写通道由LSU写缓冲负责
class ysyx_26030103_DCacheReg(
    Enable: Boolean = true,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    AddressWidth: Int = 32,
    Vipt: Boolean = false,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_DCacheBase(AddressWidth) {
  require(BlockSizeLog2 >= 2, "DCache line must contain at least one 32-bit word")
  require(IndexBits >= 1, "DCache must contain at least two sets")
  require(
    IndexBits + BlockSizeLog2 < AddressWidth,
    "DCache geometry must leave at least one tag bit"
  )
  private val TagBits = AddressWidth - IndexBits - BlockSizeLog2
  private val NumBlocks = 1 << IndexBits
  private val WordsPerBlock = 1 << (BlockSizeLog2 - 2)
  private val IndexWidth = IndexBits.max(1)
  private val OffsetWidth = (BlockSizeLog2 - 2).max(1)
  private val CountWidth = log2Ceil(WordsPerBlock).max(1)
  private val ArrayBlocks = if (Enable) NumBlocks else 1
  // 一致性 flush 是 DCache 自身的可验证接口；生产顶层已由 DMA 写 B
  // 握手的失效脉冲驱动（ysyx_26030103.scala），dontTouch 保证该端口在
  // 叶模块 elaboration 中不被裁掉。
  dontTouch(io.flush)
  val valid = RegInit(VecInit(Seq.fill(ArrayBlocks)(false.B)))
  val tag = Reg(Vec(ArrayBlocks, UInt(TagBits.W)))
  val data = Reg(Vec(ArrayBlocks, Vec(WordsPerBlock, UInt(32.W))))
  val indexAddr = if (Vipt) io.req.bits.vaddr else io.req.bits.addr
  val index = indexAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val IndexSafe = if (Enable) index else 0.U(IndexWidth.W)
  val WordOffset =
    if (WordsPerBlock > 1) indexAddr(BlockSizeLog2 - 1, 2)
    else 0.U(OffsetWidth.W)
  val ReqTag = io.req.bits.addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  // VIPT：req 只保证 VA 有效，PA 与它的 VA 标签都匹配后才能做 tag/PMA 判定。
  // PIPT 下这个条件恒为真，原有路径完全不变。
  val IncomingPaddrMatch =
    !Vipt.B || (io.req_paddr_valid && io.req_paddr_vaddr === io.req.bits.vaddr)
  val cacheable = IncomingPaddrMatch && ysyx_26030103_DCachePolicy.IsCacheable(
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
  // VIPT 等待 PA 的请求状态：VA 已经选好 index/offset 并锁存，PA 未到前
  // 不判定命中、不发 AR。WaitDataReg 是受理拍用 VA 组索引早读出的字，
  // PA 匹配后若 tag 仍命中即可直接返回（等待期间没有其他请求能改写阵列）。
  val WaitPaddr = RegInit(false.B)
  val WaitVaddrReg = Reg(UInt(AddressWidth.W))
  val WaitIndexReg = Reg(UInt(IndexWidth.W))
  val WaitOffsetReg = Reg(UInt(CountWidth.W))
  val WaitWidthReg = Reg(UInt(2.W))
  val WaitSignedReg = Reg(Bool())
  val WaitDataReg = Reg(UInt(32.W))
  val ViptNeedWait = Vipt.B && !IncomingPaddrMatch
  // PA 带受理时的 VA 标签返回：只接受属于这笔等待请求的翻译结果。
  // flush/kill 同拍时优先放弃本次 resolve，避免把已失效行或故障请求的数据
  // 送上响应通道。
  val ResolveVaddrMatch = !io.flush && !io.req_kill && WaitPaddr &&
    io.req_paddr_valid && io.req_paddr_vaddr === WaitVaddrReg
  val ResolvePaddr = io.req.bits.addr
  val ResolveTag =
    ResolvePaddr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val ResolveIndexSafe = if (Enable) WaitIndexReg else 0.U(IndexWidth.W)
  val ResolveCacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    ResolvePaddr,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  val ResolveHit = ResolveVaddrMatch && ResolveCacheable &&
    valid(ResolveIndexSafe) && tag(ResolveIndexSafe) === ResolveTag
  val ReqAddrReg = Reg(UInt(AddressWidth.W))
  val ReqWidthReg = Reg(UInt(2.W))
  val ReqSignedReg = Reg(Bool())
  val ReqIndexReg = Reg(UInt(IndexWidth.W))
  val ReqTagReg = Reg(UInt(TagBits.W))
  val ReqOffsetReg = Reg(UInt(CountWidth.W))
  val RefillBaseReg = Reg(UInt(AddressWidth.W))
  val RefillCount = RegInit(0.U(CountWidth.W))
  val RefillActive = RegInit(false.B)
  // 单MSHR回填期间逐字记录已经成功返回的数据。缓存行在最后一拍以前仍然
  // 保持invalid，但这些字可以安全地服务同一行的后续load；未置位的字绝不
  // 能读取data阵列，因为其中可能还是旧行或尚未初始化的内容。
  val RefillWordValid = RegInit(0.U(WordsPerBlock.W))
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
  io.AXI.AW.AWCACHE := 0.U
  io.AXI.AW.AWQOS := 0.U
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
  io.AXI.AR.ARCACHE := 0.U
  io.AXI.AR.ARQOS := 0.U
  io.AXI.R.RREADY := false.B
  // ResponseValid is a one-entry elastic response slot.  When the consumer
  // removes the old response in this cycle, a new request may reuse the slot
  // immediately; forbidding that pop+push case inserted a bubble between every
  // pair of cache hits.
  val ResponseSlotReady = !ResponseValid || io.resp.ready
  // 响应状态与回填状态解耦，使关键字能在缓存行其余数据到达前重启流水线。
  io.resp.valid := ResponseValid
  io.resp.bits.data := ResponseData
  io.resp.bits.fault := ResponseFault
  io.resp.bits.FaultResp := ResponseFaultResp
  io.axi_active := state === SReadReq || state === SReadResp || state === SDrain
  io.perf_refill_req := RefillActive && state === SReadReq
  io.perf_refill_resp := RefillActive && state === SReadResp
  val storeIndexAddr = if (Vipt) io.StoreVAddr else io.StoreAddr
  val StoreIndex = storeIndexAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val StoreIndexSafe = if (Enable) StoreIndex else 0.U(IndexWidth.W)
  val StoreOffset =
    if (WordsPerBlock > 1) storeIndexAddr(BlockSizeLog2 - 1, 2)
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
      RefillBaseReg(AddressWidth - 1, BlockSizeLog2)

  // Early restart之后，尾部回填仍独占AXI读ID，但其他set的完整hit以及本次
  // 回填中已经成功返回的字都不需要第二个MSHR。旧需求响应必须已经被消费
  // （或本拍正在消费），从而保持无tag响应接口的严格请求顺序。未返回字、
  // refill所在set的异tag请求和任何第二次miss仍反压到单MSHR释放。
  val SameRefillLine = cacheable && RefillActive &&
    io.req.bits.addr(AddressWidth - 1, BlockSizeLog2) ===
      RefillBaseReg(AddressWidth - 1, BlockSizeLog2)
  val RefillWordReady = VecInit(RefillWordValid.asBools)(WordOffset)
  val PriorDemandLeaves = DemandResponseDone || io.resp.fire
  val RefillInProgress = state === SReadReq || state === SReadResp
  val LastRefillWord = RefillCount === (WordsPerBlock - 1).U
  // RREADY在SReadResp恒为高，因此RVALID即表示本拍会消费该beat。寄存的
  // RefillError要到下一拍才生效；将本拍RRESP错误和“必须为单拍末尾却缺
  // RLAST”的协议错误直接旁路进ready，避免错误被采样的同拍仍接受partial hit。
  val RefillBeatArrives = state === SReadResp && RefillActive && io.AXI.R.RVALID
  val RequiredLastMissing = RefillBeatArrives && !io.AXI.R.RLAST &&
    (LastRefillWord || !RefillBurst)
  val RefillFaultNow =
    (RefillBeatArrives && io.AXI.R.RRESP =/= 0.U) || RequiredLastMissing
  // 同拍成功store会在时钟沿才写入cache阵列；若此时接受同一行的load，响应
  // 会锁存store之前的旧值，因此保守地将该load反压一拍。
  val StoreConflictsRequest = io.StoreValid && StoreCacheable && cacheable &&
    io.StoreAddr(AddressWidth - 1, BlockSizeLog2) ===
      io.req.bits.addr(AddressWidth - 1, BlockSizeLog2)
  val PartialHitUnderRefill = RefillInProgress && SameRefillLine &&
    RefillWordReady && !RefillError && !suppressFill &&
    !StoreConflictsRefill && !RefillFaultNow
  val FullHitUnderRefill = RefillInProgress && RefillActive && hit
  val HitUnderRefill = PriorDemandLeaves && !io.flush &&
    !StoreConflictsRequest && (FullHitUnderRefill || PartialHitUnderRefill)
  val AcceptedHit = Mux(state === SIdle, hit, HitUnderRefill)
  // 同拍命中 store 会在此沿才写入阵列/镜像：SIdle 命中路径也必须反压
  // 同线 load 一拍，否则 Reg 实现会锁存 store 之前的旧值（SRAM 实现因
  // 读口打拍而恰好看到新值），两实现语义不一致。
  // VIPT 缺少 PA 时只允许在空闲且无回填时受理；等待期间不再接新请求。
  val ViptAcceptable = !ViptNeedWait || (state === SIdle && !RefillActive)
  io.req.ready := ResponseSlotReady && !io.flush && !io.req_kill &&
    !WaitPaddr && ViptAcceptable &&
    (state === SIdle || (HitUnderRefill && !ViptNeedWait)) &&
    !StoreConflictsRequest
  // 性能计数器: 已返回的MSHR字按hit计数，不应伪造第二次cache miss。
  // VIPT 未拿到 PA 时推迟到 resolve 拍再统计，避免把“尚未翻译”误报成 miss。
  io.perf_hit := (io.req.fire && !ViptNeedWait && AcceptedHit) ||
    (ResolveVaddrMatch && ResolveHit)
  io.perf_miss := (io.req.fire && !ViptNeedWait && cacheable && !AcceptedHit) ||
    (ResolveVaddrMatch && ResolveCacheable && !ResolveHit)

  when(io.StoreValid && StoreCacheable) {
    // 需求请求可能已在其缓存行仍在到达时提前重启。若更年轻store此时完成，
    // 即使BRESP报错也不能排除部分副作用；剩余读拍都可能已过期，因此应完成
    // 排空但绝不安装此次回填。
    when(StoreConflictsRefill) {
      suppressFill := true.B
      RefillWordValid := 0.U
      valid(StoreIndexSafe) := false.B
    }
    when(StoreHit) {
      when(io.StoreResp === 0.U) {
        // 成功的单拍store精确知道新字节值，可以直接维护写直达镜像。
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
        // SLVERR/DECERR不保证从机完全没有副作用。不能把失败store的数据
        // 投机写入cache，但也不能继续保留可能已经过期的旧缓存行。
        valid(StoreIndexSafe) := false.B
      }
    }
  }
  // Clear the old response before installing a replacement below.  The order
  // is important for a hit accepted in the same cycle as io.resp.fire: the new
  // response must remain valid for the following cycle.
  when(io.resp.fire) {
    ResponseValid := false.B
    DemandResponseDone := true.B
  }
  when(io.req.fire) {
    when(HitUnderRefill) {
      // 此请求的数据已经在先前R拍写入阵列。不要覆盖ReqIndex/Tag/Base等
      // MSHR元数据，也不要改变正在进行的AR/R状态。
      ResponseData := FormatLoad(
        data(IndexSafe)(WordOffset),
        io.req.bits.addr,
        io.req.bits.WidthSelect,
        io.req.bits.signed
      )
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := true.B
    }.elsewhen(ViptNeedWait) {
      // VIPT：PA 尚未带正确 VA 标签返回。受理拍只锁存 VA 选出的
      // index/offset 和早读数据，绝不判定命中、发 AR 或提交故障响应。
      WaitPaddr := true.B
      WaitVaddrReg := io.req.bits.vaddr
      WaitIndexReg := index
      WaitOffsetReg := WordOffset
      WaitWidthReg := io.req.bits.WidthSelect
      WaitSignedReg := io.req.bits.signed
      WaitDataReg :=
        (if (WordsPerBlock > 1) data(IndexSafe)(WordOffset)
         else data(IndexSafe)(0))
    }.otherwise {
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
        RefillWordValid := 0.U
        RefillBaseReg := Cat(
          io.req.bits.addr(AddressWidth - 1, BlockSizeLog2),
          0.U(BlockSizeLog2.W)
        )
        when(cacheable) { valid(IndexSafe) := false.B }
        state := SReadReq
      }
    }
  }
  // 迟到的 PA 与受理时 VA 标签匹配：现在才用物理 tag 判定命中/缺失。
  // 命中数据直接来自受理拍用 VA index 早读的字；缺失则用解析出的 PA
  // 启动 refill，安装位置仍是 VA index。
  when(ResolveVaddrMatch) {
    WaitPaddr := false.B
    when(ResolveHit) {
      ResponseData := FormatLoad(
        WaitDataReg,
        WaitVaddrReg,
        WaitWidthReg,
        WaitSignedReg
      )
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := true.B
      DemandResponseDone := true.B
    }.otherwise {
      ReqAddrReg := ResolvePaddr
      ReqWidthReg := WaitWidthReg
      ReqSignedReg := WaitSignedReg
      ReqIndexReg := WaitIndexReg
      ReqTagReg := ResolveTag
      ReqOffsetReg := WaitOffsetReg
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := false.B
      DemandResponseDone := false.B
      suppressFill := false.B
      RefillActive := ResolveCacheable
      RefillBurst := ResolveCacheable
      RefillCount := 0.U
      RefillError := false.B
      RefillWordValid := 0.U
      RefillBaseReg := Cat(
        ResolvePaddr(AddressWidth - 1, BlockSizeLog2),
        0.U(BlockSizeLog2.W)
      )
      when(ResolveCacheable) { valid(ResolveIndexSafe) := false.B }
      state := SReadReq
    }
  }
  // MMU 翻译故障杀死等待中的请求：不能留下响应或总线事务。
  when(io.req_kill) {
    WaitPaddr := false.B
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
            RefillWordValid := 0.U
            when(KeywordBeat && !DemandResponseDone) {
              ResponseFault := true.B
              ResponseFaultResp := io.AXI.R.RRESP
              ResponseValid := true.B
            }
          }
          when(!BeatError) {
            // flush期间或之后返回的数据可以满足需求load，但不得重新填充
            // 已失效的缓存行。
            when(
              !RefillError && !suppressFill && !io.flush &&
                !StoreConflictsRefill
            ) {
              tag(ReqIndexSafe) := ReqTagReg
              if (WordsPerBlock > 1) {
                data(ReqIndexSafe)(RefillCount) := io.AXI.R.RDATA
              } else {
                data(ReqIndexSafe)(0) := io.AXI.R.RDATA
              }
              RefillWordValid :=
                RefillWordValid | UIntToOH(RefillCount, WordsPerBlock)
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
          when(LastRefillWord) {
            when(io.AXI.R.RLAST) {
              valid(ReqIndexSafe) :=
                !suppressFill && !io.flush && !StoreConflictsRefill &&
                  !RefillError && !BeatError
              RefillActive := false.B
              RefillBurst := false.B
              RefillWordValid := 0.U
              suppressFill := false.B
              state := SIdle
            }.otherwise {
              // 预期的最后一拍到达时没有RLAST。仲裁器仍持有此响应期间，
              // 不得让load退休；应使缓存行失效并持续排空，直到迟到的RLAST出现。
              valid(ReqIndexSafe) := false.B
              RefillError := true.B
              RefillWordValid := 0.U
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
              RefillWordValid := 0.U
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
        RefillWordValid := 0.U
        suppressFill := false.B
        state := SIdle
      }
    }
  }
  when(io.flush) { // DMA/其他外部一致性事件使全部数据缓存行失效
    valid.foreach(_ := false.B)
    RefillWordValid := 0.U
    when(state =/= SIdle) { suppressFill := true.B }
  }
}
