package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

/** 只读 AXI 二选一：ICache 与 PTW 共用仲裁器的 IFU 读端口。
  *
  * 两者都只发单拍 AR，且同一时刻只允许一个 owner 有在途读。响应由
  * RLAST 释放 owner。写通道全部拒绝，不引入新的写主设备。
  */
class ysyx_26030103_AXIReadMux(passWriteA: Boolean = false) extends Module {
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
  // a 的写通道可选择直通（PTW 复用 LSU 数据通路时使用）。
  io.a.AW.AWREADY := false.B
  io.a.AR.ARREADY := false.B
  io.a.W.WREADY := false.B
  io.a.B.BVALID := false.B
  io.a.B.BID := 0.U
  io.a.B.BRESP := 0.U
  io.a.R.RVALID := false.B
  io.a.R.RID := 0.U
  io.a.R.RDATA := 0.U
  io.a.R.RRESP := 0.U
  io.a.R.RLAST := false.B
  quiet(io.b)

  if (passWriteA) {
    io.a.R.RVALID := false.B
    io.a.R.RID := 0.U
    io.a.R.RDATA := 0.U
    io.a.R.RRESP := 0.U
    io.a.R.RLAST := false.B
    io.a.AR.ARREADY := false.B
    io.out.AW <> io.a.AW
    io.out.W <> io.a.W
    io.a.B <> io.out.B
    io.a.AW.AWREADY := io.out.AW.AWREADY
    io.a.W.WREADY := io.out.W.WREADY
    io.a.B.BVALID := io.out.B.BVALID
    io.a.B.BID := io.out.B.BID
    io.a.B.BRESP := io.out.B.BRESP
  } else {
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
  }
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
    val axiWrite = new ysyx_26030103_AXI5IO(32)
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
  io.axi.AR.ARVALID := io.req.valid && !io.req.bits.write
  io.axi.AR.ARID := 0.U
  io.axi.AR.ARADDR := io.req.bits.addr
  io.axi.AR.ARLEN := 0.U
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.AR.ARCACHE := 0.U
  io.axi.AR.ARQOS := 0.U

  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeActive = RegInit(false.B)
  io.axiWrite.AW.AWVALID := io.req.valid && io.req.bits.write && !awDone
  io.axiWrite.AW.AWID := 0.U
  io.axiWrite.AW.AWADDR := io.req.bits.addr
  io.axiWrite.AW.AWLEN := 0.U
  io.axiWrite.AW.AWSIZE := 2.U
  io.axiWrite.AW.AWBURST := 1.U
  io.axiWrite.AW.AWPROT := 0.U
  io.axiWrite.AW.AWCACHE := 0.U
  io.axiWrite.AW.AWQOS := 0.U
  io.axiWrite.W.WVALID := io.req.valid && io.req.bits.write && !wDone
  io.axiWrite.W.WDATA := io.req.bits.data
  io.axiWrite.W.WSTRB := io.req.bits.strb
  io.axiWrite.W.WLAST := true.B
  io.axiWrite.AR.ARVALID := false.B
  io.axiWrite.AR.ARID := 0.U
  io.axiWrite.AR.ARADDR := 0.U
  io.axiWrite.AR.ARLEN := 0.U
  io.axiWrite.AR.ARSIZE := 2.U
  io.axiWrite.AR.ARBURST := 1.U
  io.axiWrite.AR.ARPROT := 0.U
  io.axiWrite.AR.ARCACHE := 0.U
  io.axiWrite.AR.ARQOS := 0.U
  io.axiWrite.R.RREADY := false.B
  io.axiWrite.B.BREADY := io.resp.ready && writeActive

  when(io.req.valid && io.req.bits.write) {
    when(io.axiWrite.AW.AWREADY && !awDone) { awDone := true.B }
    when(io.axiWrite.W.WREADY && !wDone) { wDone := true.B }
  }
  when(io.req.fire && io.req.bits.write) {
    awDone := false.B
    wDone := false.B
    writeActive := true.B
  }
  when(writeActive && io.axiWrite.B.BVALID && io.resp.ready) {
    writeActive := false.B
  }

  io.req.ready := Mux(io.req.bits.write, awDone && wDone, io.axi.AR.ARREADY)
  io.resp.valid := Mux(writeActive, io.axiWrite.B.BVALID, io.axi.R.RVALID)
  io.resp.bits.data := Mux(writeActive, 0.U, io.axi.R.RDATA)
  io.resp.bits.fault := Mux(writeActive,
    io.axiWrite.B.BRESP =/= 0.U, io.axi.R.RRESP =/= 0.U)
  io.axi.R.RREADY := Mux(writeActive, false.B, io.resp.ready)
}

