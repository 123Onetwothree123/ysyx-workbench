package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** 基础 RISC-V PMP 检查器。
  *
  * 支持 OFF/TOR/NA4/NAPOT 四种 A 字段；最低编号匹配项优先。
  * 未匹配时 M 模式放行，S/U 模式拒绝；带 L 的匹配项对 M 也强制权限。
  */
class ysyx_26030103_PMP(entries: Int = 16) extends Module {
  require(entries >= 0 && entries <= 16, "PMP entries 必须在 0..16")
  private val activeEntries = 16

  val io = IO(new Bundle {
    val cfg = Input(Vec(activeEntries, UInt(8.W)))
    val addr = Input(Vec(activeEntries, UInt(32.W)))
    val fetchAddr = Input(UInt(32.W))
    val fetchPriv = Input(UInt(2.W))
    val fetchFault = Output(Bool())
    val dataAddr = Input(UInt(32.W))
    val dataPriv = Input(UInt(2.W))
    val dataWrite = Input(Bool())
    val dataFault = Output(Bool())
  })

  private def trailingOnes(value: UInt): UInt = {
    val bits = 30
    val firstZero = PriorityEncoder(~value(bits - 1, 0))
    firstZero
  }

  private def matchEntry(index: Int, address: UInt): Bool = {
    if (index >= entries) false.B
    else {
      val config = io.cfg(index)
      val mode = config(4, 3)
      val current = io.addr(index)
      val lower = if (index == 0) 0.U(32.W) else io.addr(index - 1) << 2
      val upper = current << 2
      val tor = address >= lower && address < upper
      val na4 = address(31, 2) === current(29, 0)
      val ones = trailingOnes(current)
      val napotMask = (1.U << (ones + 3.U)) - 1.U
      val napotBase = (current & ~((1.U << (ones + 1.U)) - 1.U)) << 2
      val napot = (address & ~napotMask) === (napotBase & ~napotMask)
      Mux(mode === 1.U, tor,
        Mux(mode === 2.U, na4,
          Mux(mode === 3.U, napot, false.B)))
    }
  }

  private def check(address: UInt, privilege: UInt, access: UInt): Bool = {
    val matches = (0 until activeEntries).map(i => matchEntry(i, address))
    val anyMatch = matches.reduceOption(_ || _).getOrElse(false.B)
    val permissions = (0 until activeEntries).map { i =>
      val config = io.cfg(i)
      val unlockedM = privilege === 3.U && !config(7)
      val allowed = Mux(access === 0.U, config(2),
        Mux(access === 1.U, config(0), config(1)))
      unlockedM || allowed
    }
    val allowed = Mux1H(matches, permissions)
    val lockedMatch = (0 until activeEntries).map { i =>
      matches(i) && io.cfg(i)(7)
    }.reduceOption(_ || _).getOrElse(false.B)
    val noMatchFault = !anyMatch && privilege =/= 3.U
    val permissionFault = anyMatch && !(allowed || (privilege === 3.U && !lockedMatch))
    noMatchFault || permissionFault
  }

  io.fetchFault := check(io.fetchAddr, io.fetchPriv, 0.U)
  io.dataFault := check(io.dataAddr, io.dataPriv,
    Mux(io.dataWrite, 2.U, 1.U))
}
