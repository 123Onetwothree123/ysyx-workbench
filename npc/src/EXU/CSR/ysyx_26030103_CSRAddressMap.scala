package ysyx_26030103.exu

import chisel3._
import chisel3.util._
import scala.collection.mutable

import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** CSR 地址/权限/实现的唯一表。
  *
  * 以前同一组 CSR 地址在三个地方各写一遍：
  *   - `ysyx_26030103_CSR` 的 `CSRxxx` 常量；
  *   - `ysyx_26030103_CSR` 的 `CSRMap/addCSR`；
  *   - `ysyx_26030103_MMUProfile.ImplementedCSRAddresses`。
  *
  * 现在 RTL 只从这里取地址集合：IDU 用 `CSRAddressDecoder` 的
  * valid/privilege 判断译码合法性，CSR 用同一张表建立读数据/写掩码
  * 多路选择。`MMUProfile.csrSupported` 仍然作为配置输入决定某个地址
  * 是否实现，但不再单独维护一份“RTL 实现集合”。
  *
  * 这里只放 elaboration 期常量，不生成硬件；`CSRAddressDecoder` 才是
  * 两边共享的组合译码实现。
  */
object ysyx_26030103_CSRAddressMap {

  /** 所有 CSR 地址常量。除本对象外不应再出现地址字面量。 */
  object Address {
    // U 级浮点状态
    val Fflags = 0x001
    val Frm    = 0x002
    val Fcsr   = 0x003

    // S 级
    val Sstatus    = 0x100
    val Sie        = 0x104
    val Stvec      = 0x105
    val Scounteren = 0x106
    val Senvcfg    = 0x10a
    val Sscratch   = 0x140
    val Sepc       = 0x141
    val Scause     = 0x142
    val Stval      = 0x143
    val Sip        = 0x144
    val Satp       = 0x180

    // M 级
    val Mstatus       = 0x300
    val Misa          = 0x301
    val Medeleg       = 0x302
    val Mideleg       = 0x303
    val Mie           = 0x304
    val Mtvec         = 0x305
    val Mcounteren    = 0x306
    val Menvcfg       = 0x30a
    val Mstatush      = 0x310
    val Menvcfgh      = 0x31a
    val Mcountinhibit = 0x320
    val Mscratch      = 0x340
    val Mepc          = 0x341
    val Mcause        = 0x342
    val Mtval         = 0x343
    val Mip           = 0x344

    // 机器计数器
    val Mcycle    = 0xb00
    val Minstret  = 0xb02
    val Mcycleh   = 0xb80
    val Minstreth = 0xb82

    // 用户只读计数器
    val Cycle    = 0xc00
    val Time     = 0xc01
    val Instret  = 0xc02
    val Cycleh   = 0xc80
    val Timeh    = 0xc81
    val Instreth = 0xc82

    // 机器 identity
    val Mvendorid = 0xf11
    val Marchid   = 0xf12
    val Mimpid    = 0xf13
    val Mhartid   = 0xf14

    // PMP
    val PmpCfgBase  = 0x3a0
    val PmpAddrBase = 0x3b0
    val PmpEntries  = 16

    // 触发寄存器：当前全部硬连 0，保留地址空间。
    val TriggerBase  = 0x7a0
    val TriggerCount = 16

    def pmpCfg(xlen: Int, index: Int): Int =
      if (xlen == 64) PmpCfgBase + index * 2 else PmpCfgBase + index
    def pmpAddr(index: Int): Int = PmpAddrBase + index
    def trigger(index: Int): Int = TriggerBase + index
  }

  /** CSR 地址 [9:8] 编码的最低可访问特权级。 */
  object Privilege {
    val U = 0
    val S = 1
    val M = 3
  }

