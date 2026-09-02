package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message._
import _root_.ysyx_26030103.ysyx_26030103_AXI5._

// LSU = MEM流水级: 访存指令在这里完成总线事务
// 非访存指令单拍直通; 访存故障(load错cause=5/store错cause=7)在本级提交,
// 通过CSR后门(TrapValid/Cause/Pc)写mepc/mcause并冲刷全部年轻指令
class ysyx_26030103_LSU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)
    // 给EXU: 本级非空(有访存或完成中的指令),EXU的副作用指令要等着
    val Busy = Output(Bool())
    // 访存故障提交(CSR后门)
    val MemTrapCommit = Output(Bool())
    val MemTrapCause = Output(UInt(32.W))
    val MemTrapPC = Output(UInt(32.W))
    // 访存故障提交时冲刷IDU→EXU和EXU→LSU流水寄存器(里面全是年轻指令)
    val FlushIDEX = Output(Bool())
    val FlushEXMEM = Output(Bool())
    // 直接连总线
    val DataBus = new ysyx_26030103_AXI5IO(32)
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
    val DebugALUResult = Output(UInt(32.W))
    val DebugStoreDATA = Output(UInt(32.W))
    val DebugLoadDATA = Output(UInt(32.W))
    val DebugWidthSelect = Output(UInt(2.W))
  })

  // 阶段级FSM: 接受/等待/退休
  val StageStates = Enum(3)
  val StageIdle = StageStates(0)
  val StageWait = StageStates(1)
  val StageDone = StageStates(2)
  val stageState = RegInit(StageIdle)
  val MsgReg = Reg(chiselTypeOf(io.in.bits))
  val LoadDataReg = RegInit(0.U(32.W))
  val AccessFaultReg = RegInit(false.B)
  val AccessFaultRespReg = RegInit(0.U(2.W))
  val StageIsIdle = stageState === StageIdle
  val IsMemOp = io.in.bits.MemoryValid
  val startMem = StageIsIdle && io.in.fire && IsMemOp
  when(startMem) {
    MsgReg := io.in.bits
  }
  val ActiveInstruction = Mux(StageIsIdle, io.in.bits, MsgReg)

  // 访存错误锁存: 总线进Done那一拍AccessFault还有效,之后会被清掉
  val MemFaultReg = RegInit(false.B)
  when(stageState === StageWait && io.Complete) {
    MemFaultReg := AccessFaultReg
  }
  when(stageState === StageDone && io.out.ready) {
    MemFaultReg := false.B
  }
  // 访存故障提交点(StageDone里out.valid恒为true,用out.ready而不是out.fire避免组合环)
  val MemTrapCommit = stageState === StageDone && MemFaultReg && io.out.ready
  io.MemTrapCommit := MemTrapCommit
  io.MemTrapCause := Mux(MsgReg.MemoryWrite, 7.U(32.W), 5.U(32.W))
  io.MemTrapPC := MsgReg.pc
  io.FlushIDEX := MemTrapCommit
  io.FlushEXMEM := MemTrapCommit

  io.in.ready := false.B
  io.out.valid := false.B
  switch(stageState) {
    is(StageIdle) {
      when(io.in.valid && IsMemOp) {
        io.in.ready := true.B
        when(io.in.fire) {
          stageState := StageWait
        }
      }.otherwise {
        // 非访存指令单拍直通
        io.in.ready := io.out.ready
        io.out.valid := io.in.valid
      }
    }
    is(StageWait) {
      when(io.Complete) {
        stageState := StageDone
      }
    }
    is(StageDone) {
      io.out.valid := true.B
      when(io.out.fire) {
        stageState := StageIdle
      }
    }
  }
  io.out.bits := ActiveInstruction
  io.out.bits.LoadData := LoadDataReg
  // 访存出错的load不得写回GPR
  io.out.bits.RegisterWrite := ActiveInstruction.RegisterWrite && !MemFaultReg

  // Busy的赋值在写缓冲声明之后(见下)

  io.HazardValid := io.in.valid || (stageState =/= StageIdle)
  io.HazardRd := ActiveInstruction.Rd
  io.HazardRegWrite := ActiveInstruction.RegisterWrite && !MemFaultReg
  io.HazardMemOp := ActiveInstruction.MemoryValid
  // 转发给IDU的最终写回值: ALU结果/snpc/CSR读出已在消息里,load数据在完成时给
  io.FwdData := Mux(
    ActiveInstruction.WBSelect === 2.U,
    ActiveInstruction.snpc,
    Mux(
      ActiveInstruction.WBSelect === 3.U,
      ActiveInstruction.CSRReadData,
      Mux(
        ActiveInstruction.WBSelect === 1.U,
        LoadDataReg,
        ActiveInstruction.ALUResult
      )
    )
  )
  io.FwdReady := io.HazardValid && io.HazardRegWrite &&
    (!ActiveInstruction.MemoryValid || stageState === StageDone)

  // 等待槽: 本级忙时等在EX/MEM流水寄存器里的指令(比MsgReg年轻).
  // 非访存指令的ALU结果/snpc/CSR读出已在消息里,可直接转发;
  // 等待中的load事务还没开始,数据永远不就绪,消费者必须阻塞
  val WaitValid = (stageState =/= StageIdle) && io.in.valid
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
  io.DebugALUResult := ActiveInstruction.ALUResult
  io.DebugStoreDATA := ActiveInstruction.StoreData
  io.DebugLoadDATA := LoadDataReg
  io.DebugWidthSelect := ActiveInstruction.WidthSelect

  // 总线事务FSM(原LSU逻辑)
  val AXISize = WireDefault(2.U(3.W))
  switch(ActiveInstruction.WidthSelect) {
    is("b00".U) {
      AXISize := 0.U // byte
    }
    is("b01".U) {
      AXISize := 1.U
    }
    is("b10".U) {
      AXISize := 2.U // 4 bytes
    }
  }
  val StateMachine = Enum(6)
  val StatesIdle = StateMachine(0)
  val StatesReadRequest = StateMachine(1)
  val StatesReadResponse = StateMachine(2)
  val StatesWriteWaitBuf = StateMachine(3) // store等写缓冲空位
  val StatesLoadWaitBuf = StateMachine(4) // load等写缓冲排空(保序)
  val StatesDone = StateMachine(5)
  val state = RegInit(StatesIdle)
  // 写对齐(组合逻辑): 按地址低两位把StoreData摆到正确的字节lane并生成WSTRB
  // 做个笔记，AMBA AXI的文档规定的，A3.2.1.1 Write strobes
  // There is one write strobe for each 8 bits of the write data channel, therefore WSTRB[n] corresponds to WDATA[(8n)+7:(8n)].
  val AlignedWriteData = WireDefault(ActiveInstruction.StoreData)
  val AlignedWriteMask = WireDefault("b1111".U(4.W))
  switch(ActiveInstruction.WidthSelect) {
    is("b00".U) {
      switch(ActiveInstruction.ALUResult(1, 0)) {
        is("b00".U) {
          AlignedWriteData := Cat(0.U(24.W), ActiveInstruction.StoreData(7, 0));
          AlignedWriteMask := "b0001".U
        }
        is("b01".U) {
          AlignedWriteData := Cat(
            0.U(16.W),
            ActiveInstruction.StoreData(7, 0),
            0.U(8.W)
          );
          AlignedWriteMask := "b0010".U
        }
        is("b10".U) {
          AlignedWriteData := Cat(
            0.U(8.W),
            ActiveInstruction.StoreData(7, 0),
            0.U(16.W)
          );
          AlignedWriteMask := "b0100".U
        }
        is("b11".U) {
          AlignedWriteData := Cat(ActiveInstruction.StoreData(7, 0), 0.U(24.W));
          AlignedWriteMask := "b1000".U
        }
      }
    }
    is("b01".U) {
      switch(ActiveInstruction.ALUResult(1, 0)) {
        is("b00".U) {
          AlignedWriteData := Cat(
            0.U(16.W),
            ActiveInstruction.StoreData(15, 0)
          );
          AlignedWriteMask := "b0011".U
        }
        is("b10".U) {
          AlignedWriteData := Cat(
            ActiveInstruction.StoreData(15, 0),
            0.U(16.W)
          );
          AlignedWriteMask := "b1100".U
        }
      }
    }
    is("b10".U) {
      AlignedWriteData := ActiveInstruction.StoreData
      AlignedWriteMask := "b1111".U
    }
  }
  val is_store_transaction = RegInit(false.B)
  io.AccessFault := AccessFaultReg
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
  io.Complete := state === StatesDone
  // 检查下地址有没有对齐
  val AddressMisaligned = Wire(Bool())
  when(ActiveInstruction.WidthSelect === "b10".U) {
    AddressMisaligned := ActiveInstruction.ALUResult(1, 0) =/= "b00".U
  }.elsewhen(ActiveInstruction.WidthSelect === "b01".U) {
    AddressMisaligned := ActiveInstruction.ALUResult(0) =/= 0.U
  }.otherwise {
    AddressMisaligned := false.B
  }
  // ===== 写缓冲 =====
  // store入队即退休(StatesDone), 不再陪等B响应(~21拍); 后台按程序序完成AXI写。
  // 保序: load默认等buffer排空; 满足旁路条件(纯RAM+字地址无匹配+在写项全纯RAM)可直接上总线,
  // 单主端口下先到的事务先在xbar落地, 顺序天然有保证, 因此无需store-to-load转发。
  // fence.i经EXU的IsSideEffect机制等Busy(含buffer非空)排空后才冲icache。
  // 代价: 总线级store故障(BRESP错误)无法精确异常化——入队时指令已退休。
  // SoC内合法地址的写不会故障, 可接受; 不对齐store仍在入队前被精确拦截。
  val WBufDepth = 4
  val wbAddr = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbData = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbStrb = Reg(Vec(WBufDepth, UInt(4.W)))
  val wbSize = Reg(Vec(WBufDepth, UInt(3.W)))
  val wbValid = RegInit(VecInit(Seq.fill(WBufDepth)(false.B)))
  val wbNorm = Reg(Vec(WBufDepth, Bool())) // 该项是否为纯RAM(无MMIO副作用)
  val wbHead = RegInit(0.U(log2Ceil(WBufDepth).W)) // 队首(最老)
  val wbTail = RegInit(0.U(log2Ceil(WBufDepth).W)) // 下一个空位
  val wbCount = RegInit(0.U(log2Ceil(WBufDepth + 1).W))
  val wbufEmpty = wbCount === 0.U
  val wbufFull = wbCount === WBufDepth.U
  // 入队: 接受store当拍有空位, 或等空位的那一拍(此时ActiveInstruction已是MsgReg)
  def IsPlainRAM(addr: UInt): Bool =
    addr(31, 28) === "h8".U || addr(31, 28) === "ha".U
  val wbPush =
    (startMem && ActiveInstruction.MemoryWrite && !AddressMisaligned && !wbufFull) ||
      (state === StatesWriteWaitBuf && !wbufFull)
  when(wbPush) {
    wbAddr(wbTail) := ActiveInstruction.ALUResult
    wbData(wbTail) := AlignedWriteData
    wbStrb(wbTail) := AlignedWriteMask
    wbSize(wbTail) := AXISize
    wbValid(wbTail) := true.B
    wbNorm(wbTail) := IsPlainRAM(ActiveInstruction.ALUResult)
    wbTail := wbTail + 1.U
  }
  // load旁路: buffer非空时, 若load是纯RAM访问、所有在缓冲的写也都是纯RAM、
  // 且字地址无一匹配, load可直接上总线(顺序由单主端口天然保证); 否则等排空。
  // 匹配场景(如栈上先写后读同一字)不做store-to-load转发, 直接等——先求对。
  val loadWordAddr = ActiveInstruction.ALUResult(31, 2)
  val wbufHit = VecInit(
    (0 until WBufDepth).map(i =>
      wbValid(i) && wbAddr(i)(31, 2) === loadWordAddr
    )
  ).asUInt.orR
  val wbufAllNorm = VecInit(
    (0 until WBufDepth).map(i => !wbValid(i) || wbNorm(i))
  ).asUInt.andR
  val loadMayBypass =
    IsPlainRAM(ActiveInstruction.ALUResult) && !wbufHit && wbufAllNorm
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
  val wbPop = wbState === wbWaitB && io.DataBus.B.BVALID
  switch(wbState) {
    is(wbIdle) {
      when(!wbufEmpty) {
        wbAWDone := false.B
        wbWDone := false.B
        wbState := wbIssue
      }
    }
    is(wbIssue) {
      io.DataBus.AW.AWVALID := !wbAWDone
      io.DataBus.AW.AWADDR := wbAddr(wbHead)
      io.DataBus.AW.AWLEN := 0.U
      io.DataBus.AW.AWSIZE := wbSize(wbHead)
      io.DataBus.AW.AWBURST := 1.U
      io.DataBus.W.WVALID := !wbWDone
      io.DataBus.W.WDATA := wbData(wbHead)
      io.DataBus.W.WSTRB := wbStrb(wbHead)
      io.DataBus.W.WLAST := !wbWDone
      when(wbAWfire) { wbAWDone := true.B }
      when(wbWfire) { wbWDone := true.B }
      when((wbAWDone || wbAWfire) && (wbWDone || wbWfire)) {
        wbState := wbWaitB
      }
    }
    is(wbWaitB) {
      io.DataBus.B.BREADY := true.B
      // BRESP不再检查: store已退休, 总线级故障只能非精确化(见上注释)
      when(io.DataBus.B.BVALID) {
        wbState := wbIdle
      }
    }
  }
  when(wbPop) {
    wbValid(wbHead) := false.B
    wbHead := wbHead + 1.U
  }
  wbCount := wbCount + wbPush.asUInt - wbPop.asUInt
  // Busy: 有指令停在Wait/Done,或直通的那一拍(EXU的副作用指令要等着);
  // 写缓冲非空也算忙(fence.i必须等store真正落内存后才冲icache)
  io.Busy := (stageState =/= StageIdle) || io.in.valid || !wbufEmpty
  switch(state) {
    is(StatesIdle) {
      AccessFaultReg := false.B
      AccessFaultRespReg := 0.U
      when(startMem) {
        is_store_transaction := ActiveInstruction.MemoryWrite
        when(AddressMisaligned) {
          state := StatesDone
          // 不对齐访存直接完成(不发起总线事务),结果未定义,靠编译器保证不会翻车
        }
          .elsewhen(ActiveInstruction.MemoryWrite) {
            // store入队即完成; 满了就等空位
            when(!wbufFull) {
              state := StatesDone
            }.otherwise {
              state := StatesWriteWaitBuf
            }
          }
          .otherwise {
            // load默认要等写缓冲排空; 满足旁路条件(纯RAM+无地址匹配)可直接上总线
            when(wbufEmpty || loadMayBypass) {
              state := StatesReadRequest
            }.otherwise {
              state := StatesLoadWaitBuf
            }
          }
      }
    }
    is(StatesWriteWaitBuf) {
      when(!wbufFull) {
        state := StatesDone
      }
    }
    is(StatesLoadWaitBuf) {
      when(wbufEmpty || loadMayBypass) {
        state := StatesReadRequest
      }
    }
    is(StatesReadRequest) {
      io.DataBus.AR.ARVALID := true.B
      io.DataBus.AR.ARADDR := ActiveInstruction.ALUResult
      when(io.DataBus.AR.ARREADY) {
        state := StatesReadResponse
      }
    }
    is(StatesReadResponse) {
      io.DataBus.R.RREADY := true.B
      when(io.DataBus.R.RVALID) {
        when(io.DataBus.R.RRESP =/= 0.U) {
          AccessFaultReg := true.B
          AccessFaultRespReg := io.DataBus.R.RRESP
        }.otherwise {
          when(ActiveInstruction.WidthSelect === "b00".U) {
            val ByteDATA = WireDefault(0.U(8.W))
            switch(ActiveInstruction.ALUResult(1, 0)) {
              is("b00".U) {
                ByteDATA := io.DataBus.R.RDATA(7, 0)
              }
              is("b01".U) {
                ByteDATA := io.DataBus.R.RDATA(15, 8)
              }
              is("b10".U) {
                ByteDATA := io.DataBus.R.RDATA(23, 16)
              }
              is("b11".U) {
                ByteDATA := io.DataBus.R.RDATA(31, 24)
              }
            }
            when(ActiveInstruction.LoadSigned) {
              LoadDataReg := Cat(Fill(24, ByteDATA(7)), ByteDATA)
            }.otherwise {
              LoadDataReg := Cat(Fill(24, 0.U), ByteDATA)
            }
          }.elsewhen(ActiveInstruction.WidthSelect === "b01".U) {
            val HalfWord = Wire(UInt(16.W))
            when(ActiveInstruction.ALUResult(1)) { // 高16bit
              HalfWord := io.DataBus.R.RDATA(31, 16)
            }.otherwise {
              HalfWord := io.DataBus.R.RDATA(15, 0)
            }
            when(ActiveInstruction.LoadSigned) {
              LoadDataReg := Cat(Fill(16, HalfWord(15)), HalfWord)
            }.otherwise {
              LoadDataReg := Cat(Fill(16, 0.U), HalfWord)
            }
          }.elsewhen(ActiveInstruction.WidthSelect === "b10".U) {
            LoadDataReg := io.DataBus.R.RDATA
          }
        }
        state := StatesDone
      }
    }
    // 这个就是开新的循环了
    is(StatesDone) {
      state := StatesIdle
    }
  }
  io.Active := state =/= StatesIdle
  io.IsStore := is_store_transaction
  io.StallReadAR := state === StatesReadRequest
  io.StallReadR := state === StatesReadResponse
  io.StallWriteReq := state === StatesWriteWaitBuf
  io.StallWriteB := wbState === wbWaitB // 后台等B, 不再阻塞流水线, 仅供观测
}
