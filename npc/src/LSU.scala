package RV32I
import chisel3._
import chisel3.util._
class LSU extends Module {
  val io = IO(new Bundle {
    // 本来不想加这个接口的，本来想只用MemoryWrite信号的，结果这个新的设计方案logisim画不出来，然后问了几个ai还是要做双信号
    val MemoryValid = Input(Bool()) // 当前周期是否发起一次数据访存
    val MemoryWrite = Input(Bool())
    val WidthSel = Input(UInt(2.W)) // 00:字节，01:半字，10:字
    val ALUResult = Input(UInt(32.W))
    val MemoryReadDATA = Input(UInt(32.W)) // 从内存读的原始数据
    val StoreDATA = Input(UInt(32.W)) // 这玩意就相当于rs2，store要写的数据
    val LoadSigned =
      Input(Bool()) // 区分LB/LBU和未来我可能会选择支持的LH/LHU，表示load后要不要做无符号扩展，0是零扩展，1是符号扩展
    val MemoryWE = Output(Bool()) // 给memory的写使能
    val MemoryAddr = Output(UInt(32.W))
    val MemoryWriteDATA = Output(UInt(32.W))
    val MemoryWriteMask = Output(UInt(4.W)) // 按字节写使能，为了支持SB和SW，SH以后再说吧，烦了
    val LoadDATA = Output(UInt(32.W)) // LSU处理完最终读数据后，送给WBU写回寄存器的
    val AddrMisaligned = Output(Bool()) // 反正到时候地址未对齐的时候给个异常指示
  })
  io.MemoryAddr := io.ALUResult
  io.MemoryWE := io.MemoryValid && io.MemoryWrite
  io.MemoryWriteDATA := 0.U(32.W)
  io.MemoryWriteMask := 0.U(4.W)
  when(io.MemoryValid && io.MemoryWrite) {
    switch(io.WidthSel) {
      is("b00".U) {
        switch(io.ALUResult(1, 0)) { // 拉出ALU的结果的最低位
          is("b00".U) { // 第一个字节
            io.MemoryWriteDATA := Cat(0.U(24.W), io.StoreDATA(7, 0))
            io.MemoryWriteMask := "b0001".U(4.W)
          }
          is("b01".U) { // 第二个字节
            io.MemoryWriteDATA := Cat(0.U(16.W), io.StoreDATA(7, 0), 0.U(8.W))
            io.MemoryWriteMask := "b0010".U(4.W)
          }
          is("b10".U) { // 第三个字节
            io.MemoryWriteDATA := Cat(0.U(8.W), io.StoreDATA(7, 0), 0.U(16.W))
            io.MemoryWriteMask := "b0100".U(4.W)
          }
          is("b11".U) { // 第四个字节
            io.MemoryWriteDATA := Cat(io.StoreDATA(7, 0), 0.U(24.W))
            io.MemoryWriteMask := "b1000".U(4.W)
          }
        }
      }
      is("b01".U) {
        switch(io.ALUResult(1, 0)) { // 重写，不按Verilog写法来
          is("b00".U) {
            io.MemoryWriteDATA := Cat(0.U(16.W), io.StoreDATA(15, 0))
            io.MemoryWriteMask := "b0011".U(4.W)
          }
          is("b10".U) {
            io.MemoryWriteDATA := Cat(io.StoreDATA(15, 0))
            io.MemoryWriteMask := "b1100".U(4.W)
          }
        }
      }
      is("b10".U) {
        io.MemoryWriteDATA := io.StoreDATA
        io.MemoryWriteMask := "b1111".U(4.W)
      }
    }
  }
  io.LoadDATA := 0.U(32.W)
  when(io.MemoryValid && !io.MemoryWrite) {
    switch(io.WidthSel) {
      is("b00".U) {
        switch(io.ALUResult(1, 0)) {
          is("b00".U) {
            io.LoadDATA := Mux(
              io.LoadSigned,
              Cat(Fill(24, io.MemoryReadDATA(7)), io.MemoryReadDATA(7, 0)),
              Cat(0.U(24.W), io.MemoryReadDATA(7, 0))
            )
          }
          is("b01".U) {
            io.LoadDATA := Mux(
              io.LoadSigned,
              Cat(Fill(24, io.MemoryReadDATA(15)), io.MemoryReadDATA(15, 8)),
              Cat(0.U(24.W), io.MemoryReadDATA(15, 8))
            )
          }
          is("b10".U) {
            io.LoadDATA := Mux(
              io.LoadSigned,
              Cat(Fill(24, io.MemoryReadDATA(23)), io.MemoryReadDATA(23, 16)),
              Cat(0.U(24.W), io.MemoryReadDATA(23, 16))
            )
          }
          is("b11".U) {
            io.LoadDATA := Mux(
              io.LoadSigned,
              Cat(Fill(24, io.MemoryReadDATA(31)), io.MemoryReadDATA(31, 24)),
              Cat(0.U(24.W), io.MemoryReadDATA(31, 24))
            )
          }
        }
      }
      is("b01".U) {
        switch(io.ALUResult(1, 0)) {
          is("b00".U) {
            io.LoadDATA := Mux(
              io.LoadSigned,
              Cat(Fill(16, io.MemoryReadDATA(15)), io.MemoryReadDATA(15, 0)),
              Cat(0.U(16.W), io.MemoryReadDATA(15, 0))
            )
          }
          is("b10".U) {
            io.LoadDATA := Mux(
              io.LoadSigned,
              Cat(Fill(16, io.MemoryReadDATA(31)), io.MemoryReadDATA(31, 16)),
              Cat(0.U(16.W), io.MemoryReadDATA(31, 16))
            )
          }
        }
      }
      is("b10".U) {
        io.LoadDATA := io.MemoryReadDATA
      }
    }
  }
  when(io.WidthSel === "b10".U) {
    io.AddrMisaligned := io.ALUResult(1, 0) =/= "b00".U // word直接就检查4字节对齐
  }.elsewhen(io.WidthSel === "b01".U) {
    io.AddrMisaligned := io.ALUResult(
      0
    ) =/= 0.U // half先做了再说，但是sh和lh不想做，反正就检查下2字节对齐
  }.otherwise {
    io.AddrMisaligned := false.B // byte是始终对齐的
  }
}
