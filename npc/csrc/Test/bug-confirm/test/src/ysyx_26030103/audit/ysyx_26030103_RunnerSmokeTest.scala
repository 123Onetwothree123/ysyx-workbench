package ysyx_26030103.audit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ysyx_26030103_RunnerSmokeTest extends AnyFlatSpec with Matchers {
  "the test runner" should "compile and execute a trivial assertion" in {
    1 + 1 should be(2)
  }
}
