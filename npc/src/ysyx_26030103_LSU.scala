package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_AXI5._
class ysyx_26030103_LSU extends Module {
  val io = IO(new Bundle {
//ysyx_26030103_EXU的
    val MemoryValid = Input(Bool())
    val MemoryWrite = Input(Bool())
    val WidthSelect = Input(UInt(2.W))
    val ALUResult = Input(UInt(32.W))
    val StoreDATA = Input(UInt(32.W))
    val LoadSigned = Input(Bool())
    // 直接连总线
    val DataBus = new ysyx_26030103_AXI5IO(32)
    // return给ysyx_26030103_EXU的
    val LoadDATA = Output(UInt(32.W))
    // Misaligned是未对齐的意思，我不知道必应翻译中译英这个单词对不对
    val AddressMisaligned = Output(Bool())
    val Complete = Output(Bool()) // 看看有没有完成
    val AccessFault = Output(Bool())
    val AccessFaultResp = Output(UInt(2.W))
    val Active = Output(Bool())
    val IsStore = Output(Bool())
    val StallReadAR    = Output(Bool())
    val StallReadR     = Output(Bool())
    val StallWriteReq  = Output(Bool())
    val StallWriteB    = Output(Bool())
  })
  // 接入ysyxSoC新加的
  val AXISize = WireDefault(2.U(3.W))
  switch(io.WidthSelect) {
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
  // 真的记不住，Request是名词请求，Response是名词响应
  //// 先标记一下，妈的前面ysyx_26030103_IFU就忘记了，这个不是给AXI用的，是给ysyx_26030103_EXU的，后面拿去等ysyx_26030103_EXU去选择内存
  val StatesIdle = StateMachine(0)
  val StatesReadRequest = StateMachine(1)
  val StatesReadResponse = StateMachine(2)
  val StatesWriteRequest = StateMachine(3)
  val StatesWriteResponse = StateMachine(4)
  val StatesDone = StateMachine(5)
  val state = RegInit(StatesIdle)
  val WriteData = RegInit(0.U(32.W))
  val WriteMask = RegInit(0.U(4.W))
  when(io.MemoryValid) {
    switch(io.WidthSelect) {
      is("b00".U) {
        // 做个笔记，AMBA AXI的文档规定的，A3.2.1.1 Write strobes
        // There is one write strobe for each 8 bits of the write data channel, therefore WSTRB[n] corresponds to WDATA[(8n)+7:(8n)].
        // deepseek翻译：写数据通道中每8位对应一个写选通信号，因此 WSTRB[n] 对应于 WDATA[(8n)+7:(8n)]。
        // 就是我个人的理解来看，如果我要是64bit，那么我就要捕获ALUResult的低位3bit了，因为64位总线一次传8个字节，DATA_WIDTH/8=8，WSTRB就是8位宽
        // 所以就是64:3有用，2:0当掩码用了
        // 实在是一开始没看懂这文档是什么意思，后面问了ai才懂
        // 因为一开始地址是按字节寻址，所以31:2是有效的，1:0不能浪费，就可以拿来当真正的掩码
        switch(io.ALUResult(1, 0)) {
          is("b00".U) {
            WriteData := Cat(0.U(24.W), io.StoreDATA(7, 0));
            WriteMask := "b0001".U
          }
          is("b01".U) {
            WriteData := Cat(0.U(16.W), io.StoreDATA(7, 0), 0.U(8.W));
            WriteMask := "b0010".U
          }
          is("b10".U) {
            WriteData := Cat(0.U(8.W), io.StoreDATA(7, 0), 0.U(16.W));
            WriteMask := "b0100".U
          }
          is("b11".U) {
            WriteData := Cat(io.StoreDATA(7, 0), 0.U(24.W));
            WriteMask := "b1000".U
          }
        }
      }
      is("b01".U) {
        switch(io.ALUResult(1, 0)) {
          is("b00".U) {
            WriteData := Cat(0.U(16.W), io.StoreDATA(15, 0));
            WriteMask := "b0011".U
          }
          is("b10".U) {
            WriteData := Cat(io.StoreDATA(15, 0), 0.U(16.W));
            WriteMask := "b1100".U
          }
        }
      }
      is("b10".U) {
        WriteData := io.StoreDATA
        WriteMask := "b1111".U
      }
    }
  }
  val LoadDataReg = RegInit(0.U(32.W))
  val AccessFaultReg = RegInit(false.B)
  val AccessFaultRespReg = RegInit(0.U(2.W))
  val is_store_transaction = RegInit(false.B)
  io.AccessFault := AccessFaultReg
  io.AccessFaultResp := AccessFaultRespReg
  // 先给默认值，真的是烦死了，我也不知道vscode那个doctor是干什么的，是JVM的吗？看到是甲骨文的名字，而且这玩意不能捡起来直接用就很烦，他妈的
  io.DataBus.AW.AWVALID := false.B
  io.DataBus.AW.AWID := 0.U
  io.DataBus.AW.AWADDR := 0.U
  io.DataBus.AW.AWLEN := 0.U
  io.DataBus.AW.AWSIZE := AXISize//接soc改的
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
  when(io.WidthSelect === "b10".U) {
    io.AddressMisaligned := io.ALUResult(1, 0) =/= "b00".U
  }.elsewhen(io.WidthSelect === "b01".U) {
    io.AddressMisaligned := io.ALUResult(0) =/= 0.U
  }.otherwise {
    io.AddressMisaligned := false.B
  }
  // 新加的，RTT功能正常跑起来了后，让AI复查代码，提出的安全性建议，因为并没有规定AW和W一定同一个周期握手，所以改成可以独立握手
  val AWDone = RegInit(false.B)
  val WDone = RegInit(false.B)
  val AWfire = io.DataBus.AW.AWVALID && io.DataBus.AW.AWREADY
  val Wfire = io.DataBus.W.WVALID && io.DataBus.W.WREADY
  val Bfire = io.DataBus.B.BVALID && io.DataBus.B.BREADY
  switch(state) {
    is(StatesIdle) {
      AWDone := false.B
      WDone := false.B
      AccessFaultReg := false.B
      AccessFaultRespReg := 0.U
      when(io.MemoryValid) {
        is_store_transaction := io.MemoryWrite
        when(io.AddressMisaligned) {
          state := StatesDone
          // 本来想要做一个异常的新的信号，然后直接让处理器走ysyx_26030103_CSR，但是能力有限，做不出来
          // 只能靠编译器保证不会翻车了，理论上应该不会
        }
          .elsewhen(io.MemoryWrite) {
            state := StatesWriteRequest
          }
          .otherwise {
            state := StatesReadRequest
          }
      }
    }
    is(StatesReadRequest) {
      io.DataBus.AR.ARVALID := true.B
      io.DataBus.AR.ARADDR := io.ALUResult
      when(io.DataBus.AR.ARREADY) {
        state := StatesReadResponse
      }
    }
    is(StatesReadResponse) {
      io.DataBus.R.RREADY := true.B
      when(io.DataBus.R.RVALID) {
        when(io.DataBus.R.RRESP =/= 0.U) {
          printf("LSU_R_FAULT addr=%x resp=%d\n", io.ALUResult, io.DataBus.R.RRESP)
          AccessFaultReg := true.B
          AccessFaultRespReg := io.DataBus.R.RRESP
        }.otherwise {
          when(io.WidthSelect === "b00".U) {
            val ByteDATA = WireDefault(0.U(8.W)) // 他妈的Byte是关键字，还得避开名字，真的是服了
            switch(io.ALUResult(1, 0)) {
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
            when(io.LoadSigned) {
              LoadDataReg := Cat(Fill(24, ByteDATA(7)), ByteDATA)
            }.otherwise {
              LoadDataReg := Cat(Fill(24, 0.U), ByteDATA)
            }
          }.elsewhen(io.WidthSelect === "b01".U) {
            val HalfWord = Wire(UInt(16.W))
            when(io.ALUResult(1)) { // 高16bit
              HalfWord := io.DataBus.R.RDATA(31, 16)
            }.otherwise {
              HalfWord := io.DataBus.R.RDATA(15, 0)
            }
            when(io.LoadSigned) {
              LoadDataReg := Cat(Fill(16, HalfWord(15)), HalfWord)
            }.otherwise {
              LoadDataReg := Cat(Fill(16, 0.U), HalfWord)
            }
          }.elsewhen(io.WidthSelect === "b10".U) {
            LoadDataReg := io.DataBus.R.RDATA
          }
        }
        state := StatesDone
      }
    }
    is(StatesWriteRequest) {
      val AWAlreadyDone = AWDone
      val WAlreadyDone = WDone
      io.DataBus.AW.AWVALID := !AWAlreadyDone
      io.DataBus.AW.AWADDR := io.ALUResult
      io.DataBus.AW.AWPROT := 0.U
      io.DataBus.W.WVALID := !WAlreadyDone
      io.DataBus.W.WDATA := WriteData
      io.DataBus.W.WSTRB := WriteMask
      io.DataBus.W.WLAST := !WAlreadyDone
      // 握手完毕了就记录写完了
      when(AWfire) {
        AWDone := true.B
      }
      when(Wfire) {
        WDone := true.B
      }
      when((AWAlreadyDone || AWfire) && (WAlreadyDone || Wfire)) {
        state := StatesWriteResponse
      }
    }
    is(StatesWriteResponse) {
      // 因为是等，所以要用B总线，而不是W线
      io.DataBus.B.BREADY := true.B
      when(Bfire) {
        when(io.DataBus.B.BRESP =/= 0.U) {
          printf("LSU_B_FAULT addr=%x resp=%d\n", io.ALUResult, io.DataBus.B.BRESP)
          AccessFaultReg := true.B
          AccessFaultRespReg := io.DataBus.B.BRESP
        }
        state := StatesDone
      }
    }
    // 前天搞完后，回顾的时候又忘记了，这个就是开新的循环了
    is(StatesDone) {
      state := StatesIdle
    }
  }
  io.LoadDATA := LoadDataReg
  io.Active := state =/= StatesIdle
  io.IsStore := is_store_transaction
  io.StallReadAR    := state === StatesReadRequest
  io.StallReadR     := state === StatesReadResponse
  io.StallWriteReq  := state === StatesWriteRequest
  io.StallWriteB    := state === StatesWriteResponse
}
