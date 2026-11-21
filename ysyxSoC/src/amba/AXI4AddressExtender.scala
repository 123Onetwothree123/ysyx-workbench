package ysyx

import chisel3._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._

/** 只扩展上游地址空间的 AXI4 直通节点 */
class AXI4AddressExtender(addrBits: Int)(implicit p: Parameters)
    extends LazyModule {
  val node = AXI4AdapterNode(
    masterFn = { m => m },
    slaveFn = { s =>
      s.copy(slaves = Seq(s.slaves.head.copy(
        address = Seq(AddressSet(0, (BigInt(1) << addrBits) - 1)))))
    }
  )

  lazy val module = new LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val (out, _) = node.out(0)
    out.aw.valid := in.aw.valid
    in.aw.ready := out.aw.ready
    out.aw.bits := in.aw.bits
    out.aw.bits.addr := in.aw.bits.addr(out.aw.bits.addr.getWidth - 1, 0)
    out.w.valid := in.w.valid
    in.w.ready := out.w.ready
    out.w.bits := in.w.bits
    in.b.valid := out.b.valid
    out.b.ready := in.b.ready
    in.b.bits := out.b.bits
    out.ar.valid := in.ar.valid
    in.ar.ready := out.ar.ready
    out.ar.bits := in.ar.bits
    out.ar.bits.addr := in.ar.bits.addr(out.ar.bits.addr.getWidth - 1, 0)
    in.r.valid := out.r.valid
    out.r.ready := in.r.ready
    in.r.bits := out.r.bits
  }
}
