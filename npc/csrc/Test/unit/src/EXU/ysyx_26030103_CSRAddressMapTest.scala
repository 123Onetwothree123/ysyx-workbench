package ysyx_26030103.exu

import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CSR 单表化的地址集合/权限/写掩码回归。
  *
  * `CSRAddressMap` 是 RTL 唯一的 CSR 地址来源；这里用配置层
  * `ImplementedCSRAddresses` 作为“旧口径 oracle”做等价性检查，防止
  * 表项漏掉某个配置矩阵里的 CSR。
  */
class ysyx_26030103_CSRAddressMapTest extends AnyFlatSpec with Matchers {
  private def env(values: (String, String)*): String => Option[String] =
    values.toMap.get

  private def addresses(
      profile: ysyx_26030103_MMUProfile,
      xlen: Int,
      useF: Boolean,
      zihpm: Boolean,
      sscofpmf: Boolean
  ): Seq[Int] =
    ysyx_26030103_CSRAddressMap
      .entries(profile, xlen, useF, zihpm, sscofpmf,
        _root_.ysyx_26030103.common.ysyx_26030103_PMUEventID.CounterCount)
      .map(_.address)

  private def expected(
      profile: ysyx_26030103_MMUProfile,
      xlen: Int,
      useF: Boolean,
      zihpm: Boolean,
      sscofpmf: Boolean
  ): Seq[Int] =
    ysyx_26030103_MMUProfile
      .ImplementedCSRAddresses(xlen, profile, useF, zihpm, sscofpmf,
        _root_.ysyx_26030103.common.ysyx_26030103_PMUEventID.CounterCount)
      .distinct
      .sorted

  private def check(
      name: String,
      profile: ysyx_26030103_MMUProfile,
      xlen: Int,
      useF: Boolean,
      zihpm: Boolean,
      sscofpmf: Boolean
  ): Unit = {
    withClue(name + ": ") {
      addresses(profile, xlen, useF, zihpm, sscofpmf).sorted should be(
        expected(profile, xlen, useF, zihpm, sscofpmf))
    }
  }

  "CSRAddressMap" should "与 RV32 默认实现集合一致" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(env(), xlen = 32)
    check("rv32 default", p, 32, useF = false, zihpm = false,
      sscofpmf = false)
    check("rv32 F/PMU", p, 32, useF = true, zihpm = true,
      sscofpmf = true)
  }

  it should "与 RV64 默认实现集合一致" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(env(), xlen = 64)
    check("rv64 default", p, 64, useF = false, zihpm = false,
      sscofpmf = false)
    check("rv64 F/PMU", p, 64, useF = true, zihpm = true,
      sscofpmf = true)
  }

  it should "与 M-only/M+U/S-only 配置一致" in {
    val mOnly = ysyx_26030103_MMUProfile.fromEnv(
      env("HAS_S" -> "n", "HAS_U" -> "n", "HAS_MMU" -> "n"), xlen = 32)
    check("m-only", mOnly, 32, useF = false, zihpm = false,
      sscofpmf = false)
    val mPlusU = ysyx_26030103_MMUProfile.fromEnv(
      env("HAS_S" -> "n", "HAS_U" -> "y", "HAS_MMU" -> "n"), xlen = 32)
    check("m+u", mPlusU, 32, useF = false, zihpm = false,
      sscofpmf = false)
    val sOnly = ysyx_26030103_MMUProfile.fromEnv(
      env("HAS_S" -> "y", "HAS_U" -> "n"), xlen = 32)
    check("s-only", sOnly, 32, useF = false, zihpm = false,
      sscofpmf = false)
  }

  it should "与打开 PMP 的配置一致" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(
      env("HAS_PMP" -> "y", "PMP_ENTRIES" -> "16"), xlen = 64)
    check("rv64 pmp", p, 64, useF = true, zihpm = true, sscofpmf = true)
  }

  it should "保留 CSR 权限/只读/写掩码语义" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(
      env("CSR_MSTATUS_MPP_WRITE" -> "ignore"), xlen = 32)
    val entries = ysyx_26030103_CSRAddressMap
      .entries(p, 32, useF = true, pmuZihpm = false, pmuSscofpmf = false,
        counterCount = 29)
      .map(entry => entry.address -> entry)
      .toMap

    entries(0x300).privilege should be(3)
    entries(0x100).privilege should be(1)
    entries(0x001).privilege should be(0)
    entries(0x001).fpcsr should be(true)
    entries(0x001).privilegeExempt should be(true)
    entries(0x001).readOnly should be(false)
    // 用户计数器是 U 级地址，但特权检查豁免；编码上仍然是只读。
    entries(0xc00).privilege should be(0)
    entries(0xc00).userCounter should be(true)
    entries(0xc00).privilegeExempt should be(true)
    entries(0xc00).readOnly should be(true)
    entries(0xb00).readOnly should be(false)
    entries(0x180).readOnly should be(false)
    // F 扩展的 FS 位必须并进 mstatus 写掩码，不能被 profile 默认掩码清掉。
    (entries(0x300).writeMask & BigInt("6000", 16)) should be(
      BigInt("6000", 16))
    entries(0x180).writeMask should be(
      BigInt(p.CSRSatpWriteMask) & BigInt("ffffffff", 16))
  }
}