  /** 读数据来源：CSR 模块把每个来源映射到对应寄存器的实时值。 */
  sealed trait ReadSource
  case object MstatusRead       extends ReadSource
  case object SstatusRead       extends ReadSource
  case object FflagsRead        extends ReadSource
  case object FrmRead           extends ReadSource
  case object FcsrRead          extends ReadSource
  case object MtvecRead         extends ReadSource
  case object StvecRead         extends ReadSource
  case object MepcRead          extends ReadSource
  case object SepcRead          extends ReadSource
  case object McauseRead        extends ReadSource
  case object ScauseRead        extends ReadSource
  case object MscratchRead      extends ReadSource
  case object SscratchRead      extends ReadSource
  case object MedelegRead       extends ReadSource
  case object MidelegRead       extends ReadSource
  case object MieRead           extends ReadSource
  case object MipRead           extends ReadSource
  case object SieRead           extends ReadSource
  case object SipRead           extends ReadSource
  case object ScounterenRead    extends ReadSource
  case object StvalRead         extends ReadSource
  case object SatpRead          extends ReadSource
  case object McycleRead        extends ReadSource
  case object McyclehRead       extends ReadSource
  case object MinstretRead      extends ReadSource
  case object MinstrethRead     extends ReadSource
  case object TimeRead          extends ReadSource
  case object TimehRead         extends ReadSource
  case object MisaRead          extends ReadSource
  case object MtvalRead         extends ReadSource
  case object McounterenRead    extends ReadSource
  case object McountinhibitRead extends ReadSource
  case object PmuRead           extends ReadSource
  case object IdentityRead      extends ReadSource
  case object PmpCfgRead        extends ReadSource
  case object PmpAddrRead       extends ReadSource
  case object ZeroRead          extends ReadSource

  /** 表项：地址 -> 读数据来源 / 写掩码 / 最低特权级 / 实现条件。
    *
    * `writeMask` 只表达 CSR 写合并掩码（CSRRW/CSRRS/CSRRC 的
    * read-modify-write），字段合法化仍由 mstatus/mtvec/mepc 寄存器模块
    * 完成，CSR 不再重复 Legalize/Align。
    */
  final case class Entry(
    address:     Int,
    read:        ReadSource,
    writeMask:   BigInt,
    index:       Int = 0,
    userCounter: Boolean = false,
    fpcsr: Boolean = false) {
    val privilege:       Int     = (address >> 8) & 0x3
    val readOnly:        Boolean = ((address >> 10) & 0x3) == 0x3
    val privilegeExempt: Boolean = userCounter || fpcsr
  }

  private def fullMask(xlen: Int): BigInt =
    if (xlen == 64) (BigInt(1) << 64) - 1 else BigInt(0xffffffffL)

  /** mstatus 的写合并掩码：profile 配置 + F 扩展补上的 FS 位。
    *
    * CSR 在 sstatus 写入路径也需要同一份掩码，放在这里避免两处推导。
    */
  def mstatusWriteMask(
      profile: ysyx_26030103_MMUProfile,
      useF: Boolean
  ): BigInt =
    (BigInt(profile.CSRMstatusWriteMask) & 0xffffffffL) |
      (if (useF) ysyx_26030103_mstatus.FsMask else BigInt(0))

