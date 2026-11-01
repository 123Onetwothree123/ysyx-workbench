package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config._

// ALU 加法器：ADD、地址计算、jal/jalr/auipc 目标加法共用。
// 所有结构后端共享 A/B/Cin -> sum/carryOut 语义；Cin 同时供减法器使用。
class ysyx_26030103_ALUAdder(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  private val XLEN = config.XLEN
  require(XLEN == 32 || XLEN == 64, s"ALUAdder XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val A = Input(UInt(XLEN.W))
    val B = Input(UInt(XLEN.W))
    val Cin = Input(Bool())
    val sum = Output(UInt(XLEN.W))
    val carryOut = Output(Bool())
    // RV64 ADDW：只保留低 32 位，最终由顶层做符号扩展。
    val sumWord = Output(UInt(32.W))
  })

  // 一位全加器：返回和与进位。
  private def fullAdder(a: Bool, b: Bool, cin: Bool): (Bool, Bool) = {
    val propagate = a ^ b
    (propagate ^ cin, (a & b) | (propagate & cin))
  }

  // 展成 width 位 ripple-carry 加法器。width 必须是正数；用于整行或
  // carry-select 的每个分组。
  private def ripple(width: Int, a: UInt, b: UInt, cin: Bool):
      (UInt, Bool) = {
    require(width >= 1, s"ripple width 非法: $width")
    val sums = Wire(Vec(width, Bool()))
    var carry = cin
    for (i <- 0 until width) {
      val (sumBit, carryBit) = fullAdder(a(i), b(i), carry)
      sums(i) := sumBit
      carry = carryBit
    }
    (Cat(sums.reverse), carry)
  }

  // 4 位块内超前进位，块之间仍 ripple。对 32/64 位，4 位分组能完整覆盖。
  private def carryLookahead4(a: UInt, b: UInt, cin: Bool):
      (UInt, Bool) = {
    val propagate = a ^ b
    val generate = a & b
    val c1 = generate(0) | (propagate(0) & cin)
    val c2 = generate(1) | (propagate(1) & c1)
    val c3 = generate(2) | (propagate(2) & c2)
    val c4 = generate(3) | (propagate(3) & c3)
    val sum = Cat(
      propagate(3) ^ c3,
      propagate(2) ^ c2,
      propagate(1) ^ c1,
      propagate(0) ^ cin
    )
    (sum, c4)
  }

  private def carrySelect(width: Int, a: UInt, b: UInt, cin: Bool):
      (UInt, Bool) = {
    val group = config.ALUProfile.AdderParams.CarrySelectGroup
    require(group >= 2 && width % group == 0,
      s"carry-select 分组宽度 $group 不能整除 $width")
    val groups = width / group
    val sums = Wire(Vec(groups, UInt(group.W)))
    var carry = cin
    for (g <- 0 until groups) {
      val aGroup = a(g * group + group - 1, g * group)
      val bGroup = b(g * group + group - 1, g * group)
      val (sum0, carry0) = ripple(group, aGroup, bGroup, false.B)
      val (sum1, carry1) = ripple(group, aGroup, bGroup, true.B)
      sums(g) := Mux(carry, sum1, sum0)
      carry = Mux(carry, carry1, carry0)
    }
    (Cat(sums.reverse), carry)
  }

  config.ALUProfile.Adder match {
    case ysyx_26030103_ALUAdderImpl.Native =>
      val ext =
        (Cat(0.U(1.W), io.A) + Cat(0.U(1.W), io.B) + io.Cin)(XLEN, 0)
      io.sum := ext(XLEN - 1, 0)
      io.carryOut := ext(XLEN)
    case ysyx_26030103_ALUAdderImpl.Ripple =>
      val (sum, carry) = ripple(XLEN, io.A, io.B, io.Cin)
      io.sum := sum
      io.carryOut := carry
    case ysyx_26030103_ALUAdderImpl.CarrySelect =>
      val (sum, carry) = carrySelect(XLEN, io.A, io.B, io.Cin)
      io.sum := sum
      io.carryOut := carry
    case ysyx_26030103_ALUAdderImpl.CarryLookahead =>
      require(XLEN % 4 == 0, s"carry-lookahead 要求 XLEN 是 4 的倍数: $XLEN")
      val groups = XLEN / 4
      val sums = Wire(Vec(groups, UInt(4.W)))
      var carry = io.Cin
      for (g <- 0 until groups) {
        val (sum, nextCarry) = carryLookahead4(
          io.A(g * 4 + 3, g * 4),
          io.B(g * 4 + 3, g * 4),
          carry
        )
        sums(g) := sum
        carry = nextCarry
      }
      io.sum := Cat(sums.reverse)
      io.carryOut := carry
    case ysyx_26030103_ALUAdderImpl.Elau =>
      val elau = Module(
        new ysyx_26030103_ElauAdder(XLEN, config.ALUProfile.Elau.Speed)
      )
      elau.io.A := io.A
      elau.io.B := io.B
      elau.io.CI := io.Cin
      io.sum := elau.io.S
      io.carryOut := elau.io.CO
  }

  io.sumWord := io.sum(31, 0)
}
