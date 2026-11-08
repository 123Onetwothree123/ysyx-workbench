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
    // 一次访问可能跨越 4 字节 PMP granule（例如 8 字节 load/store），
    // 必须同时检查末字节所在区域，不能只看起始地址。
    val dataLastAddr = Input(UInt(AddressWidth.W))
    val dataPriv = Input(UInt(2.W))
    // 读/写权限分别检查：AMO 需要两者同时通过，LR 只读，SC 只写。
    val dataRead = Input(Bool())
    val dataWrite = Input(Bool())
    val dataFault = Output(Bool())
  })

  private def trailingOnes(value: UInt): UInt = {
    // pmpaddr 的低 AddressWidth-2 位有效；统计末尾连续 1 的个数。
    // 有效位全 1 时 PriorityEncoder 的输入为 0，会错误地返回 0，必须
    // 显式返回 bits；而 pmpaddr=0（反转后全 1）应返回 0，不能误判为
    // 全 1，否则 8 字节 NAPOT 会退化成只匹配地址 0。
    val bits = AddressWidth - 2
    val inverted = ~value(bits - 1, 0)
    Mux(!inverted.orR, bits.U, PriorityEncoder(inverted))
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
      // pmpaddr 全 1 表示覆盖整个地址空间。非全 1 时 ones <= bits-1，
      // 因此 ones+3 <= AddressWidth；全 1 时直接选全 1 掩码，避免
      // AddressWidth-(ones+3) 下溢。
      val napotAllOnes = current(AddressWidth - 3, 0).andR
      val napotShift = Mux(napotAllOnes, 0.U,
        AddressWidth.U - (ones +& 3.U))
      val napotMask = Mux(napotAllOnes, ~0.U(AddressWidth.W),
        ((~0.U(AddressWidth.W)) >> napotShift)(AddressWidth - 1, 0))
      val napotLowMask =
        ((1.U(AddressWidth.W) << (ones +& 1.U)) - 1.U)(AddressWidth - 1, 0)
      val napotBase =
        ((current & ~napotLowMask) << 2)(AddressWidth - 1, 0)
      val napot = napotAllOnes ||
        ((address & ~napotMask) === (napotBase & ~napotMask))
      Mux(mode === 1.U, tor,
        Mux(mode === 2.U, na4,
          Mux(mode === 3.U, napot, false.B)))
    }
  }

  private def checkRange(
      start: UInt,
      end: UInt,
      privilege: UInt,
      access: UInt
  ): Bool = {
    val startMatches =
      (0 until activeEntries).map(i => matchEntry(i, start))
    val endMatches =
      (0 until activeEntries).map(i => matchEntry(i, end))
    val matchesAny = (0 until activeEntries).map { i =>
      startMatches(i) || endMatches(i)
    }
    val anyMatch = matchesAny.reduceOption(_ || _).getOrElse(false.B)
    // 规范：最低编号的“匹配任意字节”的表项决定结果；该表项必须覆盖
    // 整个访问范围，否则访问失败。不能对起点和终点分别做优先仲裁再 OR。
    val firstMatch = (0 until activeEntries).map { i =>
      matchesAny(i) &&
        !((0 until i).map(j => matchesAny(j))
          .reduceOption(_ || _).getOrElse(false.B))
    }
    val allCovered = (0 until activeEntries).map { i =>
      firstMatch(i) && startMatches(i) && endMatches(i)
    }.reduceOption(_ || _).getOrElse(false.B)
    val permissions = (0 until activeEntries).map { i =>
      val config = io.cfg(i)
      val unlockedM = privilege === 3.U && !config(7)
      val allowed = Mux(access === 0.U, config(2),
        Mux(access === 1.U, config(0), config(1)))
      unlockedM || allowed
    }
    val allowed = PriorityMux(matchesAny.zip(permissions))
    val lockedMatch = PriorityMux(matchesAny.zip(
      (0 until activeEntries).map(i => io.cfg(i)(7))))
    // M 模式只受“锁定表项”约束：最低编号匹配项未锁定（L=0）时整笔访问
    // 直接放行，不能要求它覆盖全部字节——否则跨该表项末尾的 8 字节访问
    // 会被误报 fault。锁定表项对 M 模式仍强制权限与范围检查。
    val mModeBypass = privilege === 3.U && !lockedMatch
    val noMatchFault = !anyMatch && privilege =/= 3.U
    val permissionFault = anyMatch && !mModeBypass &&
      (!allCovered || !allowed)
    noMatchFault || permissionFault
  }

  io.fetchFault := checkRange(io.fetchAddr, io.fetchAddr, io.fetchPriv, 0.U)
  // 同一次 AMO 的读/写检查必须都通过；普通 load/store 只使能其中一侧。
  // 数据访问可能跨 4B PMP granule，按“最低匹配项覆盖整个范围”规则检查。
  val readFault =
    checkRange(io.dataAddr, io.dataLastAddr, io.dataPriv, 1.U)
  val writeFault =
    checkRange(io.dataAddr, io.dataLastAddr, io.dataPriv, 2.U)
  io.dataFault := (io.dataRead && readFault) || (io.dataWrite && writeFault)
}
