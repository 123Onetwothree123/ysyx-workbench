package ysyx_26030103.test

import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.{ysyx_26030103_KconfigDefaults => KconfigDefaults}

/** 单元测试公共夹具：固定 XLEN 的 profile/NPCConfig 与断言小工具。 */
object ysyx_26030103_TestCommon {
  val rv32Profile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(_ => None, xlen = 32)

  val rv64Profile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(_ => None, xlen = 64)

  /** RV32 配置；常用字段可直接命名覆盖，其余用 configure 覆盖。 */
  def npc32(
      XLEN: Int = 32,
      AddressWidth: Int = 32,
      MMU: ysyx_26030103_MMUProfile = rv32Profile,
      ICacheEnable: Boolean = true,
      ICacheBlockSizeLog2: Int = KconfigDefaults.NpcICacheBlockSizeLog2,
      ICacheIndexBits: Int = KconfigDefaults.NpcICacheIndexBits,
      DCacheEnable: Boolean = false,
      DCacheBlockSizeLog2: Int = KconfigDefaults.NpcDCacheBlockSizeLog2,
      DCacheIndexBits: Int = KconfigDefaults.NpcDCacheIndexBits,
      configure: ysyx_26030103_NPCConfig => ysyx_26030103_NPCConfig = identity
  ): ysyx_26030103_NPCConfig =
    configure(ysyx_26030103_NPCConfig(
      XLEN = XLEN,
      AddressWidth = AddressWidth,
      MMU = MMU,
      ICacheEnable = ICacheEnable,
      ICacheBlockSizeLog2 = ICacheBlockSizeLog2,
      ICacheIndexBits = ICacheIndexBits,
      DCacheEnable = DCacheEnable,
      DCacheBlockSizeLog2 = DCacheBlockSizeLog2,
      DCacheIndexBits = DCacheIndexBits
    ))

  /** RV64 配置；常用字段可直接命名覆盖，其余用 configure 覆盖。 */
  def npc64(
      XLEN: Int = 64,
      AddressWidth: Int = 64,
      MMU: ysyx_26030103_MMUProfile = rv64Profile,
      ICacheEnable: Boolean = true,
      ICacheBlockSizeLog2: Int = KconfigDefaults.NpcICacheBlockSizeLog2,
      ICacheIndexBits: Int = KconfigDefaults.NpcICacheIndexBits,
      DCacheEnable: Boolean = false,
      DCacheBlockSizeLog2: Int = KconfigDefaults.NpcDCacheBlockSizeLog2,
      DCacheIndexBits: Int = KconfigDefaults.NpcDCacheIndexBits,
      configure: ysyx_26030103_NPCConfig => ysyx_26030103_NPCConfig = identity
  ): ysyx_26030103_NPCConfig =
    npc32(
      XLEN = XLEN,
      AddressWidth = AddressWidth,
      MMU = MMU,
      ICacheEnable = ICacheEnable,
      ICacheBlockSizeLog2 = ICacheBlockSizeLog2,
      ICacheIndexBits = ICacheIndexBits,
      DCacheEnable = DCacheEnable,
      DCacheBlockSizeLog2 = DCacheBlockSizeLog2,
      DCacheIndexBits = DCacheIndexBits,
      configure = configure
    )
}
