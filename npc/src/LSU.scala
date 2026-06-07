package RV32I
import chisel3._
import chisel3.util._
import _root_.RV32I.AXI5Lite._
class LSU extends Module {
  val io = IO(new Bundle {
//EXU的
    val MemoryValid = Input(Bool())
    val MemoryWrite = Input(Bool())
    val WidthSelect = Input(UInt(2.W))
    val ALUResult = Input(UInt(32.W))
    val StoreDATA = Input(UInt(32.W))
    val LoadSigned = Input(Bool())
    // 直接连总线
    val DataBus = new AXI5LiteIO(32)
    // return给EXU的
    val LoadDATA = Output(UInt(32.W))
    // Misaligned是未对齐的意思，我不知道必应翻译中译英这个单词对不对
    val AddressMisaligned = Output(Bool())
    val Complete = Output(Bool()) // 看看有没有完成
  })
  val StateMachine = Enum(6)
  // 真的记不住，Request是名词请求，Response是名词响应
  //// 先标记一下，妈的前面IFU就忘记了，这个不是给AXI用的，是给EXU的，后面拿去等EXU去选择内存
  val StatesIdle = StateMachine(0)
  val StatesReadRequest = StateMachine(1)
  val StatesReadResponse = StateMachine(2)
  val StatesWriteRequest = StateMachine(3)
  val StatesWriteResponse = StateMachine(4)
  val StatesDone = StateMachine(5)
  val state = RegInit(StatesIdle)
  val WriteData = WireDefault(0.U(32.W))
  val WriteMask = WireDefault(0.U(4.W))
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
  // 先给默认值，真的是烦死了，我也不知道vscode那个doctor是干什么的，是JVM的吗？看到是甲骨文的名字，而且这玩意不能捡起来直接用就很烦，他妈的
  io.DataBus.AW.AWVALID := false.B
  io.DataBus.AW.AWADDR := 0.U
  io.DataBus.AW.AWPROT := 0.U
  io.DataBus.W.WVALID := false.B
  io.DataBus.W.WDATA := 0.U
  io.DataBus.W.WSTRB := 0.U
  io.DataBus.B.BREADY := false.B
  io.DataBus.AR.ARVALID := false.B
  io.DataBus.AR.ARADDR := 0.U
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
  switch(state) {
    is(StatesIdle) {
      when(io.MemoryValid) {
        when(io.AddressMisaligned) {
          state := StatesDone
          // 本来想要做一个异常的新的信号，然后直接让处理器走CSR，但是能力有限，做不出来
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
        state := StatesDone
      }
    }
    is(StatesWriteRequest) {
      io.DataBus.AW.AWVALID := true.B
      io.DataBus.AW.AWADDR := io.ALUResult
      io.DataBus.W.WVALID := true.B
      io.DataBus.W.WDATA := WriteData
      io.DataBus.W.WSTRB := WriteMask
      when(io.DataBus.AW.AWREADY && io.DataBus.W.WREADY) {
        state := StatesWriteResponse
      }
    }
    is(StatesWriteResponse) {
      // 因为是等，所以要用B总线，而不是W线
      io.DataBus.B.BREADY := true.B
      when(io.DataBus.B.BVALID) {
        state := StatesDone
      }
    }
    // 前天搞完后，回顾的时候又忘记了，这个就是开新的循环了
    is(StatesDone) {
      state := StatesIdle
    }
  }
  io.LoadDATA := LoadDataReg
}
