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

class ysyx_26030103_RocketMultiplierRequest(val dataBits: Int = 64, val tagBits: Int = 5)
    extends Bundle {
  val fn = UInt(ysyx_26030103_RocketALU.SZ_ALU_FN.W)
  val dw = UInt(ysyx_26030103_RocketALU.SZ_DW.W)
  val in1 = UInt(dataBits.W)
  val in2 = UInt(dataBits.W)
  val tag = UInt(tagBits.W)
}

class ysyx_26030103_RocketMultiplierResponse(val dataBits: Int = 64, val tagBits: Int = 5)
    extends Bundle {
  val data = UInt(dataBits.W)
  val FullData = UInt((2 * dataBits).W)
  val tag = UInt(tagBits.W)
}

class ysyx_26030103_RocketMultiplierIO(val dataBits: Int = 64, val tagBits: Int = 5)
    extends Bundle {
  val request = Flipped(Decoupled(new ysyx_26030103_RocketMultiplierRequest(dataBits, tagBits)))
  val kill = Input(Bool())
  val response = Decoupled(new ysyx_26030103_RocketMultiplierResponse(dataBits, tagBits))
  val DivIterations = Output(UInt(16.W))
  val DivEarlyOut = Output(Bool())
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

  private val sReady :: sNegInputs :: sMul :: sDiv :: _ :: sNegOutput :: sDoneMul :: sDoneDiv :: Nil = Enum(8)
  private val state = RegInit(sReady)
  private val requestRegister = Reg(chiselTypeOf(io.request.bits))
  private val count = Reg(UInt(log2Ceil(
    math.max(w / divUnroll + 1, mulw / mulUnroll)
  ).W))
  private val negOut = Reg(Bool())
  private val isHi = Reg(Bool())
  private val resHi = Reg(Bool())
  private val divisor = Reg(UInt((w + 1).W))
  private val remainder = Reg(UInt((2 * mulw + 2).W))

  private val fn = io.request.bits.fn
  private val cmdMul = (fn === FN_MUL || fn === FN_MULH || fn === FN_MULHU || fn === FN_MULHSU)
  private val cmdHi = (fn === FN_MULH || fn === FN_MULHU || fn === FN_MULHSU || fn === FN_REM || fn === FN_REMU)
  private val lhsSigned = (fn === FN_MULH || fn === FN_MULHSU || fn === FN_DIV || fn === FN_REM)
  private val rhsSigned = (fn === FN_MULH || fn === FN_DIV || fn === FN_REM)

  private def halfWidth(requestRegister: ysyx_26030103_RocketMultiplierRequest): Bool =
    (w > 32).B && requestRegister.dw === DW_32

  private def sext(x: UInt, halfW: Bool, signed: Bool): (UInt, Bool) = {
    val sign = signed && Mux(halfW, x(w / 2 - 1), x(w - 1))
    val hi = Mux(halfW, Fill(w / 2, sign), x(w - 1, w / 2))
    (Cat(hi, x(w / 2 - 1, 0)), sign)
  }

  private val (lhsIn, lhsSign) = sext(io.request.bits.in1, halfWidth(io.request.bits), lhsSigned)
  private val (rhsIn, rhsSign) = sext(io.request.bits.in2, halfWidth(io.request.bits), rhsSigned)
  private val subtractor = remainder(2 * w, w) - divisor
  private val result = Mux(resHi, remainder(2 * w, w + 1), remainder(w - 1, 0))
  private val negatedRemainder = -result

  when(state === sNegInputs) {
    when(remainder(w - 1)) {
      remainder := negatedRemainder
    }
    when(divisor(w - 1)) {
      divisor := subtractor
    }
    state := sDiv
  }
  when(state === sNegOutput) {
    remainder := negatedRemainder
    state := sDoneDiv
    resHi := false.B
  }
  when(state === sMul) {
    val mulReg = Cat(remainder(2 * mulw + 1, w + 1), remainder(w - 1, 0))
    val mplierSign = remainder(w)
    val mplier = mulReg(mulw - 1, 0)
    val accum = mulReg(2 * mulw, mulw).asSInt
    val mpcand = divisor.asSInt
    val prod = Cat(mplierSign, mplier(mulUnroll - 1, 0)).asSInt * mpcand + accum
    val nextMulReg = Cat(prod, mplier(mulw - 1, mulUnroll))
    val nextMplierSign = count === (mulw / mulUnroll - 2).U && negOut
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
      state := sDoneMul
      resHi := isHi
    }
  }
  when(state === sDiv) {
    val unrolls = ((0 until divUnroll) scanLeft remainder) { case (rem, index) =>
      val difference = if (index == 0) subtractor else rem(2 * w, w) - divisor(w - 1, 0)
      val less = difference(w)
      Cat(Mux(less, rem(2 * w - 1, w), difference(w - 1, 0)), rem(w - 1, 0), !less)
    }.tail
    remainder := unrolls.last
    when(count === (w / divUnroll).U) {
      state := Mux(negOut, sNegOutput, sDoneDiv)
      resHi := isHi
      if (w % divUnroll < divUnroll - 1)
        remainder := unrolls(w % divUnroll)
    }
    count := count + 1.U
    val divby0 = count === 0.U && !subtractor(w)
    if (params.DIVEarlyOut) {
      val align = 1 << log2Floor(divUnroll max params.DIVEarlyOutGranularity)
      val alignMask = ~((align - 1).U(log2Ceil(w).W))
      val divisorMsb = Log2(divisor(w - 1, 0), w) & alignMask
      val dividendMsb = Log2(remainder(w - 1, 0), w) | ~alignMask
      val eOutPos = ~(dividendMsb - divisorMsb)
      val eOut = count === 0.U && !divby0 && eOutPos >= align.U
      when(eOut) {
        remainder := remainder(w - 1, 0) << eOutPos
        count := eOutPos >> log2Floor(divUnroll)
      }
    }
    when(divby0 && !isHi) { negOut := false.B }
  }
  when(io.response.fire || io.kill) {
    state := sReady
  }
  when(io.request.fire) {
    state := Mux(cmdMul, sMul, Mux(lhsSign || rhsSign, sNegInputs, sDiv))
    isHi := cmdHi
    resHi := false.B
    count := (if (fastMulW) {
      Mux(cmdMul && halfWidth(io.request.bits), (w / mulUnroll / 2).U, 0.U)
    } else {
      0.U
    })
    negOut := Mux(cmdHi, lhsSign, lhsSign =/= rhsSign)
    divisor := Cat(rhsSign, rhsIn)
    remainder := lhsIn
    requestRegister := io.request.bits
  }
  private val outMul = (state & (sDoneMul ^ sDoneDiv)) === (sDoneMul & ~sDoneDiv)
  private val loOut = Mux(fastMulW.B && halfWidth(requestRegister) && outMul,
    result(w - 1, w / 2), result(w / 2 - 1, 0))
  private val hiOut = Mux(halfWidth(requestRegister), Fill(w / 2, loOut(w / 2 - 1)), result(w - 1, w / 2))
  io.response.bits.tag := requestRegister.tag
  io.response.bits.data := Cat(hiOut, loOut)
  io.response.bits.FullData := Cat(remainder(2 * w, w + 1), remainder(w - 1, 0))
  io.response.valid := state === sDoneMul || state === sDoneDiv
  io.request.ready := state === sReady
  io.DivIterations := (count * divUnroll.U).pad(16)
  io.DivEarlyOut := state === sDoneDiv &&
    count < (w / divUnroll + 1).U
}
