package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

/** 只读 AXI 二选一：ICache 与 PTW 共用仲裁器的 IFU 读端口。
  *
  * 两者都只发单拍 AR，且同一时刻只允许一个 owner 有在途读。响应由
  * RLAST 释放 owner。写通道全部拒绝，不引入新的写主设备。
  */
class ysyx_26030103_AXIReadMux extends Module {
  val io = IO(new Bundle {
    val a = Flipped(new ysyx_26030103_AXI5IO(32))
    val b = Flipped(new ysyx_26030103_AXI5IO(32))
    val out = new ysyx_26030103_AXI5IO(32)
  })
  val OwnerA = 0.U(1.W)
  val OwnerB = 1.U(1.W)
  val busy = RegInit(false.B)
  val owner = RegInit(OwnerA)
  val last = RegInit(false.B)

  def quiet(m: ysyx_26030103_AXI5IO): Unit = {
    m.AW.AWREADY := false.B
    m.AR.ARREADY := false.B
    m.W.WREADY := false.B
    m.B.BVALID := false.B
    m.B.BID := 0.U
    m.B.BRESP := 0.U
    m.R.RVALID := false.B
    m.R.RID := 0.U
    m.R.RDATA := 0.U
    m.R.RRESP := 0.U
    m.R.RLAST := false.B
  }
  quiet(io.a)
  quiet(io.b)

  io.out.AW.AWVALID := false.B
  io.out.AW.AWID := 0.U
  io.out.AW.AWADDR := 0.U
  io.out.AW.AWLEN := 0.U
  io.out.AW.AWSIZE := 2.U
  io.out.AW.AWBURST := 0.U
  io.out.AW.AWPROT := 0.U
  io.out.AW.AWCACHE := 0.U
  io.out.AW.AWQOS := 0.U
  io.out.W.WVALID := false.B
  io.out.W.WDATA := 0.U
  io.out.W.WSTRB := 0.U
  io.out.W.WLAST := false.B
  io.out.B.BREADY := false.B
  io.out.AR.ARVALID := false.B
  io.out.AR.ARID := 0.U
  io.out.AR.ARADDR := 0.U
  io.out.AR.ARLEN := 0.U
  io.out.AR.ARSIZE := 2.U
  io.out.AR.ARBURST := 0.U
  io.out.AR.ARPROT := 0.U
  io.out.AR.ARCACHE := 0.U
  io.out.AR.ARQOS := 0.U
  io.out.R.RREADY := false.B

  val chooseB = !busy && io.b.AR.ARVALID && (!io.a.AR.ARVALID || last)
  val chooseA = !busy && io.a.AR.ARVALID && !chooseB
  when(chooseA) {
    io.out.AR.ARVALID := io.a.AR.ARVALID
    io.out.AR.ARADDR := io.a.AR.ARADDR
    io.out.AR.ARLEN := io.a.AR.ARLEN
    io.out.AR.ARSIZE := io.a.AR.ARSIZE
    io.out.AR.ARBURST := io.a.AR.ARBURST
    io.out.AR.ARPROT := io.a.AR.ARPROT
    io.out.AR.ARCACHE := io.a.AR.ARCACHE
    io.out.AR.ARQOS := io.a.AR.ARQOS
    io.a.AR.ARREADY := io.out.AR.ARREADY
  }.elsewhen(chooseB) {
    io.out.AR.ARVALID := io.b.AR.ARVALID
    io.out.AR.ARADDR := io.b.AR.ARADDR
    io.out.AR.ARLEN := io.b.AR.ARLEN
    io.out.AR.ARSIZE := io.b.AR.ARSIZE
    io.out.AR.ARBURST := io.b.AR.ARBURST
    io.out.AR.ARPROT := io.b.AR.ARPROT
    io.out.AR.ARCACHE := io.b.AR.ARCACHE
    io.out.AR.ARQOS := io.b.AR.ARQOS
    io.b.AR.ARREADY := io.out.AR.ARREADY
  }
  val arFire = io.out.AR.ARVALID && io.out.AR.ARREADY
  when(arFire) {
    busy := true.B
    owner := Mux(chooseB, OwnerB, OwnerA)
    last := Mux(chooseB, false.B, true.B)
  }

  when(busy && owner === OwnerA) {
    io.a.R.RVALID := io.out.R.RVALID
    io.a.R.RID := io.out.R.RID
    io.a.R.RDATA := io.out.R.RDATA
    io.a.R.RRESP := io.out.R.RRESP
    io.a.R.RLAST := io.out.R.RLAST
    io.out.R.RREADY := io.a.R.RREADY
  }.elsewhen(busy && owner === OwnerB) {
    io.b.R.RVALID := io.out.R.RVALID
    io.b.R.RID := io.out.R.RID
    io.b.R.RDATA := io.out.R.RDATA
    io.b.R.RRESP := io.out.R.RRESP
    io.b.R.RLAST := io.out.R.RLAST
    io.out.R.RREADY := io.b.R.RREADY
  }
  when(busy && io.out.R.RVALID && io.out.R.RREADY && io.out.R.RLAST) {
    busy := false.B
  }
}

/** 把 PTW 的简单 32 位读总线转换成单拍 AXI 读。 */
class ysyx_26030103_PTWToAXI extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTEBusRequest))
    val resp = Decoupled(new ysyx_26030103_PTEBusResponse)
    val axi = new ysyx_26030103_AXI5IO(32)
  })
  io.axi.AW.AWVALID := false.B
  io.axi.AW.AWID := 0.U
  io.axi.AW.AWADDR := 0.U
  io.axi.AW.AWLEN := 0.U
  io.axi.AW.AWSIZE := 2.U
  io.axi.AW.AWBURST := 0.U
  io.axi.AW.AWPROT := 0.U
  io.axi.AW.AWCACHE := 0.U
  io.axi.AW.AWQOS := 0.U
  io.axi.W.WVALID := false.B
  io.axi.W.WDATA := 0.U
  io.axi.W.WSTRB := 0.U
  io.axi.W.WLAST := false.B
  io.axi.B.BREADY := false.B
  io.axi.AR.ARVALID := io.req.valid
  io.axi.AR.ARID := 0.U
  io.axi.AR.ARADDR := io.req.bits.addr
  io.axi.AR.ARLEN := 0.U
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.AR.ARCACHE := 0.U
  io.axi.AR.ARQOS := 0.U
  io.req.ready := io.axi.AR.ARREADY
  io.resp.valid := io.axi.R.RVALID
  io.resp.bits.data := io.axi.R.RDATA
  io.resp.bits.fault := io.axi.R.RRESP =/= 0.U
  io.axi.R.RREADY := io.resp.ready
}
