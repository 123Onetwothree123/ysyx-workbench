package ysyx_26030103.fpu

import chisel3._
import chisel3.util._

/** FPU 后端边界的可配置响应流水级。
  *
  * `Depth=0` 时完全组合直通，保留后端原有的“组合运算下一拍出结果”
  * 路径；`Depth=N>0` 时插入 N 级 valid/ready 寄存器。每级只在整条
  * 下游 ready 链都允许前进时装载/移动，因此支持任意反压；`flush`
  * 优先级最高，会清空全部在途结果。
  *
  * 该模块只处理 `FpuResponse`，不改变请求握手或 FP 语义；后端只需把
  * 自己的结果 valid/bits 接到 `in`。若后端还有 respValid 结果缓存，可以
  * 不把 `in.ready` 纳入 `req.ready`，由缓存吸收短暂的下游反压。
  */
class ysyx_26030103_FpuResponsePipeline(val FLEN: Int, val Depth: Int)
    extends Module {
  require(FLEN == 32 || FLEN == 64, s"FpuResponsePipeline FLEN 非法: $FLEN")
  require(Depth >= 0, s"FpuResponsePipeline Depth 非法: $Depth")

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_FpuResponse(FLEN)))
    val out = Decoupled(new ysyx_26030103_FpuResponse(FLEN))
    val flush = Input(Bool())
  })

  if (Depth == 0) {
    io.out <> io.in
  } else {
    val valids = RegInit(VecInit(Seq.fill(Depth)(false.B)))
    val buffers = Reg(Vec(Depth, new ysyx_26030103_FpuResponse(FLEN)))
    val readies = Wire(Vec(Depth + 1, Bool()))

    readies(Depth) := io.out.ready
    for (i <- (Depth - 1) to 0 by -1) {
      readies(i) := !valids(i) || readies(i + 1)
    }
    io.in.ready := readies(0)
    io.out.valid := valids(Depth - 1)
    io.out.bits := buffers(Depth - 1)

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
    when(io.flush) {
      valids.foreach(_ := false.B)
    }
  }
}