  /** 构造当前配置的 CSR 表。
    *
    * 固定 CSR 由 `profile.csrSupported` 裁剪；FPCSR/用户计数器/PMU
    * 与配置的 CSR_ENABLED 解耦，和原有实现集合保持一致。表项去重后
    * 按地址排序，便于测试和调试。
    */
  def entries(
    profile:      ysyx_26030103_MMUProfile,
    xlen:         Int,
    useF:         Boolean,
    pmuZihpm:     Boolean,
    pmuSscofpmf:  Boolean,
    counterCount: Int
  ): Seq[Entry] = {
    require(xlen == 32 || xlen == 64, s"CSRAddressMap XLEN=$xlen 非法")
    val mask = fullMask(xlen)
    val csrOn: Int => Boolean = profile.csrSupported
    val out  = mutable.ArrayBuffer[Entry]()
    val seen = mutable.HashSet[Int]()

    def add(
      address:     Int,
      read:        ReadSource,
      enabled:     Boolean = true,
      index:       Int = 0,
      writeMask:   BigInt = mask,
      userCounter: Boolean = false,
      fpcsr:       Boolean = false
    ): Unit = {
      if (enabled && seen.add(address)) {
        out += Entry(address, read, writeMask & mask, index, userCounter, fpcsr)
      }
    }

    // mstatus 的 FS 位由 F 扩展补进读写掩码；写掩码同时用于 CSR 的
    // CSRRW/CSRRS/CSRRC 合并，字段合法化在 mstatus 模块内完成。
    val mstatusMask   = mstatusWriteMask(profile, useF)
    val satpWriteMask = BigInt(profile.CSRSatpWriteMask) & mask

    // 浮点状态 CSR：只要 RISCV_F=y 就实现，和 CSREnabled 解耦。
    add(Address.Fflags, FflagsRead, enabled = useF, fpcsr = true)
    add(Address.Frm, FrmRead, enabled = useF, fpcsr = true)
    add(Address.Fcsr, FcsrRead, enabled = useF, fpcsr = true)

    add(Address.Sstatus, SstatusRead, csrOn(Address.Sstatus))
    add(Address.Sie, SieRead, csrOn(Address.Sie))
    add(Address.Stvec, StvecRead, csrOn(Address.Stvec))
    add(Address.Scounteren, ScounterenRead, csrOn(Address.Scounteren))
    add(Address.Senvcfg, ZeroRead, csrOn(Address.Senvcfg))
    add(Address.Sscratch, SscratchRead, csrOn(Address.Sscratch))
    add(Address.Sepc, SepcRead, csrOn(Address.Sepc))
    add(Address.Scause, ScauseRead, csrOn(Address.Scause))
    add(Address.Stval, StvalRead, csrOn(Address.Stval))
    add(Address.Sip, SipRead, csrOn(Address.Sip))
    add(Address.Satp, SatpRead, csrOn(Address.Satp), writeMask = satpWriteMask)

    add(Address.Mstatus, MstatusRead, csrOn(Address.Mstatus), writeMask = mstatusMask)
    add(Address.Misa, MisaRead, csrOn(Address.Misa))
    add(Address.Medeleg, MedelegRead, csrOn(Address.Medeleg))
    add(Address.Mideleg, MidelegRead, csrOn(Address.Mideleg))
    add(Address.Mie, MieRead, csrOn(Address.Mie))
    add(Address.Mtvec, MtvecRead, csrOn(Address.Mtvec))
    add(Address.Mcounteren, McounterenRead, csrOn(Address.Mcounteren))
    add(Address.Menvcfg, ZeroRead, csrOn(Address.Menvcfg))
    add(Address.Mstatush, ZeroRead, xlen == 32 && csrOn(Address.Mstatush))
    add(Address.Menvcfgh, ZeroRead, csrOn(Address.Menvcfgh))
    add(Address.Mcountinhibit, McountinhibitRead, csrOn(Address.Mcountinhibit))
    add(Address.Mscratch, MscratchRead, csrOn(Address.Mscratch))
    add(Address.Mepc, MepcRead, csrOn(Address.Mepc))
    add(Address.Mcause, McauseRead, csrOn(Address.Mcause))
    add(Address.Mtval, MtvalRead, csrOn(Address.Mtval))
    add(Address.Mip, MipRead, csrOn(Address.Mip))

    add(Address.Mcycle, McycleRead, csrOn(Address.Mcycle))
    add(Address.Minstret, MinstretRead, csrOn(Address.Minstret))
    add(Address.Mcycleh, McyclehRead, csrOn(Address.Mcycleh))
    add(Address.Minstreth, MinstrethRead, csrOn(Address.Minstreth))

    // 用户计数器是 U 级地址，但 M 模式始终可访问；HAS_U=n 时仍由
    // CSR 模块实现，IDU 的 privilege 检查会拒绝 U 模式访问。
    add(Address.Cycle, McycleRead, userCounter = true)
    add(Address.Time, TimeRead, userCounter = true)
    add(Address.Instret, MinstretRead, userCounter = true)
    if (xlen == 32) {
      add(Address.Cycleh, McyclehRead, userCounter = true)
      add(Address.Timeh, TimehRead, userCounter = true)
      add(Address.Instreth, MinstrethRead, userCounter = true)
    }

    if (pmuZihpm) {
      for (index <- 0 until counterCount) {
        add(ysyx_26030103_PMUCSR.CounterBase + index, PmuRead, index = index)
        add(ysyx_26030103_PMUCSR.EventBase + index, PmuRead, index = index)
        add(ysyx_26030103_PMUCSR.UserCounterBase + index, PmuRead, index = index, userCounter = true)
        if (xlen == 32) {
          add(ysyx_26030103_PMUCSR.CounterHighBase + index, PmuRead, index = index)
          add(ysyx_26030103_PMUCSR.UserCounterHighBase + index, PmuRead, index = index, userCounter = true)
        }
      }
      if (pmuSscofpmf) {
        add(ysyx_26030103_PMUCSR.CountOvf, PmuRead)
      }
    }

    add(Address.Mvendorid, IdentityRead, csrOn(Address.Mvendorid))
    add(Address.Marchid, IdentityRead, csrOn(Address.Marchid))
    add(Address.Mimpid, IdentityRead, csrOn(Address.Mimpid))
    add(Address.Mhartid, IdentityRead, csrOn(Address.Mhartid))

    val pmpConfigIndices = if (xlen == 64) Seq(0, 1) else (0 until 4)
    for (index <- pmpConfigIndices) {
      val address = Address.pmpCfg(xlen, index)
      add(address, PmpCfgRead, csrOn(address), index = index)
    }
    for (index <- 0 until Address.PmpEntries) {
      val address = Address.pmpAddr(index)
      add(address, PmpAddrRead, csrOn(address), index = index)
    }

    // 触发寄存器当前没有实现字段，读 0、写忽略；地址始终保留。
    for (index <- 0 until Address.TriggerCount) {
      add(Address.trigger(index), ZeroRead, index = index)
    }

    // 配置里额外声明、但没有专门读口的地址保持原有行为：IDU 认为
    // 已实现，CSR 读回 0、写不落地。这样该表与 CSR_ENABLED 不会
    // 产生“译码合法但 CSR 无读口”的静默分叉。
    for (address <- profile.CSREnabled.toSeq.sorted if csrOn(address)) {
      add(address, ZeroRead)
    }

    out.sortBy(_.address).toSeq
  }
}