/** 写通道二选一：LSU 与 PTW A/D 写回共用仲裁器的 LSU 写端口。
  *
  * 读通道仍由 LSU 独占；PTW 只做单拍写，因此这里只需要处理 AW/W/B。
  */
class ysyx_26030103_AXIWriteMux extends Module {
  val io = IO(new Bundle {
    val a = Flipped(new ysyx_26030103_AXI5IO(32))
    val b = Flipped(new ysyx_26030103_AXI5IO(32))
    val out = new ysyx_26030103_AXI5IO(32)
  })
  val idle :: aWrite :: bWrite :: aResp :: bResp :: Nil = Enum(5)
  val state = RegInit(idle)
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  // 读通道始终由 LSU(a) 直通。
  io.out.AR.ARID := io.a.AR.ARID
  io.out.AR.ARADDR := io.a.AR.ARADDR
  io.out.AR.ARLEN := io.a.AR.ARLEN
  io.out.AR.ARSIZE := io.a.AR.ARSIZE
  io.out.AR.ARBURST := io.a.AR.ARBURST
  io.out.AR.ARPROT := io.a.AR.ARPROT
  io.out.AR.ARCACHE := io.a.AR.ARCACHE
  io.out.AR.ARQOS := io.a.AR.ARQOS
  io.out.AR.ARVALID := io.a.AR.ARVALID
  io.a.AR.ARREADY := io.out.AR.ARREADY
  io.a.R <> io.out.R
  io.b.AR.ARREADY := false.B
  io.b.R.RVALID := false.B
  io.b.R.RID := 0.U
  io.b.R.RDATA := 0.U
  io.b.R.RRESP := 0.U
  io.b.R.RLAST := false.B


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
    io.a.AW.AWREADY := false.B
  // 读通道由上面的 io.out.AR <> io.a.AR 驱动，不能再把 ARREADY 覆盖为 0。
  io.a.W.WREADY := false.B
  io.a.B.BVALID := false.B
  io.a.B.BID := 0.U
  io.a.B.BRESP := 0.U
  io.b.AW.AWREADY := false.B
  io.b.W.WREADY := false.B
  io.b.B.BVALID := false.B
  io.b.B.BID := 0.U
  io.b.B.BRESP := 0.U

  when(state === idle) {
    val aReq = io.a.AW.AWVALID || io.a.W.WVALID
    val bReq = io.b.AW.AWVALID || io.b.W.WVALID
    when(aReq) {
      state := aWrite
      awDone := false.B
      wDone := false.B
    }.elsewhen(bReq) {
      state := bWrite
      awDone := false.B
      wDone := false.B
    }
  }

  def drive(sel: ysyx_26030103_AXI5IO, isA: Boolean): Unit = {
    io.out.AW.AWVALID := sel.AW.AWVALID && !awDone
    io.out.AW.AWADDR := sel.AW.AWADDR
    io.out.AW.AWLEN := sel.AW.AWLEN
    io.out.AW.AWSIZE := sel.AW.AWSIZE
    io.out.AW.AWBURST := sel.AW.AWBURST
    io.out.AW.AWPROT := sel.AW.AWPROT
    io.out.AW.AWCACHE := sel.AW.AWCACHE
    io.out.AW.AWQOS := sel.AW.AWQOS
    sel.AW.AWREADY := io.out.AW.AWREADY && !awDone
    io.out.W.WVALID := sel.W.WVALID && !wDone
    io.out.W.WDATA := sel.W.WDATA
    io.out.W.WSTRB := sel.W.WSTRB
    io.out.W.WLAST := sel.W.WLAST
    sel.W.WREADY := io.out.W.WREADY && !wDone
    val awFire = io.out.AW.AWVALID && io.out.AW.AWREADY
    val wFire = io.out.W.WVALID && io.out.W.WREADY
    when(awFire) { awDone := true.B }
    when(wFire) { wDone := true.B }
    when((awDone || awFire) && (wDone || wFire)) {
      state := (if (isA) aResp else bResp)
    }
  }

  when(state === aWrite) { drive(io.a, true) }
  when(state === bWrite) { drive(io.b, false) }
  when(state === aResp) {
    io.a.B.BVALID := io.out.B.BVALID
    io.a.B.BID := io.out.B.BID
    io.a.B.BRESP := io.out.B.BRESP
    io.out.B.BREADY := io.a.B.BREADY
    when(io.out.B.BVALID && io.out.B.BREADY) { state := idle }
  }
  when(state === bResp) {
    io.b.B.BVALID := io.out.B.BVALID
    io.b.B.BID := io.out.B.BID
    io.b.B.BRESP := io.out.B.BRESP
    io.out.B.BREADY := io.b.B.BREADY
    when(io.out.B.BVALID && io.out.B.BREADY) { state := idle }
  }
}
