package ysyx_26030103.test

import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig

/** Checks that the public Scala configuration rejects geometries which the
  * generated hardware cannot represent safely.
  */
object ConfigGuardTest extends App {
  private var failures = 0

  try {
    ysyx_26030103_NPCConfig(
      BlockSizeLog2 = 2,
      IndexBits = 1,
      BTBBits = 1,
      BTBWays = 1,
      JalBTBBits = 1,
      JalBTBWays = 1,
      RASBits = 1
    )
    println("[PASS] config guard: minimum supported geometry was accepted")
  } catch {
    case error: IllegalArgumentException =>
      failures += 1
      Console.err.println(
        s"[FAIL] config guard: minimum supported geometry was rejected: ${error.getMessage}"
      )
  }

  private def expectRejected(name: String)(body: => Any): Unit = {
    try {
      body
      failures += 1
      Console.err.println(s"[FAIL] config guard: $name was accepted")
    } catch {
      case _: IllegalArgumentException =>
        println(s"[PASS] config guard: $name was rejected")
    }
  }

  private def expectAccepted(name: String)(body: => Any): Unit = {
    try {
      body
      println(s"[PASS] config guard: $name was accepted")
    } catch {
      case error: IllegalArgumentException =>
        failures += 1
        Console.err.println(
          s"[FAIL] config guard: $name was rejected: ${error.getMessage}"
        )
    }
  }

  expectRejected("BlockSizeLog2=1") {
    ysyx_26030103_NPCConfig(BlockSizeLog2 = 1)
  }
  expectRejected("IndexBits=0") {
    ysyx_26030103_NPCConfig(IndexBits = 0)
  }
  expectRejected("IndexBits+BlockSizeLog2>=AddressWidth") {
    ysyx_26030103_NPCConfig(IndexBits = 28, BlockSizeLog2 = 4)
  }
  expectAccepted("BTBBits=0 (one-set BTB)") {
    ysyx_26030103_NPCConfig(BTBBits = 0)
  }
  expectRejected("BTBWays=0") {
    ysyx_26030103_NPCConfig(BTBWays = 0)
  }
  expectAccepted("JalBTBBits=0 (one-set JAL BTB)") {
    ysyx_26030103_NPCConfig(JalBTBBits = 0)
  }
  expectRejected("JalBTBWays=0") {
    ysyx_26030103_NPCConfig(JalBTBWays = 0)
  }
  expectAccepted("RASBits=0 (one-entry RAS)") {
    ysyx_26030103_NPCConfig(RASBits = 0)
  }

  if (failures != 0) {
    Console.err.println(s"$failures invalid configuration(s) escaped NPCConfig validation")
    sys.exit(1)
  }
}
