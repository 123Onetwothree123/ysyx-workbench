// Adapted from Rocket Chip's Multiplier.scala.
// See ysyxSoC/rocket-chip/LICENSE.Berkeley and LICENSE.SiFive.

package ysyx_26030103.exu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config._

object ysyx_26030103_RocketALU {
  val SZ_ALU_FN = 5
  val SZ_DW = 1
  val FN_ADD = 0.U(SZ_ALU_FN.W)
  val FN_SL = 1.U(SZ_ALU_FN.W)
  val FN_SEQ = 2.U(SZ_ALU_FN.W)
  val FN_SNE = 3.U(SZ_ALU_FN.W)
  val FN_XOR = 4.U(SZ_ALU_FN.W)
  val FN_SR = 5.U(SZ_ALU_FN.W)
  val FN_OR = 6.U(SZ_ALU_FN.W)
  val FN_AND = 7.U(SZ_ALU_FN.W)
  val FN_DIV = FN_XOR
  val FN_DIVU = FN_SR
  val FN_REM = FN_OR
  val FN_REMU = FN_AND
  val FN_MUL = FN_ADD
  val FN_MULH = FN_SL
  val FN_MULHSU = FN_SEQ
  val FN_MULHU = FN_SNE
  val DW_32 = false.B
  val DW_64 = true.B
}

class ysyx_26030103_RocketMultiplierReq(val dataBits: Int = 64, val tagBits: Int = 5)
    extends Bundle {
  val fn = UInt(ysyx_26030103_RocketALU.SZ_ALU_FN.W)
  val dw = UInt(ysyx_26030103_RocketALU.SZ_DW.W)
  val in1 = UInt(dataBits.W)
  val in2 = UInt(dataBits.W)
  val tag = UInt(tagBits.W)
}

class ysyx_26030103_RocketMultiplierResp(val dataBits: Int = 64, val tagBits: Int = 5)
    extends Bundle {
  val data = UInt(dataBits.W)
  val full_data = UInt((2 * dataBits).W)
  val tag = UInt(tagBits.W)
}

class ysyx_26030103_RocketMultiplierIO(val dataBits: Int = 64, val tagBits: Int = 5)
    extends Bundle {
  val req = Flipped(Decoupled(new ysyx_26030103_RocketMultiplierReq(dataBits, tagBits)))
  val kill = Input(Bool())
  val resp = Decoupled(new ysyx_26030103_RocketMultiplierResp(dataBits, tagBits))
}

