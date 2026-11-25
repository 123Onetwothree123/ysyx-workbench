package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ysyx_26030103_JsonConfigTest extends AnyFlatSpec with Matchers {
  "JsonConfig" should "map typed paths to legacy flat keys" in {
    val config = ysyx_26030103_JsonConfig.parseDocument(
      """
        |{
        |  "MDU_IMPL": "rocket",
        |  "MUL_IMPL": "dadda",
        |  "PLIC_SOURCES": "64",
        |  "ICACHE": "n",
        |  "DCACHE": "y",
        |  "DCACHE_BLOCK_SIZE_LOG2": "4",
        |  "CSR_MSTATUS_READ_MASK": "255"
        |}
        |""".stripMargin
    )
    config.string("MDU.impl", "standard") should be("rocket")
    config.string("MDU.mul_impl", "shift_add") should be("dadda")
    config.int("interrupt.PLIC.sources", 0) should be(64)
    config.bool("cache.ICache.enabled", true) should be(false)
    config.bool("cache.DCache.enabled", false) should be(true)
    config.int("cache.DCache.block_size_log2", 0) should be(4)
    config.string("CSR.mstatus.read_mask", "") should be("255")
  }

  it should "project nested typed values onto the legacy flat keys" in {
    val config = ysyx_26030103_JsonConfig.parseDocument(
      """
        |{
        |  "MDU": {
        |    "impl": "rocket",
        |    "mul_impl": "dadda",
        |    "mul": { "width": 64 }
        |  }
        |}
        |""".stripMargin
    )
    config.flat.get("MDU_IMPL") should be(Some("rocket"))
    config.flat.get("MUL_IMPL") should be(Some("dadda"))
    config.flat.get("MUL_WIDTH") should be(Some("64"))
  }
}
