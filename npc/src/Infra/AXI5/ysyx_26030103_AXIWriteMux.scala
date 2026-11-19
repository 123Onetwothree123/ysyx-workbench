package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

class ysyx_26030103_AXIWriteMux(
    val PassRead: Boolean = true,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth
) extends Module {
  val io = IO(new Bundle {
    val a = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4))
    val b = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4))
    val out = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
  })
  val idle :: aWrite :: bWrite :: aResponse :: bResponse :: Nil = Enum(5)
  val state = RegInit(idle)
  val AWDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  if (PassRead) {
    // 读通道由 LSU(a) 直通。逐字段连接，不能再用 ARREADY := false 覆盖。
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
  } else {
    // 只使用写通道；读通道由顶层直接接仲裁器。
    io.out.AR.ARVALID := false.B
    io.out.AR.ARID := 0.U
    io.out.AR.ARADDR := 0.U
    io.out.AR.ARLEN := 0.U
    io.out.AR.ARSIZE := 2.U
    io.out.AR.ARBURST := 0.U
    io.out.AR.ARPROT := 0.U
    io.out.AR.ARCACHE := 0.U
    io.out.AR.ARQOS := 0.U
    io.a.AR.ARREADY := false.B
    io.a.R.RVALID := false.B
    io.a.R.RID := 0.U
    io.a.R.RDATA := 0.U
    io.a.R.RRESP := 0.U
    io.a.R.RLAST := false.B
    io.out.R.RREADY := false.B
  }
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
    val aRequest = io.a.AW.AWVALID || io.a.W.WVALID
    val bRequest = io.b.AW.AWVALID || io.b.W.WVALID
    when(aRequest) {
      state := aWrite
      AWDone := false.B
      wDone := false.B
    }.elsewhen(bRequest) {
      state := bWrite
      AWDone := false.B
      wDone := false.B
    }
  }

  def drive(selected: ysyx_26030103_AXI5IO, isA: Boolean): Unit = {
    io.out.AW.AWVALID := selected.AW.AWVALID && !AWDone
    io.out.AW.AWID := selected.AW.AWID
    io.out.AW.AWADDR := selected.AW.AWADDR
    io.out.AW.AWLEN := selected.AW.AWLEN
    io.out.AW.AWSIZE := selected.AW.AWSIZE
    io.out.AW.AWBURST := selected.AW.AWBURST
    io.out.AW.AWPROT := selected.AW.AWPROT
    io.out.AW.AWCACHE := selected.AW.AWCACHE
    io.out.AW.AWQOS := selected.AW.AWQOS
    selected.AW.AWREADY := io.out.AW.AWREADY && !AWDone
    io.out.W.WVALID := selected.W.WVALID && !wDone
    io.out.W.WDATA := selected.W.WDATA
    io.out.W.WSTRB := selected.W.WSTRB
    io.out.W.WLAST := selected.W.WLAST
    selected.W.WREADY := io.out.W.WREADY && !wDone
    val AWFire = io.out.AW.AWVALID && io.out.AW.AWREADY
    val wFire = io.out.W.WVALID && io.out.W.WREADY
    // 一笔写可能有多拍 W（AWLEN>0）：AWSIZE=2 的 8 字节 store 在 32 位总线
    // 上就是两拍。W 通道必须以 WLAST 为结束判据，不能在第一拍就置 wDone，
    // 否则后续拍永远不被转发，下游等 WLAST、上游等 B，总线挂死。
    val wFinishes = wFire && io.out.W.WLAST
    when(AWFire) { AWDone := true.B }
    when(wFinishes) { wDone := true.B }
    when((AWDone || AWFire) && (wDone || wFinishes)) {
      state := (if (isA) aResponse else bResponse)
    }
  }

  when(state === aWrite) { drive(io.a, true) }
  when(state === bWrite) { drive(io.b, false) }
  when(state === aResponse) {
    io.a.B.BVALID := io.out.B.BVALID
    io.a.B.BID := io.out.B.BID
    io.a.B.BRESP := io.out.B.BRESP
    io.out.B.BREADY := io.a.B.BREADY
    when(io.out.B.BVALID && io.out.B.BREADY) { state := idle }
  }
  when(state === bResponse) {
    io.b.B.BVALID := io.out.B.BVALID
    io.b.B.BID := io.out.B.BID
    io.b.B.BRESP := io.out.B.BRESP
    io.out.B.BREADY := io.b.B.BREADY
    when(io.out.B.BVALID && io.out.B.BREADY) { state := idle }
  }
}
