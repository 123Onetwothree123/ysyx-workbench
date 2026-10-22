package ysyx_26030103.mem
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.config._
// LSU是访存流水级：完成总线事务、提交访存故障并冲刷年轻指令
class ysyx_26030103_LSU(
    val XLEN: Int = 32,
    val WBufDepth: Int = 4, // 写缓冲项数(必须是2的幂, 环形队列按位回绕)
    val DCacheEnable: Boolean = false,
    val DCacheStorage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
    val DCacheBlockSizeLog2: Int = 4,
    val DCacheIndexBits: Int = 5,
    val DCacheableBase: Long = 0x80000000L,
    val DCacheableMask: Long = 0x80000000L,
    val DCachePMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
    val DCacheVipt: Boolean = false,
    // VIPT 并行模式：DCache/LSU 在 PA 到达前就用 VA 选 index 并持有请求，
    // MMU 故障通过 ViptKill 取消该请求。
    val ViptParallel: Boolean = false,
    val AddressWidth: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"LSU XLEN 非法: $XLEN")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"LSU AddressWidth 非法: $AddressWidth")
  require(AddressWidth <= XLEN,
    s"LSU AddressWidth($AddressWidth) 不能超过 XLEN($XLEN)")
  // RV64 的 LD/SD 是 8 字节访问。基本 AXI 数据通道仍是 32 位（与
  // ysyxSoC 兼容），由两拍窄传输完成；DCache 内部按两个相邻 32 位
  // word 处理同一次 8 字节 load/store。
  private val WideMem = XLEN == 64
  private def AddrOf(value: UInt): UInt = value(AddressWidth - 1, 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
    // 给EXU: 本级非空(有访存或完成中的指令),EXU的副作用指令要等着
    val Busy = Output(Bool())
    // 只表示仍可能失败或尚未完成的访存/写缓冲。普通 ALU 指令在本级
    // 单拍直通时不会拉高它，供控制转移避免无谓等待。
    val MemoryBusy = Output(Bool())
    // 访存故障提交(CSR后门)
    val MemTrapCommit = Output(Bool())
    val MemTrapCause = Output(UInt(32.W))
    val MemTrapPC = Output(UInt(XLEN.W))
    val MemTrapTval = Output(UInt(XLEN.W))
    // 访存故障提交时冲刷IDU→EXU和EXU→LSU流水寄存器(里面全是年轻指令)
    val FlushIDEX = Output(Bool())
    val FlushEXMEM = Output(Bool())
    // 直接连总线
    val DataBus = new ysyx_26030103_AXI5IO(AddressWidth)
    // 为未来 DMA/其他非 CPU 写入者保留的数据缓存一致性失效口。
    // Fence.I 只同步指令取值，当前写直达 DCache 不因 Fence.I 清空。
    val DCacheFlush = Input(Bool())
    // VIPT 并行翻译结果：PAddr 可能晚于 DCache 请求到达；PAddrVaddr 是
    // 该物理地址对应的虚拟地址标签。非并行模式忽略这些输入。
    val ViptPAddr = Input(UInt(AddressWidth.W))
    val ViptPAddrValid = Input(Bool())
    val ViptPAddrVaddr = Input(UInt(AddressWidth.W))
    // MMU 翻译故障：取消本 LSU 已受理但尚未取到 PA 的 load。
    val ViptKill = Input(Bool())
    // 无 MMU 时由顶层 PMP 检查“下一个可能产生总线副作用的访存地址”：
    // 写缓冲非空时检查最老 store（队首），否则检查即将进入执行槽的访存
    // 指令。PmpFault 必须在 AW/W/AR 握手前组合生效，故障指令不得产生
    // 任何总线副作用，随后按普通访问故障精确提交。
    val PmpAddr = Output(UInt(AddressWidth.W))
    val PmpWrite = Output(Bool())
    val PmpFault = Input(Bool())
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
    val FwdData = Output(UInt(XLEN.W))
    val FwdReady = Output(Bool())
    // 等待槽冒险检测: 本级被年长访存占用时,EX/MEM流水寄存器里等待的指令
    // 对IDU也必须可见,否则依赖它的消费者会拿着旧寄存器值溜过去
    val Hazard2Valid = Output(Bool())
    val Hazard2Rd = Output(UInt(5.W))
    val Hazard2RegWrite = Output(Bool())
    val Hazard2MemOp = Output(Bool())
    val Hazard2FwdData = Output(UInt(XLEN.W))
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
  // 一致性失效接口：生产顶层由仲裁器在 DMA 写 B 握手时产生的单拍脉冲
  // 驱动（见 ysyx_26030103.scala 的 DCacheFlush 接线），叶模块端口保留
  // 供协议回归直接验证。
  dontTouch(io.DCacheFlush)
  val DCache = ysyx_26030103_DCacheFactory.Create(
    Enable = DCacheEnable,
    BlockSizeLog2 = DCacheBlockSizeLog2,
    IndexBits = DCacheIndexBits,
    AddressWidth = AddressWidth,
    DataWidth = XLEN,
    CacheableBase = DCacheableBase,
    CacheableMask = DCacheableMask,
    PMARegions = DCachePMARegions,
    Storage = DCacheStorage,
    Vipt = DCacheVipt
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
  val LoadDataReg = RegInit(0.U(XLEN.W))
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
  // VIPT 并行 load/store 进入 LSU 时 ALUResult 仍是 VA；翻译结果通过独立
  // 端口晚到。退休/mtrace/调试需要和 PIPT 一样看到物理地址，因此这里按
  // VA 标签锁存有效的 PA。非访存指令保持原始 ALUResult。
  // 只接受“当前指令 VAddr 与 PA 标签匹配”的结果，旧 VA 的迟到 PA 会被
  // 丢弃；LSUMMU 在受理新请求时还会先撤销上一笔 PA。
  val ViptActiveMem = ActiveInstruction.MemoryValid
  val EffectiveALUResult = WireDefault(ActiveInstruction.ALUResult)
  if (DCacheVipt && ViptParallel) {
    val PAddrValidReg = RegInit(false.B)
    val PAddrReg = Reg(UInt(AddressWidth.W))
    when(startMem) { PAddrValidReg := false.B }
    when(io.ViptPAddrValid && io.ViptPAddrVaddr === ActiveInstruction.VAddr &&
        ViptActiveMem) {
      PAddrValidReg := true.B
      PAddrReg := io.ViptPAddr
    }
    EffectiveALUResult :=
      Mux(PAddrValidReg && ViptActiveMem, PAddrReg, ActiveInstruction.ALUResult)
  }
  // LD/SD 要求 8 字节对齐；LW/SW 要求 4 字节；LH/SH、LB/SB 依次降低。
  private def AddressIsMisaligned(Address: UInt, Width: UInt): Bool =
    Mux(
      Width === "b11".U,
      Address(2, 0) =/= 0.U,
      Mux(
        Width === "b10".U,
        Address(1, 0) =/= 0.U,
        Mux(Width === "b01".U, Address(0), false.B)
      )
    )
  val StageInputMisaligned = AddressIsMisaligned(
    StageInputBits.ALUResult,
    StageInputBits.WidthSelect
  )
  // PMP 故障只针对访存指令；非访存直通指令不消费 PmpFault。
  val StageInputPmpFault = io.PmpFault && StageInputBits.MemoryValid
  when(startMem) {
    MisalignedReg := StageInputMisaligned
  }
  when(stageState === StageWait && TransactionComplete) {
    MemFaultReg := TransactionFault
  }.elsewhen(startMem) {
    MemFaultReg := StageInputMisaligned || StageInputPmpFault
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
  io.MemTrapTval := EffectiveALUResult
  io.FlushIDEX := MemTrapCommit
  io.FlushEXMEM := MemTrapCommit
  io.out.valid := false.B
  switch(stageState) {
    is(StageIdle) {
      when(StageInputValid && IsMemOp) {
        StageInputReady := true.B
        when(StageInputFire) {
          stageState := Mux(
            StageInputMisaligned || StageInputPmpFault,
            StageDone,
            StageWait
          )
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
            stageState := Mux(
              StageInputMisaligned || StageInputPmpFault,
              StageDone,
              StageWait
            )
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
  io.out.bits.ALUResult := EffectiveALUResult
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
  io.DebugPC := ActiveInstruction.pc(31, 0)
  io.DebugALUResult := EffectiveALUResult(31, 0)
  io.DebugStoreDATA := ActiveInstruction.StoreData(31, 0)
  io.DebugLoadDATA := LoadDataReg(31, 0)
  io.DebugWidthSelect := ActiveInstruction.WidthSelect
  // 总线事务FSM(原LSU逻辑)
  // AXI 数据通道固定 32 位：LD/SD 的 8 字节访问拆成两拍 AWSIZE=2。
  private def AXISizeFor(Width: UInt): UInt = MuxLookup(Width, 2.U(3.W))(
    Seq("b00".U -> 0.U(3.W), "b01".U -> 1.U(3.W), "b10".U -> 2.U(3.W),
      "b11".U -> 2.U(3.W))
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
    val Data = WireDefault(StoreData(31, 0))
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
        Data := StoreData(31, 0)
        Mask := "b1111".U
      }
      is("b11".U) {
        // SD 的第一拍是低 32 位；第二拍由写缓冲的 wbDataHi 提供。
        Data := StoreData(31, 0)
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
  // SD 的第二拍数据。RV32 时该寄存器不会被执行路径使用。
  val IncomingWriteDataHi =
    if (WideMem) io.in.bits.StoreData(63, 32) else 0.U(32.W)
  // 将最多 64 位的原始小端数据格式化为 XLEN 架构结果。
  //   LB/LH/LW 按 Signed 做符号/零扩展；
  //   LBU/LHU/LWU 零扩展；
  //   LD 直接返回 64 位，忽略 Signed。
  private def FormatLoad(
      WordLow: UInt,
      WordHigh: UInt,
      Address: UInt,
      Width: UInt,
      Signed: Bool
  ): UInt = {
    val Word = WordLow(31, 0)
    val RawWide = Cat(WordHigh(31, 0), Word)
    val ByteData = MuxLookup(Address(1, 0), Word(7, 0))(
      Seq(1.U -> Word(15, 8), 2.U -> Word(23, 16), 3.U -> Word(31, 24))
    )
    val HalfData = Mux(Address(1), Word(31, 16), Word(15, 0))
    def Extend(Value: UInt, Bits: Int): UInt =
      if (XLEN == Bits) {
        Value
      } else {
        Mux(Signed, Cat(Fill(XLEN - Bits, Value(Bits - 1)), Value),
          Cat(0.U((XLEN - Bits).W), Value))
      }
    val ByteResult = Extend(ByteData, 8)
    val HalfResult = Extend(HalfData, 16)
    val WordResult = Extend(Word, 32)
    MuxLookup(Width, WordResult)(
      Seq(0.U -> ByteResult, 1.U -> HalfResult, 2.U -> WordResult,
        3.U -> RawWide)
    )
  }
  val LoadWord0Reg = RegInit(0.U(32.W))
  val LoadBeat = RegInit(0.U(1.W))
  val ReadIsWide = ActiveInstruction.WidthSelect === "b11".U
  // 第二拍 RDATA 是高 32 位，LoadWord0Reg 是第一拍的低 32 位。
  // 窄访问始终使用当前 RDATA 作为低字。
  val DirectLoadData =
    if (WideMem)
      Mux(ReadIsWide && LoadBeat === 1.U,
        FormatLoad(LoadWord0Reg, io.DataBus.R.RDATA,
          ActiveInstruction.ALUResult, ActiveInstruction.WidthSelect,
          ActiveInstruction.LoadSigned),
        FormatLoad(io.DataBus.R.RDATA, 0.U(32.W),
          ActiveInstruction.ALUResult, ActiveInstruction.WidthSelect,
          ActiveInstruction.LoadSigned))
    else
      FormatLoad(io.DataBus.R.RDATA, 0.U(32.W),
        ActiveInstruction.ALUResult, ActiveInstruction.WidthSelect,
        ActiveInstruction.LoadSigned)
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
  io.DataBus.AW.AWCACHE := 0.U
  io.DataBus.AW.AWQOS := 0.U
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
  io.DataBus.AR.ARCACHE := 0.U
  io.DataBus.AR.ARQOS := 0.U
  io.DataBus.R.RREADY := false.B
  io.Complete := TransactionComplete
  // 所有 store 都先进入写缓冲，但只有属于该指令的 B 响应成功后才能退休。
  // 这样 RAM 的 SLVERR/DECERR 也能精确转换成 store access fault(cause=7)。
  val wbAddr = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  val wbVAddr = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  // VIPT 并行 store：wbAddr 暂存 VA，翻译后的 PA 单独保存并带有效位。
  // 只有该位为 1（或处于 PIPT 模式）才允许驱动 AW/W 和更新 DCache。
  val wbPAddr = Reg(Vec(WBufDepth, UInt(AddressWidth.W)))
  val wbPAddrValid = RegInit(VecInit(Seq.fill(WBufDepth)(false.B)))
  val wbData = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbDataHi = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbStrb = Reg(Vec(WBufDepth, UInt(4.W)))
  val wbSize = Reg(Vec(WBufDepth, UInt(3.W)))
  // AXISize 对 8 字节访问也编码为 2（每拍 4 字节），因此必须单独保存
  // 架构宽度以区分 SD 与 SW。
  val wbWide = Reg(Vec(WBufDepth, Bool()))
  val wbMisaligned = Reg(Vec(WBufDepth, Bool()))
  // PIPT/无 MMU 时入队地址就是物理地址，保持原有行为完全不变。
  private val WbNeedsPAddr = DCacheVipt && ViptParallel
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
  // bypass store 的 PA 在 LSU 受理拍已经带正确 VA 标签到达，可以同拍登记；
  // 需要页表翻译的 store 则先保持无效，等独立 PA 端口。
  val IncomingViptPAddrMatch =
    io.ViptPAddrValid && io.ViptPAddrVaddr === io.in.bits.VAddr
  when(wbPush) {
    if (WBufDepth == 1) {
      wbAddr(0) := AddrOf(io.in.bits.ALUResult)
      wbVAddr(0) := AddrOf(io.in.bits.VAddr)
      wbPAddr(0) := Mux(
        WbNeedsPAddr.B && IncomingViptPAddrMatch,
        io.ViptPAddr,
        AddrOf(io.in.bits.ALUResult)
      )
      wbPAddrValid(0) := !WbNeedsPAddr.B || IncomingViptPAddrMatch
      wbData(0) := IncomingWriteData
      wbDataHi(0) := IncomingWriteDataHi
      wbStrb(0) := IncomingWriteMask
      wbSize(0) := IncomingAXISize
      wbWide(0) := io.in.bits.WidthSelect === "b11".U
      wbMisaligned(0) := AddressIsMisaligned(
        io.in.bits.ALUResult,
        io.in.bits.WidthSelect
      )
      wbTail := 0.U
    } else {
      wbAddr(wbTail) := AddrOf(io.in.bits.ALUResult)
      wbVAddr(wbTail) := AddrOf(io.in.bits.VAddr)
      wbPAddr(wbTail) := Mux(
        WbNeedsPAddr.B && IncomingViptPAddrMatch,
        io.ViptPAddr,
        AddrOf(io.in.bits.ALUResult)
      )
      wbPAddrValid(wbTail) := !WbNeedsPAddr.B || IncomingViptPAddrMatch
      wbData(wbTail) := IncomingWriteData
      wbDataHi(wbTail) := IncomingWriteDataHi
      wbStrb(wbTail) := IncomingWriteMask
      wbSize(wbTail) := IncomingAXISize
      wbWide(wbTail) := io.in.bits.WidthSelect === "b11".U
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
  // VIPT 并行模式下 addr 来自 DMMU 的物理地址端口（可能尚未有效），
  // vaddr 始终是架构虚拟地址；DCache 用 vaddr 选 index，等 PA 标签匹配。
  val ViptReqAddr =
    if (DCacheVipt && ViptParallel) io.ViptPAddr
    else ActiveInstruction.ALUResult
  val ViptReqPaddrValid =
    if (DCacheVipt && ViptParallel) io.ViptPAddrValid else true.B
  val ViptReqPaddrVaddr =
    if (DCacheVipt && ViptParallel) io.ViptPAddrVaddr
    else ActiveInstruction.ALUResult
  val ViptReqKill =
    if (DCacheVipt && ViptParallel) io.ViptKill else false.B
  DCache.io.req.valid := DCacheRequestActive && state === StatesReadRequest // 复用LSU的AR/R通道
  DCache.io.req.bits.addr := ViptReqAddr
  DCache.io.req.bits.vaddr := ActiveInstruction.VAddr
  DCache.io.req.bits.WidthSelect := ActiveInstruction.WidthSelect
  DCache.io.req.bits.signed := ActiveInstruction.LoadSigned
  DCache.io.req_paddr_valid := ViptReqPaddrValid
  DCache.io.req_paddr_vaddr := ViptReqPaddrVaddr
  DCache.io.req_kill := ViptReqKill
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
  // 写直达 DCache 不能在 store 入队时投机更新。匹配B握手时再通知：
  // OKAY按字节更新命中行；错误响应只失效该行，以覆盖从机可能的部分副作用。
  val wbHeadAddr = if (WBufDepth == 1) wbAddr(0) else wbAddr(wbHead)
  val wbHeadVAddr = if (WBufDepth == 1) wbVAddr(0) else wbVAddr(wbHead)
  val wbHeadPAddr = if (WBufDepth == 1) wbPAddr(0) else wbPAddr(wbHead)
  val wbHeadPAddrValid =
    if (WBufDepth == 1) wbPAddrValid(0) else wbPAddrValid(wbHead)
  val wbHeadData = if (WBufDepth == 1) wbData(0) else wbData(wbHead)
  val wbHeadDataHi = if (WBufDepth == 1) wbDataHi(0) else wbDataHi(wbHead)
  val wbHeadStrb = if (WBufDepth == 1) wbStrb(0) else wbStrb(wbHead)
  val wbHeadSize = if (WBufDepth == 1) wbSize(0) else wbSize(wbHead)
  val wbHeadWide = if (WBufDepth == 1) wbWide(0) else wbWide(wbHead)
  val wbHeadMisaligned =
    if (WBufDepth == 1) wbMisaligned(0) else wbMisaligned(wbHead)
  // 只有写缓冲队首会对总线/DCache 产生副作用。VIPT 下必须等这笔 store
  // 自己的 PA 带匹配 VA 标签返回；旧标签或尚未翻译的 PA 都被拒绝。
  val wbHeadReady =
    !WbNeedsPAddr.B || wbHeadPAddrValid
  val WbHeadEffectiveAddr =
    if (WbNeedsPAddr) wbHeadPAddr else wbHeadAddr
  // 同拍 pop+push 且缓冲满时，tail 会复用即将出队的 head 槽位。此时新
  // store 的 PA 有效位必须由 wbPush 写入，不能被上一笔 store 的迟到 PA
  // 覆盖；否则新 store 可能带着旧 PA 发出 AW/W。
  val WbPushOverwritesHead =
    wbPush && (if (WBufDepth == 1) true.B else wbTail === wbHead)
  val WbPAddrMatchesHead = io.ViptPAddrValid &&
    io.ViptPAddrVaddr === wbHeadVAddr && !wbHeadPAddrValid &&
    !WbPushOverwritesHead
  when(!wbufEmpty && WbNeedsPAddr.B && WbPAddrMatchesHead) {
    if (WBufDepth == 1) {
      wbPAddr(0) := io.ViptPAddr
      wbPAddrValid(0) := true.B
    } else {
      wbPAddr(wbHead) := io.ViptPAddr
      wbPAddrValid(wbHead) := true.B
    }
  }
  // 出队槽位不再持有待用 PA。若同拍有新 store 复用它，上面的 wbPush
  // 已经写入该项自己的有效位，不能被清零覆盖。
  when(wbPop && !wbPush) {
    if (WBufDepth == 1) {
      wbPAddrValid(0) := false.B
    } else {
      wbPAddrValid(wbHead) := false.B
    }
  }
  DCache.io.StoreValid := wbPop
  DCache.io.StoreResp := io.DataBus.B.BRESP
  DCache.io.StoreAddr := WbHeadEffectiveAddr
  DCache.io.StoreVAddr := wbHeadVAddr
  DCache.io.StoreData :=
    (if (XLEN == 64) Cat(wbHeadDataHi, wbHeadData) else wbHeadData)
  DCache.io.StoreStrb :=
    (if (XLEN == 64)
       Mux(wbHeadWide, "hff".U(8.W), Cat(0.U(4.W), wbHeadStrb))
     else wbHeadStrb)
  // Idle 可直接驱动已经寄存的队首，避免“发现非空→切 Issue→下一拍才
  // 拉 VALID”的固定空拍。失败/非对齐/等待 PA 的队首会阻断所有年轻副作用。
  val wbCanStart = !wbufEmpty && !wbHeadMisaligned && wbHeadReady &&
    !io.PmpFault && !StoreFaultReg && !MemFaultReg
  val wbDrivingIssue = wbState === wbIssue || (wbState === wbIdle && wbCanStart)
  // 8 字节 store 在 32 位 AXI 上拆成两拍：AWLEN=1，W 通道低字在前、
  // 高字在后，第二拍拉 WLAST。窄访问仍是一拍。
  val WbHeadWide = wbHeadWide
  val wbBeat = RegInit(0.U(1.W))
  when(wbDrivingIssue) {
    io.DataBus.AW.AWVALID := !wbAWDone
    io.DataBus.AW.AWADDR := WbHeadEffectiveAddr
    io.DataBus.AW.AWLEN := Mux(WbHeadWide, 1.U, 0.U)
    io.DataBus.AW.AWSIZE := wbHeadSize
    io.DataBus.AW.AWBURST := 1.U
    io.DataBus.W.WVALID := !wbWDone
    io.DataBus.W.WDATA :=
      Mux(WbHeadWide && wbBeat === 1.U, wbHeadDataHi, wbHeadData)
    io.DataBus.W.WSTRB :=
      Mux(WbHeadWide && wbBeat === 1.U, "hf".U, wbHeadStrb)
    io.DataBus.W.WLAST := !WbHeadWide || wbBeat === 1.U
  }
  // 本拍 W 握手是否完成整笔 store 的全部数据拍。
  val wbWFinishes = wbWfire && (!WbHeadWide || wbBeat === 1.U)
  switch(wbState) {
    is(wbIdle) {
      when(wbCanStart) {
        when(wbAWfire) { wbAWDone := true.B }
        when(wbWfire) {
          when(WbHeadWide && wbBeat === 0.U) { wbBeat := 1.U }
            .otherwise { wbWDone := true.B }
        }
        when(wbAWfire && wbWFinishes) {
          wbState := wbWaitB
        }.otherwise {
          wbState := wbIssue
        }
      }.otherwise {
        wbAWDone := false.B
        wbWDone := false.B
        wbBeat := 0.U
      }
    }
    is(wbIssue) {
      when(wbAWfire) { wbAWDone := true.B }
      when(wbWfire) {
        when(WbHeadWide && wbBeat === 0.U) { wbBeat := 1.U }
          .otherwise { wbWDone := true.B }
      }
      when((wbAWDone || wbAWfire) && (wbWDone || wbWFinishes)) {
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
    wbBeat := 0.U
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
    wbBeat := 0.U
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
        LoadBeat := 0.U
        is_store_transaction := StageInputBits.MemoryWrite
        when(StageInputMisaligned || StageInputPmpFault) {
          // 非对齐/PMP 故障项只作为写缓冲屏障，不产生任何 AXI 请求，
          // 随后按 cause 4/6 或 5/7 精确提交。
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
          io.DataBus.AR.ARADDR := AddrOf(ActiveInstruction.ALUResult)
          io.DataBus.AR.ARLEN := 0.U
          when(io.DataBus.AR.ARREADY) { state := StatesReadResponse }
        }
      } else {
        io.DataBus.AR.ARVALID := true.B
        io.DataBus.AR.ARADDR := AddrOf(ActiveInstruction.ALUResult)
        // LD 在 32 位 AXI 上使用两拍 INCR 读；其余访问保持单拍。
        io.DataBus.AR.ARLEN := Mux(ReadIsWide, 1.U, 0.U)
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
          }
          if (WideMem) {
            when(ReadIsWide) {
              when(LoadBeat === 0.U) {
                when(io.DataBus.R.RLAST) {
                  // 两拍读在第一个响应就结束：按协议错误提交访问故障。
                  AccessFaultReg := true.B
                  AccessFaultRespReg := Mux(
                    io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                  TransactionComplete := true.B
                  TransactionFault := true.B
                  state := StatesIdle
                }.otherwise {
                  LoadWord0Reg := io.DataBus.R.RDATA
                  LoadBeat := 1.U
                }
              }.otherwise {
                when(io.DataBus.R.RLAST) {
                  val FinalData = DirectLoadData
                  when(!(AccessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                    LoadDataReg := FinalData
                  }
                  LoadForwardValid := true.B
                  LoadForwardData := FinalData
                  LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
                  TransactionComplete := true.B
                  TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
                  state := StatesIdle
                }.otherwise {
                  AccessFaultReg := true.B
                  AccessFaultRespReg := Mux(
                    io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                  state := StatesReadDrain
                }
              }
            }.otherwise {
              when(io.DataBus.R.RLAST) {
                val FinalData = DirectLoadData
                when(!(AccessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                  LoadDataReg := FinalData
                }
                LoadForwardValid := true.B
                LoadForwardData := FinalData
                LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
                TransactionComplete := true.B
                TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
                state := StatesIdle
              }.otherwise {
                AccessFaultReg := true.B
                AccessFaultRespReg := Mux(
                  io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                state := StatesReadDrain
              }
            }
          } else {
            when(io.DataBus.R.RLAST) {
              val FinalData = DirectLoadData
              when(!(AccessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                LoadDataReg := FinalData
              }
              LoadForwardValid := true.B
              LoadForwardData := FinalData
              LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
              TransactionComplete := true.B
              TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
              state := StatesIdle
            }.otherwise {
              AccessFaultReg := true.B
              AccessFaultRespReg := Mux(
                io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
              state := StatesReadDrain
            }
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
      io.DataBus.AR.ARCACHE := DCache.io.AXI.AR.ARCACHE
      io.DataBus.AR.ARQOS := DCache.io.AXI.AR.ARQOS
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
  // 无 MMU 的 PMP 检查对象：写缓冲非空时是队首 store（唯一可能在本拍
  // 产生写副作用的项），否则是本拍即将受理的访存指令。store 在写缓冲
  // 中始终保持架构顺序，因此队首地址也对应最老未完成 store。
  io.PmpAddr := Mux(!wbufEmpty, WbHeadEffectiveAddr, AddrOf(StageInputBits.ALUResult))
  io.PmpWrite := Mux(!wbufEmpty, true.B, StageInputBits.MemoryWrite)
  io.IsStore := is_store_transaction
  io.StallReadAR := state === StatesReadRequest
  io.StallReadR := state === StatesReadResponse || state === StatesReadDrain
  io.StallWriteReq := wbufFull && io.in.valid &&
    io.in.bits.MemoryValid && io.in.bits.MemoryWrite && !io.in.ready
  // 等 B 时仍可继续吸收连续 store；其它指令为保持精确异常顺序而等待。
  io.StallWriteB := wbState === wbWaitB
  // VIPT 并行 load 的 MMU 翻译故障：LSU 已经先于 PA 受理该 load，
  // 这里必须撤销流水级和 DCache 等待请求，不能把故障 load 当作正常
  // load 退休；精确 page fault 由顶层 LSUMMU 的 faultCommit 提交。
  if (DCacheVipt && ViptParallel) {
    when(io.ViptKill) {
      // 翻译故障发生在 store 已进入写缓冲、但 PA 尚未确认的阶段。此时
      // 队首绝不可能已经发过 AW/W（由 wbHeadReady 门控），因此可以连同
      // 已经排队的年轻 store 一起整体撤销，保持精确异常。
      stageState := StageIdle
      DCacheRequestActive := false.B
      state := StatesIdle
      MisalignedReg := false.B
      MemFaultReg := false.B
      AccessFaultReg := false.B
      AccessFaultRespReg := 0.U
      StoreFaultReg := false.B
      is_store_transaction := false.B
      PendingStoreCount := 0.U
      PendingStoreHead := 0.U
      PendingStoreTail := 0.U
      wbCount := 0.U
      wbHead := 0.U
      wbTail := 0.U
      wbState := wbIdle
      wbAWDone := false.B
      wbWDone := false.B
      wbPAddrValid.foreach(_ := false.B)
    }
  }
}
