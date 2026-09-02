package ysyx_26030103
import chisel3._
import chisel3.util._

// 返回地址栈(Return Address Stack)
// call(jal ra/jalr ra)提交时压入pc+4, ret提交时弹出; ret取指时预测目标=栈顶
// 循环缓冲: 满则覆盖最旧(只影响超深嵌套), 空栈不预测(nonempty=false)
// 深度=2^RASBits, Kconfig可配
// 本流水线中EXU提交点之前的错路指令到不了EXU, 提交点更新天然非投机, 无需检查点/恢复
class ysyx_26030103_RAS(
    RASBits: Int = 4,
    AddressWidth: Int = 32
) extends Module {
  val Depth = 1 << RASBits
  val io = IO(new Bundle {
    // 查询(组合逻辑, IFU取指级和响应级共用当前栈顶)
    val top = Output(UInt(AddressWidth.W))
    val nonempty = Output(Bool())
    // EXU提交call时压栈(返回地址=pc+4=snpc)
    val push_valid = Input(Bool())
    val push_addr = Input(UInt(AddressWidth.W))
    // EXU提交ret时弹栈
    val pop_valid = Input(Bool())
  })

  val buf = Reg(Vec(Depth, UInt(AddressWidth.W)))
  val top = RegInit(0.U(RASBits.W)) // 下一个写入位置
  val count = RegInit(0.U((RASBits + 1).W))

  io.top := buf((top - 1.U)(RASBits - 1, 0))
  io.nonempty := count =/= 0.U

  // 单发射每拍最多提交一条, push和pop天然互斥
  when(io.push_valid) {
    buf(top) := io.push_addr
    top := top + 1.U
    when(count < Depth.U) {
      count := count + 1.U
    } // 已满则覆盖最旧, count不变
  }.elsewhen(io.pop_valid) {
    when(count =/= 0.U) {
      top := top - 1.U
      count := count - 1.U
    }
  }
}
