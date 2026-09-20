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
    val DCacheableMask: Long = 0x80000000L
) extends Module {
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
    val DCacheFlush = Input(Bool()) // fence.i使写直达数据缓存失效
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
  val DCache = Module(
    new ysyx_26030103_DCache(
      Enable = DCacheEnable,
      BlockSizeLog2 = DCacheBlockSizeLog2,
      IndexBits = DCacheIndexBits,
      AddressWidth = 32,
      CacheableBase = DCacheableBase,
      CacheableMask = DCacheableMask
    )
  )
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
  val StoreFaultReg = RegInit(false.B) // 等待B响应时锁存写访问错误
  val StageIsIdle = stageState === StageIdle
  val IsMemOp = io.in.bits.MemoryValid
  val startMem = StageIsIdle && io.in.fire && IsMemOp
  when(startMem) {
    MsgReg := io.in.bits
  }
  val ActiveInstruction = Mux(StageIsIdle, io.in.bits, MsgReg)
  val AddressMisaligned = Wire(Bool()) // 字需4字节对齐，半字需2字节对齐
  when(ActiveInstruction.WidthSelect === "b10".U) {
    AddressMisaligned := ActiveInstruction.ALUResult(1, 0) =/= "b00".U
  }.elsewhen(ActiveInstruction.WidthSelect === "b01".U) {
    AddressMisaligned := ActiveInstruction.ALUResult(0) =/= 0.U
  }.otherwise {
    AddressMisaligned := false.B
  }
  val MemFaultReg = RegInit(false.B) // 锁存总线完成时的访存错误
  when(stageState === StageWait && io.Complete) {
    MemFaultReg := AccessFaultReg || StoreFaultReg
  }
  when(stageState === StageDone && io.out.ready) {
    MemFaultReg := false.B
  }
  val MemTrapCommit = stageState === StageDone && MemFaultReg && io.out.ready
  io.MemTrapCommit := MemTrapCommit
  io.MemTrapCause := Mux(
    AddressMisaligned,
    Mux(MsgReg.MemoryWrite, 6.U(32.W), 4.U(32.W)), // 地址非对齐：写=6，读=4
    Mux(MsgReg.MemoryWrite, 7.U(32.W), 5.U(32.W)) // 访问故障：写=7，读=5
  )
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
  io.out.bits.RegisterWrite := ActiveInstruction.RegisterWrite && !MemFaultReg // 读错误时禁止写回
  // Busy的赋值在写缓冲声明之后(见下)
  io.HazardValid := io.in.valid || (stageState =/= StageIdle)
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
        LoadDataReg,
        ActiveInstruction.ALUResult
      )
    )
  )
  io.FwdReady := io.HazardValid && io.HazardRegWrite &&
    (!ActiveInstruction.MemoryValid || stageState === StageDone)
  val WaitValid = (stageState =/= StageIdle) && io.in.valid // 本级忙时等待槽中的年轻指令
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
      AXISize := 0.U // 字节
    }
    is("b01".U) {
      AXISize := 1.U
    }
    is("b10".U) {
      AXISize := 2.U // 四字节
    }
  }
  val StateMachine = Enum(7)
  val StatesIdle = StateMachine(0)
  val StatesReadRequest = StateMachine(1)
  val StatesReadResponse = StateMachine(2)
  val StatesWriteWaitBuf = StateMachine(3) // 写操作等待写缓冲空位
  val StatesLoadWaitBuf = StateMachine(4) // 读操作等待写缓冲排空
  val StatesDone = StateMachine(5)
  val StatesWriteWaitB = StateMachine(6) // 非普通内存写等待B响应
  val State = RegInit(StatesIdle)
  val DCacheRequestActive = RegInit(false.B)
  val AlignedWriteData = WireDefault(ActiveInstruction.StoreData) // 按地址低位对齐写数据
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
  io.AccessFault := AccessFaultReg || StoreFaultReg
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
  io.Complete := State === StatesDone
  val wbAddr = Reg(Vec(WBufDepth, UInt(32.W))) // 普通RAM写入队即退休，MMIO写等待B响应
  val wbData = Reg(Vec(WBufDepth, UInt(32.W)))
  val wbStrb = Reg(Vec(WBufDepth, UInt(4.W)))
  val wbSize = Reg(Vec(WBufDepth, UInt(3.W)))
  val wbValid = RegInit(VecInit(Seq.fill(WBufDepth)(false.B)))
  val wbNorm = Reg(Vec(WBufDepth, Bool())) // 该项是否为纯RAM(无MMIO副作用)
  val wbHead = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 队首(最老)
  val wbTail = RegInit(0.U(log2Ceil(WBufDepth).max(1).W)) // 下一个空位
  val wbCount = RegInit(0.U(log2Ceil(WBufDepth + 1).W))
  val wbufEmpty = wbCount === 0.U
  val wbufFull = wbCount === WBufDepth.U
  def IsPlainRAM(addr: UInt): Bool =
    addr(31, 28) === "h8".U || addr(31, 28) === "ha".U
  val storeNeedsB = !IsPlainRAM(ActiveInstruction.ALUResult)
  val storeBufIdx = Reg(UInt(log2Ceil(WBufDepth).max(1).W)) // 当前写操作的缓冲槽位
  val wbPush =
    (startMem && ActiveInstruction.MemoryWrite && !AddressMisaligned && !wbufFull) ||
      (State === StatesWriteWaitBuf && !wbufFull)
  when(wbPush) {
    wbAddr(wbTail) := ActiveInstruction.ALUResult
    wbData(wbTail) := AlignedWriteData
    wbStrb(wbTail) := AlignedWriteMask
    wbSize(wbTail) := AXISize
    wbValid(wbTail) := true.B
    wbNorm(wbTail) := IsPlainRAM(ActiveInstruction.ALUResult)
    storeBufIdx := wbTail
    wbTail := wbTail + 1.U
  }
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
    IsPlainRAM(ActiveInstruction.ALUResult) && !wbufHit && wbufAllNorm // 无冲突时越过写缓冲
  val DCacheCacheable = if (DCacheEnable) {
    val Configured =
      (ActiveInstruction.ALUResult & DCacheableMask.U(32.W)) ===
        DCacheableBase.U(32.W)
    val NormalRAM = ActiveInstruction.ALUResult(31, 28) === "h8".U ||
      ActiveInstruction.ALUResult(31, 28) === "ha".U
    Configured && NormalRAM
  } else false.B
  val DCacheStore = ActiveInstruction.MemoryValid && ActiveInstruction.MemoryWrite
  // 回填不能越过同一缓存行中尚未完成的写操作
  val DCacheWbufLineHit = VecInit(
    (0 until WBufDepth).map(i =>
      wbValid(i) &&
        wbAddr(i)(31, DCacheBlockSizeLog2) ===
          ActiveInstruction.ALUResult(31, DCacheBlockSizeLog2)
    )
  ).asUInt.orR
  val DCacheLoadMayBypass =
    DCacheCacheable && !DCacheWbufLineHit && wbufAllNorm
  val LoadMayProceed = Mux(
    DCacheCacheable,
    wbufEmpty || DCacheLoadMayBypass,
    wbufEmpty || loadMayBypass
  )
  DCache.io.Req.valid := DCacheRequestActive && State === StatesReadRequest // 复用LSU的AR/R通道
  DCache.io.Req.bits.Addr := ActiveInstruction.ALUResult
  DCache.io.Req.bits.WidthSelect := ActiveInstruction.WidthSelect
  DCache.io.Req.bits.Signed := ActiveInstruction.LoadSigned
  DCache.io.Resp.ready := DCacheRequestActive && State === StatesReadResponse
  DCache.io.StoreValid := wbPush && DCacheStore && DCacheCacheable
  DCache.io.StoreAddr := ActiveInstruction.ALUResult
  DCache.io.StoreData := AlignedWriteData
  DCache.io.StoreStrb := AlignedWriteMask
  DCache.io.Flush := io.DCacheFlush
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
      // 普通内存写已退休，因此后台B响应只用于完成出队
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
  io.Busy := (stageState =/= StageIdle) || io.in.valid || !wbufEmpty // 当前级、输入或写缓冲任一忙
  switch(State) {
    is(StatesIdle) {
      AccessFaultReg := false.B
      AccessFaultRespReg := 0.U
      StoreFaultReg := false.B
      when(startMem) {
        is_store_transaction := ActiveInstruction.MemoryWrite
        when(AddressMisaligned) {
          // 不对齐访存不发起总线事务: 置故障标志, 经MemTrap精确提交地址非对齐异常(cause 4/6)
          AccessFaultReg := true.B
          State := StatesDone
        }
          .elsewhen(ActiveInstruction.MemoryWrite) {
            // 普通内存写入队即完成，其余写操作等待B响应
            when(!wbufFull) {
              State := Mux(storeNeedsB, StatesWriteWaitB, StatesDone)
            }.otherwise {
              State := StatesWriteWaitBuf
            }
          }
          .otherwise {
            if (DCacheEnable) {
              when(LoadMayProceed) {
                DCacheRequestActive := true.B
                State := StatesReadRequest
              }.otherwise {
                State := StatesLoadWaitBuf
              }
            } else { // 关闭数据缓存时保持原来的单拍直读路径
              when(wbufEmpty || loadMayBypass) {
                State := StatesReadRequest
              }.otherwise {
                State := StatesLoadWaitBuf
              }
            }
          }
      }
    }
    is(StatesWriteWaitBuf) {
      when(!wbufFull) {
        State := Mux(storeNeedsB, StatesWriteWaitB, StatesDone)
      }
    }
    is(StatesWriteWaitB) {
      // 等到自己的写事务拿到B响应: 之前的项都已按序完成, 队首就是本指令的项
      when(wbState === wbWaitB && io.DataBus.B.BVALID && wbHead === storeBufIdx) {
        StoreFaultReg := io.DataBus.B.BRESP =/= 0.U
        AccessFaultRespReg := io.DataBus.B.BRESP
        State := StatesDone
      }
    }
    is(StatesLoadWaitBuf) {
      if (DCacheEnable) {
        when(LoadMayProceed) {
          DCacheRequestActive := true.B
          State := StatesReadRequest
        }
      } else {
        when(wbufEmpty || loadMayBypass) {
          State := StatesReadRequest
        }
      }
    }
    is(StatesReadRequest) {
      if (DCacheEnable) {
        when(DCacheRequestActive) {
          when(DCache.io.Req.fire) { State := StatesReadResponse }
        }.otherwise {
          io.DataBus.AR.ARVALID := true.B
          io.DataBus.AR.ARADDR := ActiveInstruction.ALUResult
          when(io.DataBus.AR.ARREADY) { State := StatesReadResponse }
        }
      } else {
        io.DataBus.AR.ARVALID := true.B
        io.DataBus.AR.ARADDR := ActiveInstruction.ALUResult
        when(io.DataBus.AR.ARREADY) { State := StatesReadResponse }
      }
    }
    is(StatesReadResponse) {
      if (DCacheEnable) {
        when(DCacheRequestActive) {
          when(DCache.io.Resp.fire) {
            when(DCache.io.Resp.bits.Fault) {
              AccessFaultReg := true.B
              AccessFaultRespReg := DCache.io.Resp.bits.FaultResp
            }.otherwise {
              LoadDataReg := DCache.io.Resp.bits.Data
            }
            DCacheRequestActive := false.B
            State := StatesDone
          }
        }.otherwise {
          io.DataBus.R.RREADY := true.B
          when(io.DataBus.R.RVALID) {
            when(io.DataBus.R.RRESP =/= 0.U) {
              AccessFaultReg := true.B
              AccessFaultRespReg := io.DataBus.R.RRESP
            }.otherwise {
              when(ActiveInstruction.WidthSelect === "b00".U) {
                val ByteDATA = WireDefault(0.U(8.W))
                switch(ActiveInstruction.ALUResult(1, 0)) {
                  is("b00".U) { ByteDATA := io.DataBus.R.RDATA(7, 0) }
                  is("b01".U) { ByteDATA := io.DataBus.R.RDATA(15, 8) }
                  is("b10".U) { ByteDATA := io.DataBus.R.RDATA(23, 16) }
                  is("b11".U) { ByteDATA := io.DataBus.R.RDATA(31, 24) }
                }
                when(ActiveInstruction.LoadSigned) {
                  LoadDataReg := Cat(Fill(24, ByteDATA(7)), ByteDATA)
                }.otherwise {
                  LoadDataReg := Cat(Fill(24, 0.U), ByteDATA)
                }
              }.elsewhen(ActiveInstruction.WidthSelect === "b01".U) {
                val HalfWord = Wire(UInt(16.W))
                when(ActiveInstruction.ALUResult(1)) {
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
            State := StatesDone
          }
        }
      } else {
        io.DataBus.R.RREADY := true.B
        when(io.DataBus.R.RVALID) {
          when(io.DataBus.R.RRESP =/= 0.U) {
            AccessFaultReg := true.B
            AccessFaultRespReg := io.DataBus.R.RRESP
          }.otherwise {
            when(ActiveInstruction.WidthSelect === "b00".U) {
              val ByteDATA = WireDefault(0.U(8.W))
              switch(ActiveInstruction.ALUResult(1, 0)) {
                is("b00".U) { ByteDATA := io.DataBus.R.RDATA(7, 0) }
                is("b01".U) { ByteDATA := io.DataBus.R.RDATA(15, 8) }
                is("b10".U) { ByteDATA := io.DataBus.R.RDATA(23, 16) }
                is("b11".U) { ByteDATA := io.DataBus.R.RDATA(31, 24) }
              }
              when(ActiveInstruction.LoadSigned) {
                LoadDataReg := Cat(Fill(24, ByteDATA(7)), ByteDATA)
              }.otherwise {
                LoadDataReg := Cat(Fill(24, 0.U), ByteDATA)
              }
            }.elsewhen(ActiveInstruction.WidthSelect === "b01".U) {
              val HalfWord = Wire(UInt(16.W))
              when(ActiveInstruction.ALUResult(1)) {
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
          State := StatesDone
        }
      }
    }
    // 这个就是开新的循环了
    is(StatesDone) {
      State := StatesIdle
    }
  }
  if (DCacheEnable) {
    when(DCacheRequestActive) {
      io.DataBus.AR.ARVALID := DCache.io.AXI.AR.ARVALID
      io.DataBus.AR.ARID := DCache.io.AXI.AR.ARID
      io.DataBus.AR.ARADDR := DCache.io.AXI.AR.ARADDR
      io.DataBus.AR.ARLEN := DCache.io.AXI.AR.ARLEN
      io.DataBus.AR.ARSIZE := DCache.io.AXI.AR.ARSIZE
      io.DataBus.AR.ARBURST := DCache.io.AXI.AR.ARBURST
      io.DataBus.AR.ARPROT := DCache.io.AXI.AR.ARPROT
    }
    DCache.io.AXI.AR.ARREADY := Mux(
      DCacheRequestActive,
      io.DataBus.AR.ARREADY,
      false.B
    )
    DCache.io.AXI.R.RID := io.DataBus.R.RID
    DCache.io.AXI.R.RDATA := io.DataBus.R.RDATA
    DCache.io.AXI.R.RRESP := io.DataBus.R.RRESP
    DCache.io.AXI.R.RLAST := io.DataBus.R.RLAST
    DCache.io.AXI.R.RVALID := io.DataBus.R.RVALID
    when(DCacheRequestActive) {
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
  io.Active := State =/= StatesIdle
  io.IsStore := is_store_transaction
  io.StallReadAR := State === StatesReadRequest
  io.StallReadR := State === StatesReadResponse
  io.StallWriteReq := State === StatesWriteWaitBuf
  io.StallWriteB := wbState === wbWaitB // 后台等B, 不再阻塞流水线, 仅供观测
}
