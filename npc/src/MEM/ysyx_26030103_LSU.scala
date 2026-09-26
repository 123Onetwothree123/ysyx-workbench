package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._
// LSU是访存流水级：完成总线事务、提交访存故障并冲刷年轻指令
class ysyx_26030103_LSU(
    val WBufDepth: Int = 4, // 写缓冲项数(必须是2的幂, 环形队列按位回绕)
    val DCacheEnable: Boolean = false,
    val DCacheBlockSizeLog2: Int = 4,
    val DCacheIndexBits: Int = 5,
    val DCacheableBase: Long = 0x80000000L,
    val DCacheableMask: Long = 0x80000000L,
    val DCachePMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)
    // 给EXU: 本级非空(有访存或完成中的指令),EXU的副作用指令要等着
    val Busy = Output(Bool())
    // 只表示仍可能失败或尚未完成的访存/写缓冲。普通 ALU 指令在本级
    // 单拍直通时不会拉高它，供控制转移避免无谓等待。
    val MemoryBusy = Output(Bool())
    // 访存故障提交(CSR后门)
    val MemTrapCommit = Output(Bool())
    val MemTrapCause = Output(UInt(32.W))
    val MemTrapPC = Output(UInt(32.W))
    // 访存故障提交时冲刷IDU→EXU和EXU→LSU流水寄存器(里面全是年轻指令)
    val FlushIDEX = Output(Bool())
    val FlushEXMEM = Output(Bool())
    // 直接连总线
    val DataBus = new ysyx_26030103_AXI5IO(32)
    val DCacheFlush = Input(Bool()) // fence.i使写直达数据缓存失效
    // DCache性能计数器
    val DCachePerfHit = Output(Bool())
    val DCachePerfMiss = Output(Bool())
    val DCachePerfRefillReq = Output(Bool())
    val DCachePerfRefillResp = Output(Bool())
    // 给IDU做数据冒险检测和转发用
    val HazardValid = Output(Bool())
    val HazardRd = Output(UInt(5.W))
    val HazardRegWrite = Output(Bool())
    val HazardMemOp = Output(Bool())
    val FwdData = Output(UInt(32.W))
    val FwdReady = Output(Bool())
    // 等待槽冒险检测: 本级被年长访存占用时,EX/MEM流水寄存器里等待的指令
    // 对IDU也必须可见,否则依赖它的消费者会拿着旧寄存器值溜过去
    val Hazard2Valid = Output(Bool())
    val Hazard2Rd = Output(UInt(5.W))
    val Hazard2RegWrite = Output(Bool())
    val Hazard2MemOp = Output(Bool())
    val Hazard2FwdData = Output(UInt(32.W))
    val Hazard2FwdReady = Output(Bool())
    // 调试/性能
    val StallWaitLSU = Output(Bool())
    val Complete = Output(Bool())
    val AccessFault = Output(Bool())
    val AccessFaultResp = Output(UInt(2.W))
    val Active = Output(Bool())
    val IsStore = Output(Bool())
    val StallReadAR = Output(Bool())
    val StallReadR = Output(Bool())
    val StallWriteReq = Output(Bool())
    val StallWriteB = Output(Bool())
    val DebugMemoryWrite = Output(Bool())
    val DebugPC = Output(UInt(32.W))
    val DebugALUResult = Output(UInt(32.W))
    val DebugStoreDATA = Output(UInt(32.W))
    val DebugLoadDATA = Output(UInt(32.W))
    val DebugWidthSelect = Output(UInt(2.W))
  })
  val DCache = Module(
    new ysyx_26030103_DCache(
      Enable = DCacheEnable,
      BlockSizeLog2 = DCacheBlockSizeLog2,
      IndexBits = DCacheIndexBits,
      AddressWidth = 32,
      CacheableBase = DCacheableBase,
      CacheableMask = DCacheableMask,
      PMARegions = DCachePMARegions
    )
  )
  io.DCachePerfHit := DCache.io.perf_hit
  io.DCachePerfMiss := DCache.io.perf_miss
  io.DCachePerfRefillReq := DCache.io.perf_refill_req
  io.DCachePerfRefillResp := DCache.io.perf_refill_resp
  // 阶段级FSM: 接受/等待/退休
  val StageStates = Enum(3)
  val StageIdle = StageStates(0)
  val StageWait = StageStates(1)
  val StageDone = StageStates(2)
  val stageState = RegInit(StageIdle)
  val MsgReg = Reg(chiselTypeOf(io.in.bits))
  // WBufDepth 表示 LSU 最多能容纳的未退休 store 数。MsgReg/PendingStore
  // 保存架构退休顺序；后面的 wb* 队列同时保存每项总线载荷。总线只发最老项，
  // 且必须收到成功 BRESP 才会前移到下一项。因此失败队首之前所有年轻 store
  // 都只有缓冲状态、没有外部副作用，可以整体撤销并维持精确异常。
  private val StorePtrWidth = log2Ceil(WBufDepth).max(1)
  private val StoreCountWidth = log2Ceil(WBufDepth + 1)
  val PendingStore = Reg(Vec(WBufDepth, chiselTypeOf(io.in.bits)))
  val PendingStoreHead = RegInit(0.U(StorePtrWidth.W))
  val PendingStoreTail = RegInit(0.U(StorePtrWidth.W))
  val PendingStoreCount = RegInit(0.U(StoreCountWidth.W))
  val PendingStoreValid = PendingStoreCount =/= 0.U
  val PendingStoreHeadBits =
    if (WBufDepth == 1) PendingStore(0) else PendingStore(PendingStoreHead)
  val LoadDataReg = RegInit(0.U(32.W))
  val AccessFaultReg = RegInit(false.B)
  val AccessFaultRespReg = RegInit(0.U(2.W))
  val StoreFaultReg = RegInit(false.B) // 等待B响应时锁存写访问错误
  // 地址非对齐和 AXI access fault 是两种不同的异常。单独锁存前者，
  // 避免调试端把 cause=4/6 误报成 RESP=0 的总线故障。
  val MisalignedReg = RegInit(false.B)
  val MemFaultReg = RegInit(false.B) // 锁存总线完成时的访存错误
  // 总线状态机在真正完成的握手拍直接通知流水级，避免先进入一个 Done
  // 状态、再让外层状态机晚一拍观察到它。
  val TransactionComplete = WireDefault(false.B)
  val TransactionFault = WireDefault(false.B)
  // load 数据一旦在响应通道有效即可前递给 IDU；寄存后的 LoadDataReg
  // 仍是退休消息的稳定数据源。
  val LoadForwardValid = WireDefault(false.B)
  val LoadForwardData = WireDefault(LoadDataReg)
  val LoadForwardFault = WireDefault(false.B)
  val StageIsIdle = stageState === StageIdle
  val StageInputBits = Wire(chiselTypeOf(io.in.bits))
  StageInputBits := Mux(PendingStoreValid, PendingStoreHeadBits, io.in.bits)
  val StageInputValid = Mux(PendingStoreValid, true.B, io.in.valid)
  val StageInputReady = WireDefault(false.B)
  val StageInputFire = StageInputValid && StageInputReady
  val IsMemOp = StageInputBits.MemoryValid
  val StageCanReplaceDone =
    stageState === StageDone && io.out.ready && !MemFaultReg
  val startMem =
    (StageIsIdle || StageCanReplaceDone) && StageInputFire && IsMemOp
  when(startMem) {
    MsgReg := StageInputBits
  }
  val ActiveInstruction = Mux(StageIsIdle, StageInputBits, MsgReg)
  private def AddressIsMisaligned(Address: UInt, Width: UInt): Bool =
    Mux(
      Width === "b10".U,
      Address(1, 0) =/= 0.U,
      Mux(Width === "b01".U, Address(0), false.B)
    )
  val StageInputMisaligned = AddressIsMisaligned(
    StageInputBits.ALUResult,
    StageInputBits.WidthSelect
  )
  when(startMem) {
    MisalignedReg := StageInputMisaligned
  }
  when(stageState === StageWait && TransactionComplete) {
    MemFaultReg := TransactionFault
  }.elsewhen(startMem) {
    MemFaultReg := StageInputMisaligned
  }
  when(
    stageState === StageDone && io.out.ready &&
      (!StageInputFire || !IsMemOp)
  ) {
    MemFaultReg := false.B
  }
  val MemTrapCommit = stageState === StageDone && MemFaultReg && io.out.ready
  io.MemTrapCommit := MemTrapCommit
  io.MemTrapCause := Mux(
    MisalignedReg,
    Mux(MsgReg.MemoryWrite, 6.U(32.W), 4.U(32.W)), // 地址非对齐：写=6，读=4
    Mux(MsgReg.MemoryWrite, 7.U(32.W), 5.U(32.W)) // 访问故障：写=7，读=5
  )
  io.MemTrapPC := MsgReg.pc
  io.FlushIDEX := MemTrapCommit
  io.FlushEXMEM := MemTrapCommit
  io.out.valid := false.B
  switch(stageState) {
    is(StageIdle) {
      when(StageInputValid && IsMemOp) {
        StageInputReady := true.B
        when(StageInputFire) {
          stageState := Mux(StageInputMisaligned, StageDone, StageWait)
        }
      }.otherwise {
        // 非访存指令单拍直通
        StageInputReady := io.out.ready
        io.out.valid := StageInputValid
      }
    }
    is(StageWait) {
      when(TransactionComplete) {
        stageState := StageDone
      }
    }
    is(StageDone) {
      io.out.valid := true.B
      // A successful completed instruction may be replaced in the same cycle
      // that it leaves the stage.  Memory replacements enter StageWait;
      // non-memory replacements are held as an already completed StageDone item.
      StageInputReady := io.out.ready && !MemFaultReg
      when(io.out.fire) {
        when(StageInputFire) {
          when(IsMemOp) {
            stageState := Mux(StageInputMisaligned, StageDone, StageWait)
          }.otherwise {
            MsgReg := StageInputBits
            stageState := StageDone
          }
        }.otherwise {
          stageState := StageIdle
        }
      }
    }
  }
  val PendingStorePop =
    (StageIsIdle || StageCanReplaceDone) && PendingStoreValid && StageInputFire
  val CurrentStoreBuffered = stageState =/= StageIdle &&
    MsgReg.MemoryValid && MsgReg.MemoryWrite
  val StoreSlotsUsed = PendingStoreCount + CurrentStoreBuffered.asUInt
  val PendingStoreSpace = StoreSlotsUsed < WBufDepth.U || PendingStorePop
  // 有排队项时内部队首优先进入执行槽；同拍仍可把新的连续 store 放到
  // 队尾。load/ALU/分支等保持在上游，不能越过尚未确认成功的 store。
  val CanQueueYoungerStore =
    (CurrentStoreBuffered || PendingStoreValid) &&
      io.in.bits.MemoryValid && io.in.bits.MemoryWrite &&
      !MemFaultReg && !StoreFaultReg && PendingStoreSpace
  val ExternalFeedsStage =
    !PendingStoreValid && (StageIsIdle || StageCanReplaceDone)
  io.in.ready := Mux(
    ExternalFeedsStage,
    StageInputReady,
    CanQueueYoungerStore
  )
  val PendingStorePush = io.in.fire &&
    !(ExternalFeedsStage && StageInputFire)
  when(PendingStorePush) {
    if (WBufDepth == 1) {
      PendingStore(0) := io.in.bits
      PendingStoreTail := 0.U
    } else {
      PendingStore(PendingStoreTail) := io.in.bits
      PendingStoreTail := PendingStoreTail + 1.U
    }
  }
  when(PendingStorePop) {
    if (WBufDepth == 1) {
      PendingStoreHead := 0.U
    } else {
      PendingStoreHead := PendingStoreHead + 1.U
    }
  }
  when(MemTrapCommit) {
    // 队首访存失败：年轻 store 尚未发总线，可全部安全撤销。
    PendingStoreCount := 0.U
    PendingStoreHead := 0.U
    PendingStoreTail := 0.U
  }.otherwise {
    PendingStoreCount := PendingStoreCount +
      PendingStorePush.asUInt - PendingStorePop.asUInt
  }
  io.out.bits := ActiveInstruction
  io.out.bits.LoadData := LoadDataReg
  io.out.bits.RegisterWrite := ActiveInstruction.RegisterWrite && !MemFaultReg // 读错误时禁止写回
  io.out.bits.Retire := ActiveInstruction.Retire && !MemFaultReg
  // Busy的赋值在写缓冲声明之后(见下)
  io.HazardValid := StageInputValid || (stageState =/= StageIdle)
  io.HazardRd := ActiveInstruction.Rd
  io.HazardRegWrite := ActiveInstruction.RegisterWrite && !MemFaultReg
  io.HazardMemOp := ActiveInstruction.MemoryValid
  // 向译码级转发最终写回值
  io.FwdData := Mux(
    ActiveInstruction.WBSelect === 2.U,
    ActiveInstruction.snpc,
    Mux(
      ActiveInstruction.WBSelect === 3.U,
      ActiveInstruction.CSRReadData,
      Mux(
        ActiveInstruction.WBSelect === 1.U,
        Mux(LoadForwardValid, LoadForwardData, LoadDataReg),
        ActiveInstruction.ALUResult
      )
    )
  )
  io.FwdReady := io.HazardValid && io.HazardRegWrite &&
    (!ActiveInstruction.MemoryValid || stageState === StageDone ||
      (LoadForwardValid && !LoadForwardFault))
  val WaitValid =
    ((stageState =/= StageIdle) || PendingStoreValid) && io.in.valid // 本级忙时等待槽中的年轻指令
  io.Hazard2Valid := WaitValid
  io.Hazard2Rd := io.in.bits.Rd
  io.Hazard2RegWrite := io.in.bits.RegisterWrite
  io.Hazard2MemOp := io.in.bits.MemoryValid
  io.Hazard2FwdData := Mux(
    io.in.bits.WBSelect === 2.U,
    io.in.bits.snpc,
    Mux(
      io.in.bits.WBSelect === 3.U,
      io.in.bits.CSRReadData,
      io.in.bits.ALUResult
    )
  )
  io.Hazard2FwdReady := WaitValid && io.in.bits.RegisterWrite && !io.in.bits.MemoryValid
  io.StallWaitLSU := stageState === StageWait
  io.DebugMemoryWrite := ActiveInstruction.MemoryWrite
  io.DebugPC := ActiveInstruction.pc
  io.DebugALUResult := ActiveInstruction.ALUResult
  io.DebugStoreDATA := ActiveInstruction.StoreData
  io.DebugLoadDATA := LoadDataReg
  io.DebugWidthSelect := ActiveInstruction.WidthSelect
  // 总线事务FSM(原LSU逻辑)
  private def AXISizeFor(Width: UInt): UInt = MuxLookup(Width, 2.U(3.W))(
    Seq("b00".U -> 0.U(3.W), "b01".U -> 1.U(3.W), "b10".U -> 2.U(3.W))
  )
  val AXISize = AXISizeFor(ActiveInstruction.WidthSelect)
  val IncomingAXISize = AXISizeFor(io.in.bits.WidthSelect)
  val StateMachine = Enum(5)
  val StatesIdle = StateMachine(0)
  val StatesReadRequest = StateMachine(1)
  val StatesReadResponse = StateMachine(2)
  val StatesWriteWaitB = StateMachine(3) // store等待属于自己的B响应
  val StatesReadDrain = StateMachine(4) // 非法单拍读缺少RLAST时排空剩余响应
  val state = RegInit(StatesIdle)
  val DCacheRequestActive = RegInit(false.B)
  private def AlignStore(
      StoreData: UInt,
      Address: UInt,
      Width: UInt
  ): (UInt, UInt) = {
    val Data = WireDefault(StoreData)
    val Mask = WireDefault("b1111".U(4.W))
    switch(Width) {
      is("b00".U) {
        switch(Address(1, 0)) {
          is("b00".U) {
            Data := Cat(0.U(24.W), StoreData(7, 0))
            Mask := "b0001".U
          }
          is("b01".U) {
            Data := Cat(
              0.U(16.W),
              StoreData(7, 0),
              0.U(8.W)
            )
            Mask := "b0010".U
          }
          is("b10".U) {
            Data := Cat(
              0.U(8.W),
              StoreData(7, 0),
              0.U(16.W)
            )
            Mask := "b0100".U
          }
          is("b11".U) {
            Data := Cat(StoreData(7, 0), 0.U(24.W))
            Mask := "b1000".U
          }
        }
      }
      is("b01".U) {
        switch(Address(1, 0)) {
          is("b00".U) {
            Data := Cat(
              0.U(16.W),
              StoreData(15, 0)
            )
            Mask := "b0011".U
          }
          is("b10".U) {
            Data := Cat(
              StoreData(15, 0),
              0.U(16.W)
            )
            Mask := "b1100".U
          }
        }
      }
      is("b10".U) {
        Data := StoreData
        Mask := "b1111".U
      }
    }
    (Data, Mask)
  }
  private val IncomingWritePayload = AlignStore(
    io.in.bits.StoreData,
    io.in.bits.ALUResult,
    io.in.bits.WidthSelect
  )
  val IncomingWriteData = IncomingWritePayload._1
  val IncomingWriteMask = IncomingWritePayload._2
  private def FormatLoad(
      Word: UInt,
      Address: UInt,
      Width: UInt,
      Signed: Bool
  ): UInt = {
    val ByteData = MuxLookup(Address(1, 0), Word(7, 0))(
      Seq(1.U -> Word(15, 8), 2.U -> Word(23, 16), 3.U -> Word(31, 24))
    )
    val HalfData = Mux(Address(1), Word(31, 16), Word(15, 0))
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
    MuxLookup(Width, Word)(Seq(0.U -> ByteResult, 1.U -> HalfResult, 2.U -> Word))
  }
  val DirectLoadData = FormatLoad(
    io.DataBus.R.RDATA,
    ActiveInstruction.ALUResult,
    ActiveInstruction.WidthSelect,
    ActiveInstruction.LoadSigned
  )
  val is_store_transaction = RegInit(false.B)
  // 只在总线故障真正提交的周期产生事件。顶层会再寄存一拍供 C++ 在
  // 上升沿后采样；misaligned 不属于 AXI access fault，必须排除。
  io.AccessFault := MemTrapCommit && !MisalignedReg
  io.AccessFaultResp := AccessFaultRespReg
  io.DataBus.AW.AWVALID := false.B
  io.DataBus.AW.AWID := 0.U
  io.DataBus.AW.AWADDR := 0.U
  io.DataBus.AW.AWLEN := 0.U
  io.DataBus.AW.AWSIZE := AXISize // 接soc改的
  io.DataBus.AW.AWBURST := 1.U
  io.DataBus.AW.AWPROT := 0.U
  io.DataBus.W.WVALID := false.B
  io.DataBus.W.WDATA := 0.U
  io.DataBus.W.WSTRB := 0.U
  io.DataBus.W.WLAST := false.B
  io.DataBus.B.BREADY := false.B
  io.DataBus.AR.ARVALID := false.B
  io.DataBus.AR.ARID := 0.U
  io.DataBus.AR.ARADDR := 0.U
  io.DataBus.AR.ARLEN := 0.U
  io.DataBus.AR.ARSIZE := AXISize
  io.DataBus.AR.ARBURST := 1.U
  io.DataBus.AR.ARPROT := 0.U
  io.DataBus.R.RREADY := false.B
  io.Complete := TransactionComplete
  // 所有 store 都先进入写缓冲，但只有属于该指令的 B 响应成功后才能退休。
  // 这样 RAM 的 SLVERR/DECERR 也能精确转换成 store access fault(cause=7)。
  val wbAddr = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbData = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbStrb = Reg(Vec(WBufDepth, UInt(4.W)))
  val wbSize = Reg(Vec(WBufDepth, UInt(3.W)))
  val wbMisaligned = Reg(Vec(WBufDepth, Bool()))
  val wbHead = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 队首(最老)
  val wbTail = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 下一个空位
  val wbCount = RegInit(0.U(log2Ceil(WBufDepth + 1).W))
  val wbufEmpty = wbCount === 0.U
  val wbufFull = wbCount === WBufDepth.U
  val storeBufIdx = Reg(UInt(log2Ceil(WBufDepth).max(1).W)) // 当前写操作的缓冲槽位
  // 每条外部 store 在第一次被 LSU 接收时就占据一个真实写缓冲项。
  // 后续从 PendingStore 提升为活动队首时绝不重复入队。
  val wbPush = io.in.fire && io.in.bits.MemoryValid && io.in.bits.MemoryWrite
  assert(!wbPush || !wbufFull, "LSU accepted a store while its write buffer was full")
  when(wbPush) {
    if (WBufDepth == 1) {
      wbAddr(0) := io.in.bits.ALUResult
      wbData(0) := IncomingWriteData
      wbStrb(0) := IncomingWriteMask
      wbSize(0) := IncomingAXISize
      wbMisaligned(0) := AddressIsMisaligned(
        io.in.bits.ALUResult,
        io.in.bits.WidthSelect
      )
      wbTail := 0.U
    } else {
      wbAddr(wbTail) := io.in.bits.ALUResult
      wbData(wbTail) := IncomingWriteData
      wbStrb(wbTail) := IncomingWriteMask
      wbSize(wbTail) := IncomingAXISize
      wbMisaligned(wbTail) := AddressIsMisaligned(
        io.in.bits.ALUResult,
        io.in.bits.WidthSelect
      )
      wbTail := wbTail + 1.U
    }
  }
  when(startMem && StageInputBits.MemoryWrite) {
    // Store 指令队列和总线队列保持同序；当前活动 store 必然对应总线队首。
    storeBufIdx := wbHead
  }
  // DCache 接收 req 后保证最终返回且只返回一个 resp；flush 只抑制
  // 回填安装，不取消该 demand。因此 DCacheRequestActive 必须一直保持到
  // resp.fire，不能因 fence.i/DMA 一致性 flush 自行清除。
  DCache.io.req.valid := DCacheRequestActive && state === StatesReadRequest // 复用LSU的AR/R通道
  DCache.io.req.bits.addr := ActiveInstruction.ALUResult
  DCache.io.req.bits.WidthSelect := ActiveInstruction.WidthSelect
  DCache.io.req.bits.signed := ActiveInstruction.LoadSigned
  DCache.io.resp.ready := DCacheRequestActive && state === StatesReadResponse
  DCache.io.flush := io.DCacheFlush
  DCache.io.AXI.AW.AWREADY := false.B // DCache不使用AXI写通道
  DCache.io.AXI.W.WREADY := false.B
  DCache.io.AXI.B.BID := 0.U
  DCache.io.AXI.B.BRESP := 0.U
  DCache.io.AXI.B.BVALID := false.B
  // 写事务FSM: 队首向总线发AW/W(可独立握手), 都完成后等B, B到了出队
  val wbStates = Enum(3)
  val wbIdle = wbStates(0)
  val wbIssue = wbStates(1)
  val wbWaitB = wbStates(2)
  val wbState = RegInit(wbIdle)
  val wbAWDone = RegInit(false.B)
  val wbWDone = RegInit(false.B)
  val wbAWfire = io.DataBus.AW.AWVALID && io.DataBus.AW.AWREADY
  val wbWfire = io.DataBus.W.WVALID && io.DataBus.W.WREADY
  val wbBMatch = io.DataBus.B.BID === 0.U
  // A following store may already have issued while the preceding successful
  // store is in StageDone.  Do not consume its B until its matching instruction
  // has become the active architectural queue head.
  val ActiveStoreAwaitsB = stageState === StageWait &&
    MsgReg.MemoryValid && MsgReg.MemoryWrite && !MisalignedReg &&
    state === StatesWriteWaitB && wbHead === storeBufIdx
  val wbBReady = wbState === wbWaitB && ActiveStoreAwaitsB
  val wbBFire = wbBReady && io.DataBus.B.BVALID
  val wbPop = wbBFire && wbBMatch
  // 写直达 DCache 不能在 store 入队时投机更新；否则外部写失败后
  // cache 会保留并不存在于内存的新值。只在成功 B 握手当拍更新。
  val wbCommitSuccess = wbPop && io.DataBus.B.BRESP === 0.U
  val wbHeadAddr = if (WBufDepth == 1) wbAddr(0) else wbAddr(wbHead)
  val wbHeadData = if (WBufDepth == 1) wbData(0) else wbData(wbHead)
  val wbHeadStrb = if (WBufDepth == 1) wbStrb(0) else wbStrb(wbHead)
  val wbHeadSize = if (WBufDepth == 1) wbSize(0) else wbSize(wbHead)
  val wbHeadMisaligned =
    if (WBufDepth == 1) wbMisaligned(0) else wbMisaligned(wbHead)
  DCache.io.StoreValid := wbCommitSuccess
  DCache.io.StoreAddr := wbHeadAddr
  DCache.io.StoreData := wbHeadData
  DCache.io.StoreStrb := wbHeadStrb
  // Idle 可直接驱动已经寄存的队首，避免“发现非空→切 Issue→下一拍才
  // 拉 VALID”的固定空拍。失败/非对齐队首会阻断所有年轻副作用。
  val wbCanStart = !wbufEmpty && !wbHeadMisaligned &&
    !StoreFaultReg && !MemFaultReg
  val wbDrivingIssue = wbState === wbIssue || (wbState === wbIdle && wbCanStart)
  when(wbDrivingIssue) {
    io.DataBus.AW.AWVALID := !wbAWDone
    io.DataBus.AW.AWADDR := wbHeadAddr
    io.DataBus.AW.AWLEN := 0.U
    io.DataBus.AW.AWSIZE := wbHeadSize
    io.DataBus.AW.AWBURST := 1.U
    io.DataBus.W.WVALID := !wbWDone
    io.DataBus.W.WDATA := wbHeadData
    io.DataBus.W.WSTRB := wbHeadStrb
    io.DataBus.W.WLAST := !wbWDone
  }
  switch(wbState) {
    is(wbIdle) {
      when(wbCanStart) {
        when(wbAWfire) { wbAWDone := true.B }
        when(wbWfire) { wbWDone := true.B }
        when(wbAWfire && wbWfire) {
          wbState := wbWaitB
        }.otherwise {
          wbState := wbIssue
        }
      }.otherwise {
        wbAWDone := false.B
        wbWDone := false.B
      }
    }
    is(wbIssue) {
      when(wbAWfire) { wbAWDone := true.B }
      when(wbWfire) { wbWDone := true.B }
      when((wbAWDone || wbAWfire) && (wbWDone || wbWfire)) {
        wbState := wbWaitB
      }
    }
    is(wbWaitB) {
      io.DataBus.B.BREADY := wbBReady
      // B 响应既决定写缓冲出队，也是 store 的精确退休点。
      when(wbBFire) {
        assert(
          io.DataBus.B.BID === 0.U,
          "LSU received a B response with an unexpected BID"
        )
        when(wbBMatch) {
          // Head advances on this edge.  wbIdle can immediately drive the new
          // registered head in the following cycle, so there is no empty FSM
          // cycle between a successful B and the next AW/W.
          wbState := wbIdle
          wbAWDone := false.B
          wbWDone := false.B
        }
      }
    }
  }
  when(wbPop) {
    if (WBufDepth == 1) {
      wbHead := 0.U
    } else {
      wbHead := wbHead + 1.U
    }
  }
  when(MemTrapCommit) {
    wbCount := 0.U
    wbHead := 0.U
    wbTail := 0.U
    wbState := wbIdle
    wbAWDone := false.B
    wbWDone := false.B
  }.otherwise {
    wbCount := wbCount + wbPush.asUInt - wbPop.asUInt
  }
  io.Busy := (stageState =/= StageIdle) || io.in.valid ||
    PendingStoreValid || !wbufEmpty // 当前级、输入或写缓冲任一忙
  io.MemoryBusy :=
    (stageState === StageWait && MsgReg.MemoryValid) ||
      (StageInputValid && StageInputBits.MemoryValid) || !wbufEmpty
  switch(state) {
    is(StatesIdle) {
      when(startMem) {
        // 这些寄存器属于已接受的访存指令。总线状态机返回Idle后仍要保持其稳定，
        // 直到该级能在下游反压下真正提交。
        AccessFaultReg := false.B
        AccessFaultRespReg := 0.U
        StoreFaultReg := false.B
        is_store_transaction := StageInputBits.MemoryWrite
        when(StageInputMisaligned) {
          // 外层流水状态已直接进入 StageDone；不对齐项只作为写缓冲屏障，
          // 不产生任何 AXI 请求，随后精确提交 cause 4/6。
          state := StatesIdle
        }
          .elsewhen(StageInputBits.MemoryWrite) {
            // 所有 store（包括普通 RAM）都要等属于自己的 B 响应。
            state := StatesWriteWaitB
          }
          .otherwise {
            if (DCacheEnable) {
              // 精确 store 队列只在全部老 store 已处理后才让 load 成为
              // StageInput，因此这里不再经过不可达的 LoadWaitBuf 空状态。
              DCacheRequestActive := true.B
              state := StatesReadRequest
            } else { // 关闭数据缓存时保持原来的单拍直读路径
              state := StatesReadRequest
            }
          }
      }
    }
    is(StatesWriteWaitB) {
      // 等到自己的写事务拿到B响应: 之前的项都已按序完成, 队首就是本指令的项
      when(wbPop && wbHead === storeBufIdx) {
        StoreFaultReg := io.DataBus.B.BRESP =/= 0.U
        AccessFaultRespReg := io.DataBus.B.BRESP
        TransactionComplete := true.B
        TransactionFault := io.DataBus.B.BRESP =/= 0.U
        state := StatesIdle
      }
    }
    is(StatesReadRequest) {
      if (DCacheEnable) {
        when(DCacheRequestActive) {
          when(DCache.io.req.fire) { state := StatesReadResponse }
        }.otherwise {
          io.DataBus.AR.ARVALID := true.B
          io.DataBus.AR.ARADDR := ActiveInstruction.ALUResult
          when(io.DataBus.AR.ARREADY) { state := StatesReadResponse }
        }
      } else {
        io.DataBus.AR.ARVALID := true.B
        io.DataBus.AR.ARADDR := ActiveInstruction.ALUResult
        when(io.DataBus.AR.ARREADY) { state := StatesReadResponse }
      }
    }
    is(StatesReadResponse) {
      if (DCacheEnable) {
        when(DCacheRequestActive) {
          when(DCache.io.resp.fire) {
            LoadForwardValid := true.B
            LoadForwardData := DCache.io.resp.bits.data
            LoadForwardFault := DCache.io.resp.bits.fault
            when(DCache.io.resp.bits.fault) {
              AccessFaultReg := true.B
              AccessFaultRespReg := DCache.io.resp.bits.FaultResp
            }.otherwise {
              LoadDataReg := DCache.io.resp.bits.data
            }
            DCacheRequestActive := false.B
            TransactionComplete := true.B
            TransactionFault := DCache.io.resp.bits.fault
            state := StatesIdle
          }
        }.otherwise {
          io.DataBus.R.RREADY := true.B
          when(io.DataBus.R.RVALID) {
            when(io.DataBus.R.RRESP =/= 0.U) {
              AccessFaultReg := true.B
              AccessFaultRespReg := io.DataBus.R.RRESP
            }.otherwise {
              LoadDataReg := DirectLoadData
            }
            when(io.DataBus.R.RLAST) {
              LoadForwardValid := true.B
              LoadForwardData := DirectLoadData
              LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
              TransactionComplete := true.B
              TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
              state := StatesIdle
            }.otherwise {
              // 该请求是单拍AXI读，因此不带RLAST的响应属于协议错误。应继续消费
              // 此通道，直到违规突发传输排空，而不是提前退休。
              AccessFaultReg := true.B
              AccessFaultRespReg := Mux(
                io.DataBus.R.RRESP =/= 0.U,
                io.DataBus.R.RRESP,
                2.U
              )
              state := StatesReadDrain
            }
          }
        }
      } else {
        io.DataBus.R.RREADY := true.B
        when(io.DataBus.R.RVALID) {
          when(io.DataBus.R.RRESP =/= 0.U) {
            AccessFaultReg := true.B
            AccessFaultRespReg := io.DataBus.R.RRESP
          }.otherwise {
            LoadDataReg := DirectLoadData
          }
          when(io.DataBus.R.RLAST) {
            LoadForwardValid := true.B
            LoadForwardData := DirectLoadData
            LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
            TransactionComplete := true.B
            TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
            state := StatesIdle
          }.otherwise {
            AccessFaultReg := true.B
            AccessFaultRespReg := Mux(
              io.DataBus.R.RRESP =/= 0.U,
              io.DataBus.R.RRESP,
              2.U
            )
            state := StatesReadDrain
          }
        }
      }
    }
    is(StatesReadDrain) {
      io.DataBus.R.RREADY := true.B
      when(io.DataBus.R.RVALID && io.DataBus.R.RLAST) {
        TransactionComplete := true.B
        TransactionFault := true.B
        state := StatesIdle
      }
    }
  }
  if (DCacheEnable) {
    val DCacheBusActive = DCacheRequestActive || DCache.io.axi_active
    when(DCacheBusActive) {
      io.DataBus.AR.ARVALID := DCache.io.AXI.AR.ARVALID
      io.DataBus.AR.ARID := DCache.io.AXI.AR.ARID
      io.DataBus.AR.ARADDR := DCache.io.AXI.AR.ARADDR
      io.DataBus.AR.ARLEN := DCache.io.AXI.AR.ARLEN
      io.DataBus.AR.ARSIZE := DCache.io.AXI.AR.ARSIZE
      io.DataBus.AR.ARBURST := DCache.io.AXI.AR.ARBURST
      io.DataBus.AR.ARPROT := DCache.io.AXI.AR.ARPROT
    }
    DCache.io.AXI.AR.ARREADY := Mux(
      DCacheBusActive,
      io.DataBus.AR.ARREADY,
      false.B
    )
    DCache.io.AXI.R.RID := io.DataBus.R.RID
    DCache.io.AXI.R.RDATA := io.DataBus.R.RDATA
    DCache.io.AXI.R.RRESP := io.DataBus.R.RRESP
    DCache.io.AXI.R.RLAST := io.DataBus.R.RLAST
    DCache.io.AXI.R.RVALID := io.DataBus.R.RVALID
    when(DCacheBusActive) {
      io.DataBus.R.RREADY := DCache.io.AXI.R.RREADY
    }
  } else {
    DCache.io.AXI.AR.ARREADY := false.B
    DCache.io.AXI.R.RID := 0.U
    DCache.io.AXI.R.RDATA := 0.U
    DCache.io.AXI.R.RRESP := 0.U
    DCache.io.AXI.R.RLAST := false.B
    DCache.io.AXI.R.RVALID := false.B
  }
  io.Active := state =/= StatesIdle
  io.IsStore := is_store_transaction
  io.StallReadAR := state === StatesReadRequest
  io.StallReadR := state === StatesReadResponse || state === StatesReadDrain
  io.StallWriteReq := wbufFull && io.in.valid &&
    io.in.bits.MemoryValid && io.in.bits.MemoryWrite && !io.in.ready
  // 等 B 时仍可继续吸收连续 store；其它指令为保持精确异常顺序而等待。
  io.StallWriteB := wbState === wbWaitB
}
