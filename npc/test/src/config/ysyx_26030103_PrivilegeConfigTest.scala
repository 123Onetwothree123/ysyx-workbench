package ysyx_26030103.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** HAS_S/HAS_U/MMU 组合的 elaboration 期约束。
  *
  * 这里直接构造 case class，确保非法组合在生成 RTL 之前就抛出明确的
  * IllegalArgumentException，而不是静默保留不可达的 S/U 逻辑。
  */
class ysyx_26030103_PrivilegeConfigTest extends AnyFlatSpec with Matchers {
  private def env(values: (String, String)*): String => Option[String] =
    values.toMap.get

  "MMUProfile" should "默认保持 HAS_S=y/HAS_U=y 且不改变 MMU 开关" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(env())
    p.hasS should be(true)
    p.hasU should be(true)
    p.enabled should be(false)
    p.lowestPrivilege should be(0)
  }

  it should "支持 HAS_S=y/HAS_U=n 的最低特权级 S" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(env(
      "HAS_S" -> "y",
      "HAS_U" -> "n"
    ))
    p.hasS should be(true)
    p.hasU should be(false)
    p.lowestPrivilege should be(1)
    p.csrSupported(0x100) should be(true)
    p.csrSupported(0xc00) should be(false)
  }

  it should "支持 M-only（HAS_S=n/HAS_U=n/HAS_MMU=n）" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(env(
      "HAS_S" -> "n",
      "HAS_U" -> "n",
      "HAS_MMU" -> "n"
    ))
    p.hasS should be(false)
    p.hasU should be(false)
    p.lowestPrivilege should be(3)
    p.csrSupported(0x300) should be(true)
    p.csrSupported(0x100) should be(false)
    p.csrSupported(0x180) should be(false)
    p.csrSupported(0xc00) should be(false)
    p.csrSupported(0x302) should be(false)
    p.csrSupported(0x303) should be(false)
    p.csrSupported(0x306) should be(false)
  }

  it should "HAS_S=n 且未给出 HAS_U 时默认 M-only" in {
    val p = ysyx_26030103_MMUProfile.fromEnv(env("HAS_S" -> "n"))
    p.hasS should be(false)
    p.hasU should be(false)
  }

  it should "明确拒绝 HAS_MMU=y 且 HAS_S=n" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(enabled = true, hasS = false, hasU = false)
    }
    error.getMessage should include("HAS_MMU=y 需要 HAS_S=y")
  }

  it should "明确拒绝 HAS_U=y 且 HAS_S=n" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(hasS = false, hasU = true)
    }
    error.getMessage should include("HAS_U=y 需要 HAS_S=y")
  }

  it should "fromEnv 拒绝 M-only 下打开 MMU" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile.fromEnv(env(
        "HAS_S" -> "n",
        "HAS_U" -> "n",
        "HAS_MMU" -> "y"
      ))
    }
    error.getMessage should include("HAS_MMU=y 需要 HAS_S=y")
  }
}
