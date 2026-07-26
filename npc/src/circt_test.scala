import chisel3._
import chisel3.util._

class circt_test_Minimal(
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L
) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(32.W))
    val en   = Input(Bool())
    val reg  = Output(Bool())
  })
  val cacheable = (io.addr & CacheableMask.U) === CacheableBase.U
  val reg = RegInit(false.B)
  when(io.en) { reg := cacheable }
  io.reg := reg
}
