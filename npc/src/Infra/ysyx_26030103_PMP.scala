package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** 基础 RISC-V PMP 检查器。
  *
  * 支持 OFF/TOR/NA4/NAPOT 四种 A 字段；最低编号匹配项优先。
  * 未匹配时 M 模式放行，S/U 模式拒绝；带 L 的匹配项对 M 也强制权限。
  */
class ysyx_26030103_PMP(entries: Int = 16, AddressWidth: Int = 32) extends Module {
  require(entries >= 0 && entries <= 16, "PMP entries 必须在 0..16")
  private val activeEntries = 16

  val io = IO(new Bundle {
    val cfg = Input(Vec(activeEntries, UInt(8.W)))
    // pmpaddr 保存 address[AddressWidth-1:2]，与 RISC-V PMP 语义一致。
    val addr = Input(Vec(activeEntries, UInt(AddressWidth.W)))
    val fetchAddr = Input(UInt(AddressWidth.W))
    val fetchPriv = Input(UInt(2.W))
    val fetchFault = Output(Bool())
    val dataAddr = Input(UInt(AddressWidth.W))
    val dataPriv = Input(UInt(2.W))
    // 读/写权限分别检查：AMO 需要两者同时通过，LR 只读，SC 只写。
    val dataRead = Input(Bool())
    val dataWrite = Input(Bool())
    val dataFault = Output(Bool())
  })

  private def trailingOnes(value: UInt): UInt = {
    // pmpaddr 的低 AddressWidth-2 位有效；统计末尾连续 1 的个数。
    // 全 1 时 PriorityEncoder 的输入为 0，会错误地返回 0，必须显式
    // 返回 bits，否则全地址空间 NAPOT 会退化成 8 字节区域。
    val bits = AddressWidth - 2
    val inverted = ~value(bits - 1, 0)
    Mux(inverted.andR, bits.U, PriorityEncoder(inverted))
  }

  private def matchEntry(index: Int, address: UInt): Bool = {
    if (index >= entries) false.B
    else {
      val config = io.cfg(index)
      val mode = config(4, 3)
      val current = io.addr(index)
      val lower =
        if (index == 0) 0.U(AddressWidth.W) else io.addr(index - 1) << 2
      val upper = current << 2
      val tor = address >= lower && address < upper
      val na4 = address(AddressWidth - 1, 2) === current(AddressWidth - 3, 0)
      val ones = trailingOnes(current)
      // pmpaddr 全 1 表示覆盖整个地址空间。非全 1 时 ones <= bits-2，
      // 因此 ones+3 不会超过 AddressWidth，右移求掩码是安全的。
      val napotAllOnes = current(AddressWidth - 3, 0).andR
      val napotMask = Mux(napotAllOnes, ~0.U(AddressWidth.W),
        ((~0.U(AddressWidth.W)) >>
          (AddressWidth.U - (ones + 3.U)))(AddressWidth - 1, 0))
      val napotLowMask =
        ((1.U(AddressWidth.W) << (ones + 1.U)) - 1.U)(AddressWidth - 1, 0)
      val napotBase =
        ((current & ~napotLowMask) << 2)(AddressWidth - 1, 0)
      val napot = napotAllOnes ||
        ((address & ~napotMask) === (napotBase & ~napotMask))
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
    // RISC-V PMP 规定最低编号的匹配项优先，不能对多个匹配项的权限做 OR。
    // lockedMatch 也必须取同一个（第一个）匹配项，否则低编号 unlocked
    // allow 会被高编号 locked deny 的 L 位误伤，或反之。
    val allowed = PriorityMux(matches.zip(permissions))
    val lockedMatch = PriorityMux(matches.zip(
      (0 until activeEntries).map(i => io.cfg(i)(7))))
    val noMatchFault = !anyMatch && privilege =/= 3.U
    val permissionFault = anyMatch && !(allowed || (privilege === 3.U && !lockedMatch))
    noMatchFault || permissionFault
  }

  io.fetchFault := check(io.fetchAddr, io.fetchPriv, 0.U)
  // 同一次 AMO 的读/写检查必须都通过；普通 load/store 只使能其中一侧。
  val readFault = check(io.dataAddr, io.dataPriv, 1.U)
  val writeFault = check(io.dataAddr, io.dataPriv, 2.U)
  io.dataFault := (io.dataRead && readFault) || (io.dataWrite && writeFault)
}