/** 共享的 CSR 地址译码器。
  *
  * IDU 与 CSR 处于不同流水级，不能共享同一份组合输出，因此两边各
  * 例化一个实例，但地址表、权限、只读属性和写掩码只有这一份实现。
  */
class ysyx_26030103_CSRAddressDecoder(
  val entries: Seq[ysyx_26030103_CSRAddressMap.Entry],
  val XLEN:    Int = 32)
    extends Module {
  require(XLEN == 32 || XLEN == 64, s"CSRAddressDecoder XLEN=$XLEN 非法")
  import ysyx_26030103_CSRAddressMap._

  val io = IO(new Bundle {
    val address = Input(UInt(12.W))

    /** 当前配置真正实现该地址（CSR_MISSING_MODE 由 IDU 另行处理）。 */
    val valid = Output(Bool())

    /** 表项声明的最低可访问特权级；未实现地址回退到地址位 [9:8]。 */
    val privilege = Output(UInt(2.W))

    /** fflags/frm/fcsr 与用户计数器不受地址特权级检查限制。 */
    val privilegeExempt = Output(Bool())
    val userCounter     = Output(Bool())
    val fpcsr           = Output(Bool())
    val readOnly        = Output(Bool())

    /** CSRRW/CSRRS/CSRRC 的写合并掩码。 */
    val writeMask = Output(UInt(XLEN.W))
  })

  private val address     = io.address
  private val defaultMask =
    (if (XLEN == 64) (BigInt(1) << 64) - 1 else BigInt(0xffffffffL))
      .U(XLEN.W)

  io.valid           := ysyx_26030103_AnyOf(entries.map(entry => address === entry.address.U(12.W)))
  // RISC-V 的 CSR 最低特权级/只读属性直接由地址位 [9:8]/[11:10] 编码，
  // 表项字段只是同一编码的元数据；译码器直接取地址位，避免为上百个
  // 表项生成常量 mux。
  io.privilege       := address(9, 8)
  io.privilegeExempt := ysyx_26030103_AnyOf(
    entries
      .filter(_.privilegeExempt)
      .map(entry => address === entry.address.U(12.W))
  )
  io.userCounter     := ysyx_26030103_AnyOf(
    entries
      .filter(_.userCounter)
      .map(entry => address === entry.address.U(12.W))
  )
  io.fpcsr           := ysyx_26030103_AnyOf(
    entries
      .filter(_.fpcsr)
      .map(entry => address === entry.address.U(12.W))
  )
  io.readOnly        := address(11, 10) === "b11".U(2.W)
  // 绝大多数 CSR 的写合并掩码是整字；只为 mstatus/satp 这类受限掩码
  // 生成选择逻辑，避免把上百个相同常量塞进 mux。
  private val fullMaskValue =
    if (XLEN == 64) (BigInt(1) << 64) - 1 else BigInt(0xffffffffL)
  io.writeMask := MuxLookup(address, defaultMask)(
    entries
      .filter(_.writeMask != fullMaskValue)
      .map(entry => entry.address.U(12.W) -> entry.writeMask.U(XLEN.W))
  )
}
