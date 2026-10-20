package ysyx_26030103.cache
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.common._
class ysyx_26030103_ICacheSRAM(
    Enable: Boolean = true, // false=不做缓存, 每次取指直接发单拍AXI读(和数据通路一样)
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    AddressWidth: Int = 32,
    Vipt: Boolean = false,
    // true=命中响应的第一拍旁路SRAM输出(保持1拍延迟);
    // false=老实打一拍(响应晚一拍), 用于量化"不旁路"的性能代价
    Bypass: Boolean = true,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_ICacheBase(AddressWidth) {
  val TagBits = AddressWidth - IndexBits - BlockSizeLog2
  val NumBlocks = 1 << IndexBits
  val WordsPerBlock = 1 << (BlockSizeLog2 - 2) // 块大小 ÷ 4字节
  val WordCntBits = log2Ceil(WordsPerBlock) // 块内字数计数器位宽
  // 直接映射cache存储阵列：valid/tag留在触发器阵列（小、需要复位、判定命中不占端口），
  // data换成同步读SRAM(SyncReadMem)：第N拍给地址和读使能，第N+1拍数据有效。
  // valid/tag在关闭缓存时只留1项占位；data SRAM按满容量描述，禁用模式下不会被访问。
  private val ArrayBlocks = if (Enable) NumBlocks else 1
  val valid = RegInit(VecInit(Seq.fill(ArrayBlocks)(false.B)))
  val tag = Reg(Vec(ArrayBlocks, UInt(TagBits.W)))
  val dataMem = SyncReadMem(NumBlocks * WordsPerBlock, UInt(32.W))
  val indexAddr = if (Vipt) io.fetch_vaddr else io.fetch_addr
  val index = indexAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  // Enable=false时阵列只有一个综合占位项。用Scala期的常量索引访问它，
  // 避免把完整的组索引接到单项Vec上产生W004；Enable=true时仍是原始索引，
  // 不在命中路径增加任何截断或选择逻辑。
  val IndexSafe = if (Enable) index else 0.U(IndexBits.W)
  val blockOffset =
    if (WordsPerBlock > 1) indexAddr(BlockSizeLog2 - 1, 2)
    else 0.U
  val reqTag = io.fetch_addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  // SRAM字地址：组索引与块内word偏移拼成一个线性地址。
  private def WordAddress(BlockIndex: UInt, WordOffset: UInt): UInt =
    if (WordsPerBlock > 1) Cat(BlockIndex, WordOffset) else BlockIndex
  private def AddressInRegion(
      Address: UInt,
      Region: ysyx_26030103_PMARegion
  ): Bool = {
    val Extended = Cat(0.U(1.W), Address)
    // 未启用 C 扩展的 RV32 每次都取一条完整的 4 字节指令。如果只检查
    // 首字节，位于非规则大小可执行区域末尾的请求就可能在 Xbar 拒绝前
    // 越过 EndExclusive。
    val LastByte = Extended + 3.U((AddressWidth + 1).W)
    Extended >= BigInt(Region.Base).U((AddressWidth + 1).W) &&
    LastByte < BigInt(Region.EndExclusive).U((AddressWidth + 1).W)
  }
  private def AddressInRegions(
      Address: UInt,
      Regions: Seq[ysyx_26030103_PMARegion]
  ): Bool =
    Regions
      .map(AddressInRegion(Address, _))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  private val ExecutableRegions = PMARegions.filter(_.Executable)
  private val CacheableRegions = PMARegions.filter(r => r.Executable && r.Cacheable)
  require(ExecutableRegions.nonEmpty, "ICache至少需要一个可执行PMA区域")
  require(
    CacheableRegions.forall(r =>
      r.Size >= (1L << BlockSizeLog2) &&
        (r.Base & ((1L << BlockSizeLog2) - 1L)) == 0L &&
        (r.Size & ((1L << BlockSizeLog2) - 1L)) == 0L
    ),
    "可缓存PMA区域必须按cache line对齐且不得小于一个cache line"
  )

  // 可执行权限和可缓存属性是两个独立的PMA属性。非法取指在本模块
  // 直接形成instruction access fault，不允许向MMIO/CLINT/地址空洞发AR。
  val executable = AddressInRegions(io.fetch_addr, ExecutableRegions)
  val cacheable =
    if (Enable) AddressInRegions(io.fetch_addr, CacheableRegions) else false.B
  // 两级流水: s1受理级(寄存请求,受理拍发起SRAM读) -> 响应级(下一拍SRAM数据旁路到响应,缺失走refill)
  val s1_valid = RegInit(false.B)
  val s1_hit = Reg(Bool()) // 受理时判定: 可缓存且命中
  val s1_ready = RegInit(false.B) // 缺失的关键词已返回(early restart)或refill已完成
  val fetch_addr_reg = Reg(UInt(AddressWidth.W))
  val fetch_index_reg = Reg(UInt(IndexBits.W))
  val fetch_offset_reg = Reg(UInt((BlockSizeLog2 - 2).W)) // 请求时块内word偏移
  val FetchIndexSafe =
    if (Enable) fetch_index_reg else 0.U(IndexBits.W)
  val FetchOffsetSafe =
    if (WordsPerBlock > 1) fetch_offset_reg else 0.U
  val resp_data_reg = Reg(UInt(32.W))
  // SRAM数据晚一拍：命中响应的第一拍直接旁路SRAM输出，同时锁存到resp_data_reg，
  // 之后被下游反压时用锁存值保证响应数据稳定。
  val resp_data_held = RegInit(false.B)
  val cacheable_reg = RegInit(false.B)
  val access_fault_reg = RegInit(false.B)
  val access_fault_resp_reg = RegInit(0.U(2.W))
  // refill状态机(单MSHR: 同一时刻只处理一个缺失)
  val rfstates = Enum(5)
  val rf_idle = rfstates(0)
  val rf_req = rfstates(1)
  val rf_resp = rfstates(2)
  val rf_drain = rfstates(3)
  val rf_drain_fault = rfstates(4)
  val rfstate = RegInit(rf_idle)
  // 排空超时（AXI要求从设备给RLAST；违规时不能永久停在排空态）。
  // 超时后转入 rf_drain_fault：继续吸收迟到拍防串扰，同时让取指以
  // 故障响应继续推进，而不是整核挂死。
  val drain_timer = RegInit(0.U(10.W))
  val drain_retry_timer = RegInit(0.U(10.W))
  val bus_fault = RegInit(false.B)
  // refill专属寄存器。refill期间只能受理同一line中已返回的word，
  // 未返回word和其他line在单MSHR结构下继续反压。
  val ref_addr = Reg(UInt(AddressWidth.W))
  val ref_vindex = Reg(UInt(IndexBits.W))
  val ref_index = if (Vipt) ref_vindex else ref_addr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val RefIndexSafe = if (Enable) ref_index else 0.U(IndexBits.W)
  val ref_tag = ref_addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val ref_offset =
    if (WordsPerBlock > 1) ref_addr(BlockSizeLog2 - 1, 2)
    else 0.U
  val ref_cacheable = Reg(Bool())
  val refill_cnt = RegInit(0.U(WordCntBits.W)) // 当前正在填充第几个word
  val RefillOffsetSafe = if (WordsPerBlock > 1) refill_cnt else 0.U
  // 只有RRESP成功且已握手的word才能被early-restart后的新fetch使用。
  // line valid仍要等所有beat成功后才会置位。
  val refill_word_valid = RegInit(0.U(WordsPerBlock.W))
  val burst_mode = RegInit(false.B) // 突发传输模式标志
  // AXI规定VALID一旦对外呈现，在READY前不能撤回。该位区分
  // “刚进rf_req且本拍kill”与“ARVALID已经反压了一拍”。
  val ar_exposed = RegInit(false.B)
  // 在飞refill是否仍属于当前s1里的请求: 原请求被消费/冲刷/替换后,
  // 后续R拍只写阵列, 绝不再写s1的响应寄存器(那会污染新请求)
  // s1_ready为true时响应已经对外可见，即使尚未握手也不能在反压下修改
  // payload。因此迟到的协议错误只能替换尚未可见的demand响应。
  val s1_waits = RegInit(false.B)
  // flush(fence.i)/kill不能丢弃已被从机接受的AXI事务: 置位后继续把剩余拍排空,
  // 否则残留的R拍会被后续突发误收, 造成块内数据错位
  val discard = RegInit(false.B)
  // 一条cache line的refill错误状态。RRESP错误可能出现在关键词之后，
  // 因而不能只看最后一个beat决定valid；任意一个错误都使整条line失效。
  val refill_error = RegInit(false.B)
  // 正在refill的索引不能走普通valid/tag命中；半填行只能通过
  // refill_word_valid的逐word许可通路访问。
  val refill_busy = rfstate =/= rf_idle
  val under_refill = refill_busy && ref_cacheable && (index === ref_index)
  val hit = valid(IndexSafe) && tag(IndexSafe) === reqTag && !under_refill
  val refill_line_match =
    io.fetch_addr(AddressWidth - 1, BlockSizeLog2) ===
      ref_addr(AddressWidth - 1, BlockSizeLog2)
  val requested_word_arrived =
    if (WordsPerBlock > 1) refill_word_valid(blockOffset)
    else refill_word_valid(0)
  val refill_r_fire = rfstate === rf_resp && io.axi.R.RVALID
  val malformed_refill_beat_now =
    refill_r_fire && !io.axi.R.RLAST &&
      (!ref_cacheable || !burst_mode || refill_cnt === (WordsPerBlock - 1).U)
  val refill_error_now =
    refill_r_fire && (io.axi.R.RRESP =/= 0.U || malformed_refill_beat_now)
  val partial_refill_hit =
    (rfstate === rf_req || rfstate === rf_resp) &&
      ref_cacheable && !discard && !refill_error && !refill_error_now &&
      refill_line_match && requested_word_arrived
  val request_hit = executable && cacheable && (hit || partial_refill_hit)
  io.access_fault := access_fault_reg
  io.access_fault_resp := access_fault_resp_reg
  io.axi.AW.AWVALID := false.B
  io.axi.AW.AWID := 0.U
  io.axi.AW.AWADDR := 0.U
  io.axi.AW.AWLEN := 0.U
  io.axi.AW.AWSIZE := 2.U
  io.axi.AW.AWBURST := 1.U
  io.axi.AW.AWCACHE := 0.U
  io.axi.AW.AWQOS := 0.U
  io.axi.AW.AWPROT := 0.U
  io.axi.W.WVALID := false.B
  io.axi.W.WDATA := 0.U
  io.axi.W.WSTRB := 0.U
  io.axi.W.WLAST := false.B
  io.axi.B.BREADY := false.B
  io.axi.AR.ARVALID := false.B
  io.axi.AR.ARID := 0.U
  io.axi.AR.ARADDR := 0.U
  io.axi.AR.ARLEN := 0.U
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.AR.ARCACHE := 0.U
  io.axi.AR.ARQOS := 0.U
  io.axi.R.RREADY := true.B // 始终接收并排空 AXI 响应
  // 响应级: 命中在Bypass模式第一拍直接旁路SRAM输出、
  // 否则等数据锁存就绪后再响应(晚一拍);
  // 关键词返回/refill完成(s1_ready)的缺失用R拍锁存的数据; 错误一律给NOP
  val hitResponding = if (Bypass) s1_hit else s1_hit && resp_data_held
  val responding = s1_valid && (hitResponding || s1_ready)
  io.resp_valid := responding
  io.resp_addr := fetch_addr_reg
  io.resp_fault := access_fault_reg
  // 受理级: 响应槽为空,或本拍响应正被接收。refill中可放行
  // 其他index的完整命中，以及同line已返回word的partial hit；两者的
  // 数据都在受理拍发起SRAM读，下一拍旁路到响应并同时锁存。
  val response_slot_available =
    !s1_valid || (responding && io.resp_ready)
  io.fetch_ready :=
    !io.flush &&
      !io.kill &&
      response_slot_available &&
      (rfstate === rf_idle || rfstate === rf_drain_fault || request_hit)
  val accept = io.fetch_valid && io.fetch_ready
  io.perf_hit := accept && request_hit
  // 非缓存取指（ICACHE=n 或未来的可执行不可缓存区）不是 miss。
  io.perf_miss := accept && executable && cacheable && !request_hit
  io.perf_refill_req := rfstate === rf_req
  io.perf_refill_resp := rfstate === rf_resp
  when(responding && io.resp_ready) {
    s1_valid := false.B
    s1_waits := false.B
  }
  when(accept) {
    fetch_addr_reg := io.fetch_addr
    fetch_index_reg := index
    fetch_offset_reg := blockOffset
    cacheable_reg := cacheable
    s1_hit := request_hit
    // PMA拒绝不需要总线往返；用DECERR作为调试响应码，并让带故障的
    // NOP响应沿正常流水线到达EXU，在提交点精确写入mepc/mcause。
    s1_ready := !executable
    access_fault_reg := !executable
    access_fault_resp_reg := Mux(executable, 0.U, 3.U)
    s1_valid := true.B
    s1_waits := false.B
    // 总线已超时：不再发AR，直接以故障响应结束本次取指（命中仍可正常
    // 服务，数据在阵列里不需要总线）。
    when(bus_fault && executable && !request_hit) {
      s1_ready := true.B
      access_fault_reg := true.B
      access_fault_resp_reg := 2.U
    }
  }
  // 排队缺失: refill空闲时启动; 启动前重查命中(在飞refill可能刚把这行填好)
  val queued_miss = s1_valid && !s1_hit && !s1_ready
  val recheck_hit = cacheable_reg &&
    valid(FetchIndexSafe) &&
    tag(FetchIndexSafe) === fetch_addr_reg(
      AddressWidth - 1,
      IndexBits + BlockSizeLog2
    )
  when(queued_miss && rfstate === rf_idle && !io.kill && !io.flush &&
      !bus_fault) {
    when(recheck_hit) {
      s1_hit := true.B
    }.otherwise {
      rfstate := rf_req
      ref_addr := fetch_addr_reg
      ref_vindex := fetch_index_reg
      ref_cacheable := cacheable_reg
      refill_cnt := 0.U
      refill_word_valid := 0.U
      ar_exposed := false.B
      burst_mode := cacheable_reg
      // 每次新refill都从干净的事务状态开始，不能继承上一次flush/discard
      // 或RRESP错误；旧目标行在AR握手时才失效，避免取消的
      // 错路请求白白驱逐原有行。
      discard := false.B
      refill_error := false.B
      s1_waits := true.B
    }
  }
  // SRAM读口: 受理命中(含partial hit)与"排队缺失重查命中"两处需要读数据。
  // 两者不会同拍发生(前者要求响应槽可复用, 后者要求槽中仍是未就绪的缺失),
  // 因此共用唯一读端口。
  val acceptRead = accept && request_hit
  val recheckRead =
    queued_miss && rfstate === rf_idle && !io.kill && !io.flush && recheck_hit
  val sramReadEnable = acceptRead || recheckRead
  val sramReadAddr = Mux(
    acceptRead,
    WordAddress(IndexSafe, blockOffset),
    WordAddress(FetchIndexSafe, FetchOffsetSafe)
  )
  val sramReadData = dataMem.read(sramReadAddr, sramReadEnable)
  // SRAM数据到达(受理/重查命中的下一拍)时锁存; 旁路模式同拍直接输出,
  // 非旁路模式下一拍才作为响应。resp_data_held表示锁存已就绪。
  val respDataArrives = s1_valid && s1_hit && !resp_data_held
  val respDataFire = responding && io.resp_ready
  val respDataSelected = if (Bypass) {
    Mux(s1_hit, Mux(respDataArrives, sramReadData, resp_data_reg), resp_data_reg)
  } else {
    resp_data_reg
  }
  io.resp_data := Mux(access_fault_reg, "h00000013".U, respDataSelected)
  when(respDataArrives) {
    resp_data_reg := sramReadData
  }
  when(!s1_valid || respDataFire) {
    resp_data_held := false.B
  }.elsewhen(respDataArrives) {
    resp_data_held := true.B
  }
  // 注意: refill启动刻意不早于s1_valid寄存后一拍——启动条件里的!io.kill
  // 能抑制错误路径取指的refill(误预测时kill比受理晚一拍到)。曾经试过受理即启动,
  // 结果误预测窗口内的错误路径取指把无用refill发上总线并驱逐有用行, microbench
  // 缺失+70%, 总周期+10.6%, 已回退。
  switch(rfstate) {
    is(rf_req) {
      // redirect/exception在AR尚未被时钟采样为pending时可以取消错路
      // refill。ARVALID若已被反压过，即使还没握手也必须保持到READY。
      val cancel_unaccepted =
        io.kill && s1_waits && !ar_exposed && !io.flush
      io.axi.AR.ARVALID := !cancel_unaccepted
      io.axi.AR.ARLEN := Mux(
        ref_cacheable && burst_mode,
        (WordsPerBlock - 1).U,
        0.U
      )
      io.axi.AR.ARADDR := Mux(
        ref_cacheable,
        Mux(
          burst_mode,
          Cat(ref_addr(AddressWidth - 1, BlockSizeLog2), 0.U(BlockSizeLog2.W)),
          Cat(ref_addr(AddressWidth - 1, BlockSizeLog2), refill_cnt, 0.U(2.W))
        ),
        ref_addr
      )
      when(cancel_unaccepted) {
        burst_mode := false.B
        discard := false.B
        refill_word_valid := 0.U
        ar_exposed := false.B
        rfstate := rf_idle
      }.elsewhen(io.axi.AR.ARREADY) {
        // 延迟到AR真正接受后才失效被替换行；因此未握手的
        // 错路refill被取消时，不会白白驱逐原有有效行。
        // 已展示但在握手前被kill的owner AR必须完成协议，但其
        // discard事务既不写新line，也不应驱逐原victim。
        val killed_owner_now = io.kill && s1_waits
        when(ref_cacheable && !discard && !killed_owner_now) {
          valid(RefIndexSafe) := false.B
        }
        ar_exposed := false.B
        rfstate := rf_resp
      }.otherwise {
        // 本拍ARVALID已对外呈现但被反压；之后即使kill到来也必须
        // 保持地址和VALID，等握手后再排空响应。
        ar_exposed := true.B
      }
    }
    is(rf_resp) {
      io.axi.R.RREADY := true.B
      when(io.axi.R.RVALID && io.axi.R.RREADY) {
        val r_error = io.axi.R.RRESP =/= 0.U
        // 非缓存访问只有一个beat；缓存访问则只有请求所在的关键词beat
        // 能把错误归属给当前s1请求，其他beat的错误仅记录为line错误。
        val keyword_beat = !ref_cacheable || (refill_cnt === ref_offset)

        // 错误必须在discard时也记录（事务仍在排空），但不能把被flush的
        // 响应重新写回s1。sticky状态保证后续成功beat不能重新置valid。
        when(r_error) {
          refill_error := true.B
          // burst中任一beat失败后，整条line的partial-hit许可立即
          // 撤销；不能再利用早先成功beat的半填数据。
          refill_word_valid := 0.U
          when(!discard && s1_waits && keyword_beat) {
            access_fault_reg := true.B
            access_fault_resp_reg := io.axi.R.RRESP
            resp_data_reg := "h00000013".U // NOP, 不让下游拿到非法指令
          }
        }.elsewhen(!discard) {
          // SRAM不能在响应拍组合读数据: 关键词拍必须把数据锁存到resp_data_reg,
          // 供early-restart/s1_ready后的响应使用。命中新请求的数据在
          // "第一拍旁路"时才锁存, 与该路径不同拍, 不会互相覆盖。
          when(s1_waits && !ref_cacheable) {
            resp_data_reg := io.axi.R.RDATA
          }
          when(s1_waits && ref_cacheable && keyword_beat) {
            resp_data_reg := io.axi.R.RDATA
          }
          when(ref_cacheable) { // 阵列填充无条件进行, 数据是真实内存内容
            tag(RefIndexSafe) := ref_tag
            dataMem.write(
              WordAddress(RefIndexSafe, RefillOffsetSafe),
              io.axi.R.RDATA
            )
            when(!refill_error) {
              refill_word_valid := refill_word_valid |
                UIntToOH(refill_cnt, WordsPerBlock)
            }
            // 只有整条line无任何错误时才允许变为valid；当前beat为成功
            // 响应，refill_error覆盖此前已经发生的错误。
            when(
              refill_cnt === (WordsPerBlock - 1).U &&
                io.axi.R.RLAST && !refill_error
            ) {
              valid(RefIndexSafe) := true.B
            }
          }
        }
        // early restart: 关键词所在拍到手即可响应, 无需等整行填完
        when(!discard && s1_waits && keyword_beat) {
          s1_ready := true.B
        }
        when(ref_cacheable && burst_mode) {
          refill_cnt := refill_cnt + 1.U
          when(io.axi.R.RLAST) {
            // 突发结束(正常结束或被从机提前截断)
            when(discard) {
              discard := false.B
              rfstate := rf_idle
            }.elsewhen(refill_cnt === (WordsPerBlock - 1).U) {
              when(s1_waits) {
                s1_ready := true.B
              }
              rfstate := rf_idle
            }.otherwise {
              burst_mode := false.B // 提前RLAST: 剩余的词改单拍补取
              rfstate := rf_req
            }
          }.elsewhen(refill_cnt === (WordsPerBlock - 1).U) {
            // 已收到ARLEN指定的最后一拍却没有RLAST，该line绝不能安装。
            // 继续排空R通道直到迟到的RLAST，避免它被后续refill误收。
            valid(RefIndexSafe) := false.B
            refill_error := true.B
            refill_word_valid := 0.U
            when(!discard && s1_waits && !s1_ready) {
              access_fault_reg := true.B
              access_fault_resp_reg := Mux(r_error, io.axi.R.RRESP, 2.U)
              resp_data_reg := "h00000013".U
              s1_ready := true.B
            }
            rfstate := rf_drain
            drain_timer := 0.U
          }
        }.elsewhen(ref_cacheable) {
          // 提前RLAST后的补取是独立单拍事务，每一拍也必须以RLAST结束。
          when(io.axi.R.RLAST) {
            when(discard) {
              discard := false.B
              rfstate := rf_idle
            }.elsewhen(refill_cnt === (WordsPerBlock - 1).U) {
              when(s1_waits) {
                s1_ready := true.B
              }
              rfstate := rf_idle
            }.otherwise {
              refill_cnt := refill_cnt + 1.U
              rfstate := rf_req
            }
          }.otherwise {
            valid(RefIndexSafe) := false.B
            refill_error := true.B
            refill_word_valid := 0.U
            when(!discard && s1_waits && !s1_ready) {
              access_fault_reg := true.B
              access_fault_resp_reg := Mux(r_error, io.axi.R.RRESP, 2.U)
              resp_data_reg := "h00000013".U
              s1_ready := true.B
            }
            rfstate := rf_drain
            drain_timer := 0.U
          }
        }.otherwise {
          // 非缓存区: 一词一拍, 当前拍即结束
          when(io.axi.R.RLAST) {
            when(discard) {
              discard := false.B
              rfstate := rf_idle
            }.otherwise {
              when(s1_waits) {
                s1_ready := true.B
              }
              rfstate := rf_idle
            }
          }.otherwise {
            when(!discard && s1_waits && !s1_ready) {
              access_fault_reg := true.B
              access_fault_resp_reg := Mux(r_error, io.axi.R.RRESP, 2.U)
              resp_data_reg := "h00000013".U
              s1_ready := true.B
            }
            rfstate := rf_drain
            drain_timer := 0.U
          }
        }
      }
    }
    is(rf_drain) {
      io.axi.R.RREADY := true.B
      when(io.axi.R.RVALID && io.axi.R.RLAST) {
        discard := false.B
        burst_mode := false.B
        rfstate := rf_idle
      }.elsewhen(drain_timer === 1023.U) {
        bus_fault := true.B
        drain_timer := 0.U
        drain_retry_timer := 0.U
        rfstate := rf_drain_fault
      }.otherwise {
        drain_timer := drain_timer + 1.U
      }
    }
    is(rf_drain_fault) {
      // 继续以 RREADY 吸收违规从设备的迟到拍，直到 RLAST 使总线重新同步；
      // 期间新取指由受理路径直接以故障响应结束。通道安静时周期性重试
      // refill（清故障标记回空闲）：从设备恢复后系统自动复原，不必等复位。
      io.axi.R.RREADY := true.B
      when(io.axi.R.RVALID && io.axi.R.RLAST) {
        bus_fault := false.B
        rfstate := rf_idle
      }.elsewhen(drain_retry_timer === 1023.U && !io.axi.R.RVALID) {
        bus_fault := false.B
        drain_retry_timer := 0.U
        rfstate := rf_idle
      }.otherwise {
        drain_retry_timer := drain_retry_timer + 1.U
      }
    }
  }
  // kill(重定向/异常): 杀响应槽(s1)。未展示的owner AR可直接取消；
  // 已展示但未握手的owner AR必须排空但不安装，也不驱逐victim；
  // 已握手的refill继续填充。已脱离s1的后台fill不受无关kill影响。
  when(io.kill) {
    // 只有当前s1仍是这个refill的demand owner时，kill才能改变
    // refill。early response已消费后，s1_waits=false，之后无关分支的kill
    // 不能丢弃后台fill。只有已展示但未握手的AR需要sticky discard；
    // 已握手的rf_resp继续完成有效line，避免留下必然miss的空index。
    val kill_owned_exposed_ar =
      s1_waits && rfstate === rf_req && ar_exposed
    s1_valid := false.B
    s1_waits := false.B
    when(kill_owned_exposed_ar) {
      discard := true.B
      refill_word_valid := 0.U
    }
  }
  // flush(fence.i): 代码可能已被改写,清cache行,在飞事务排空丢弃
  // 注意: 已发出的ARVALID绝不能在握手前撤回(否则仲裁器授权后等不到fire会死锁),
  // 必须保持请求直到AR完成,再转入排空丢弃数据
  when(io.flush) {
    s1_valid := false.B
    s1_waits := false.B
    refill_word_valid := 0.U
    valid.foreach(_ := false.B)
    when(rfstate === rf_req) {
      discard := true.B
    }.elsewhen(rfstate === rf_resp && !discard) {
      discard := true.B
    }
  }
}
