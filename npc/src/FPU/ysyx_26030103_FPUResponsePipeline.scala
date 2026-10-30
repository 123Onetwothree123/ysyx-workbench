package ysyx_26030103.fpu
import chisel3._
import chisel3.util._
//FPU后端响应流水级，如果Depth=0的时候直通，Depth>0的时候就直接插入多级的寄存器
class ysyx_26030103_FPUResponsePipeline(val FLEN: Int, val Depth: Int)
    extends Module {
  require(FLEN == 32 || FLEN == 64, s"FPUResponsePipeline FLEN 非法: $FLEN")
  require(Depth >= 0, s"FPUResponsePipeline Depth 非法: $Depth")
  // 只处理FPUResponse，别的请求握手和FP的语义拿过去交给后端负责
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_FpuResponse(FLEN)))
    val out = Decoupled(new ysyx_26030103_FpuResponse(FLEN))
    val flush = Input(Bool())
  })
  // Depth=0的时候，可以去保留后端组合运算和下一拍出结果的直通路径
  if (Depth == 0) {
    io.out <> io.in // 这里就直接连线了
  } else {
    val valids = RegInit(VecInit(Seq.fill(Depth)(false.B)))
    val buffers = Reg(Vec(Depth, new ysyx_26030103_FpuResponse(FLEN)))
    val readies = Wire(Vec(Depth + 1, Bool()))
    // 从最下游反推ready，本级为空或者是说下游可接收时才能前进，也就是out.ready是否是1的时候
    readies(Depth) := io.out.ready
    for (i <- (Depth - 1) to 0 by -1) {
      readies(i) := !valids(i) || readies(i + 1)
    }
    // 如果FPU后端自己已经有了结果缓存，就可以不用把in.ready连上req.ready，让后端缓存吸收来实现这个短暂的反压操作
    // 如果后端没有缓存，就把in.ready接到请求的那边，避免覆盖掉这些还没取走的结果
    io.in.ready := readies(0)
    io.out.valid := valids(Depth - 1)
    io.out.bits := buffers(Depth - 1)
    // 如果整条下游链都是允许的话，数据逐级向前移动
    for (i <- (Depth - 1) to 1 by -1) {
      when(readies(i)) {
        valids(i) := valids(i - 1)
        buffers(i) := buffers(i - 1)
      }
    }
    when(readies(0)) {
      valids(0) := io.in.valid
      buffers(0) := io.in.bits
    }
    // flush优先级最高，清空所有在途结果
    when(io.flush) {
      valids.foreach(_ := false.B)
    }
  }
}
