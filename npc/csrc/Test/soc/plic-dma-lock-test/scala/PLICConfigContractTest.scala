package ysyx_26030103.test

import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** Elaboration-time PLIC configuration contract.
  *
  * The RTL advertises UART source 10 and the S-context claim window at
  * 0x201004, so a profile that cannot address either must be rejected rather
  * than silently generating a smaller/partially mapped PLIC.
  */
object PLICConfigContractTest extends App {
  private def expectRejected(name: String)(body: => Unit): Unit = {
    try {
      body
      sys.error(s"$name was accepted but must be rejected")
    } catch {
      case _: IllegalArgumentException => ()
    }
  }

  val defaults = ysyx_26030103_MMUProfile()
  assert(defaults.PLICSources > 10, "default PLIC must include source 10")
  assert(defaults.PLICSize >= 0x202000L,
    "default PLIC must cover the S-context claim window")
  assert(defaults.PLICBase % 4096 == 0 && defaults.PLICSize % 4096 == 0,
    "default PLIC must be page aligned")

  expectRejected("PLIC_SOURCES=10") {
    ysyx_26030103_MMUProfile(PLICSources = 10)
  }
  expectRejected("PLIC_SIZE below S claim window") {
    ysyx_26030103_MMUProfile(PLICSize = 0x201000L)
  }
  expectRejected("unaligned PLIC_BASE") {
    ysyx_26030103_MMUProfile(PLICBase = 0x0c000001L)
  }
  expectRejected("unaligned PLIC_SIZE") {
    ysyx_26030103_MMUProfile(PLICSize = 0x202001L)
  }

  println("[PASS] PLIC configuration contract")
}