class ysyx_26030103_RocketMulDiv(
    width: Int,
    params: ysyx_26030103_RocketMDUParams
) extends Module {
  import ysyx_26030103_RocketALU._

  require(width == 32 || width == 64, s"Rocket MulDiv width 非法: $width")
  require(params.MULUnroll > 0, "Rocket MULUnroll 必须大于 0")
  require(params.DIVUnroll > 0, "Rocket DIVUnroll 必须大于 0")

  val io = IO(new ysyx_26030103_RocketMultiplierIO(width, 5))
  val w = width
  private val mulUnroll = params.MULUnroll
  private val divUnroll = params.DIVUnroll
  private val mulw = ((w + mulUnroll - 1) / mulUnroll) * mulUnroll
  private val fastMulW = w / 2 > mulUnroll && w % (2 * mulUnroll) == 0

  private val s_ready :: s_neg_inputs :: s_mul :: s_div :: s_dummy :: s_neg_output :: s_done_mul :: s_done_div :: Nil = Enum(8)
  private val state = RegInit(s_ready)
  private val req = Reg(chiselTypeOf(io.req.bits))
  private val count = Reg(UInt(log2Ceil(
    math.max(w / divUnroll + 1, mulw / mulUnroll)
  ).W))
  private val neg_out = Reg(Bool())
  private val isHi = Reg(Bool())
  private val resHi = Reg(Bool())
  private val divisor = Reg(UInt((w + 1).W))
  private val remainder = Reg(UInt((2 * mulw + 2).W))

  private val fn = io.req.bits.fn
  private val cmdMul = (fn === FN_MUL || fn === FN_MULH || fn === FN_MULHU || fn === FN_MULHSU)
  private val cmdHi = (fn === FN_MULH || fn === FN_MULHU || fn === FN_MULHSU || fn === FN_REM || fn === FN_REMU)
  private val lhsSigned = (fn === FN_MULH || fn === FN_MULHSU || fn === FN_DIV || fn === FN_REM)
  private val rhsSigned = (fn === FN_MULH || fn === FN_DIV || fn === FN_REM)

  private def halfWidth(req: ysyx_26030103_RocketMultiplierReq): Bool =
    (w > 32).B && req.dw === DW_32

  private def sext(x: UInt, halfW: Bool, signed: Bool): (UInt, Bool) = {
    val sign = signed && Mux(halfW, x(w / 2 - 1), x(w - 1))
    val hi = Mux(halfW, Fill(w / 2, sign), x(w - 1, w / 2))
    (Cat(hi, x(w / 2 - 1, 0)), sign)
  }

  private val (lhs_in, lhs_sign) = sext(io.req.bits.in1, halfWidth(io.req.bits), lhsSigned)
  private val (rhs_in, rhs_sign) = sext(io.req.bits.in2, halfWidth(io.req.bits), rhsSigned)
  private val subtractor = remainder(2 * w, w) - divisor
  private val result = Mux(resHi, remainder(2 * w, w + 1), remainder(w - 1, 0))
  private val negated_remainder = -result

  when(state === s_neg_inputs) {
    when(remainder(w - 1)) {
      remainder := negated_remainder
    }
    when(divisor(w - 1)) {
      divisor := subtractor
    }
    state := s_div
  }
  when(state === s_neg_output) {
    remainder := negated_remainder
    state := s_done_div
    resHi := false.B
  }
  when(state === s_mul) {
    val mulReg = Cat(remainder(2 * mulw + 1, w + 1), remainder(w - 1, 0))
    val mplierSign = remainder(w)
    val mplier = mulReg(mulw - 1, 0)
    val accum = mulReg(2 * mulw, mulw).asSInt
    val mpcand = divisor.asSInt
    val prod = Cat(mplierSign, mplier(mulUnroll - 1, 0)).asSInt * mpcand + accum
    val nextMulReg = Cat(prod, mplier(mulw - 1, mulUnroll))
    val nextMplierSign = count === (mulw / mulUnroll - 2).U && neg_out
    val eOutMask =
      ((BigInt(-1) << mulw).S >> (count * mulUnroll.U)(log2Up(mulw) - 1, 0))(mulw - 1, 0)
    val eOut = params.MULEarlyOut.B && count =/= (mulw / mulUnroll - 1).U &&
      count =/= 0.U && !isHi && (mplier & ~eOutMask) === 0.U
    val eOutRes = mulReg >> (mulw.U - count * mulUnroll.U)(log2Up(mulw) - 1, 0)
    val nextMulReg1 = Cat(
      nextMulReg(2 * mulw, mulw),
      Mux(eOut, eOutRes, nextMulReg)(mulw - 1, 0)
    )
    remainder := Cat(nextMulReg1 >> w, nextMplierSign, nextMulReg1(w - 1, 0))
    count := count + 1.U
    when(eOut || count === (mulw / mulUnroll - 1).U) {
      state := s_done_mul
      resHi := isHi
    }
  }
  when(state === s_div) {
    val unrolls = ((0 until divUnroll) scanLeft remainder) { case (rem, i) =>
      val difference = if (i == 0) subtractor else rem(2 * w, w) - divisor(w - 1, 0)
      val less = difference(w)
      Cat(Mux(less, rem(2 * w - 1, w), difference(w - 1, 0)), rem(w - 1, 0), !less)
    }.tail
    remainder := unrolls.last
    when(count === (w / divUnroll).U) {
      state := Mux(neg_out, s_neg_output, s_done_div)
      resHi := isHi
      if (w % divUnroll < divUnroll - 1)
        remainder := unrolls(w % divUnroll)
    }
    count := count + 1.U
    val divby0 = count === 0.U && !subtractor(w)
    if (params.DIVEarlyOut) {
      val align = 1 << log2Floor(divUnroll max params.DIVEarlyOutGranularity)
      val alignMask = ~((align - 1).U(log2Ceil(w).W))
      val divisorMSB = Log2(divisor(w - 1, 0), w) & alignMask
      val dividendMSB = Log2(remainder(w - 1, 0), w) | ~alignMask
      val eOutPos = ~(dividendMSB - divisorMSB)
      val eOut = count === 0.U && !divby0 && eOutPos >= align.U
      when(eOut) {
        remainder := remainder(w - 1, 0) << eOutPos
        count := eOutPos >> log2Floor(divUnroll)
      }
    }
    when(divby0 && !isHi) { neg_out := false.B }
  }
  when(io.resp.fire || io.kill) {
    state := s_ready
  }
  when(io.req.fire) {
    state := Mux(cmdMul, s_mul, Mux(lhs_sign || rhs_sign, s_neg_inputs, s_div))
    isHi := cmdHi
    resHi := false.B
    count := (if (fastMulW) {
      Mux(cmdMul && halfWidth(io.req.bits), (w / mulUnroll / 2).U, 0.U)
    } else {
      0.U
    })
    neg_out := Mux(cmdHi, lhs_sign, lhs_sign =/= rhs_sign)
    divisor := Cat(rhs_sign, rhs_in)
    remainder := lhs_in
    req := io.req.bits
  }
  private val outMul = (state & (s_done_mul ^ s_done_div)) === (s_done_mul & ~s_done_div)
  private val loOut = Mux(fastMulW.B && halfWidth(req) && outMul,
    result(w - 1, w / 2), result(w / 2 - 1, 0))
  private val hiOut = Mux(halfWidth(req), Fill(w / 2, loOut(w / 2 - 1)), result(w - 1, w / 2))
  io.resp.bits.tag := req.tag
  io.resp.bits.data := Cat(hiOut, loOut)
  io.resp.bits.full_data := Cat(remainder(2 * w, w + 1), remainder(w - 1, 0))
  io.resp.valid := state === s_done_mul || state === s_done_div
  io.req.ready := state === s_ready
}
