package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

// ICache/DCache 与 PTW 共用读端口：A 口 ID 0，B 口 ID 3..15，按 RID 路由响应。
class ysyx_26030103_AXIReadMux(
    passWriteA: Boolean = false,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth
) extends Module {
  val io = IO(new Bundle {
    val a = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4))
    val b = Flipped(new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4))
    val out = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
  })

  private val AReadID = ysyx_26030103_AXIReadId.Ifu.U(4.W)
  private val PtwBaseID = ysyx_26030103_AXIReadId.PtwBase.U(4.W)
  private val PtwLastID = ysyx_26030103_AXIReadId.PtwLast.U(4.W)

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

  // 写通道：只有复用 LSU 读端口时才把 A 的写通路直通出去。
  if (passWriteA) {
    io.out.AW <> io.a.AW
    io.out.W <> io.a.W
    io.a.B <> io.out.B
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

  // A 口最多一笔在途；B 口按 ID 槽记录在途事务。不同 ID 的响应可以
  // 任意顺序返回，同 ID 仍由 AXI 保证顺序。
  val aBusy = RegInit(false.B)
  val bBusy = RegInit(VecInit(Seq.fill(16)(false.B)))
  // 初始优先 A；每次授权后把优先权让给另一侧，A/B 交替，避免 PTW
  // 连续请求饿死取指。
  val lastGrantB = RegInit(false.B)

  val aReq = io.a.AR.ARVALID && !aBusy
  val bId = io.b.AR.ARID
  val bIdValid = bId >= PtwBaseID && bId <= PtwLastID
  val bReq = io.b.AR.ARVALID && bIdValid && !bBusy(bId)
  val chooseBNow = bReq && (!aReq || lastGrantB)
  val chooseANow = aReq && !chooseBNow
  // AXI 要求 ARVALID 拉高后到 ARREADY 之前 AR payload 保持不变。下游
  // 反压时不能因为另一侧新请求到达而改选，否则会丢掉已发出的请求。
  val arLocked = RegInit(false.B)
  val arLockB = RegInit(false.B)
  val chooseB = Mux(arLocked, arLockB, chooseBNow)
  val chooseA = Mux(arLocked, !arLockB, chooseANow)
  when(io.out.AR.ARVALID && io.out.AR.ARREADY) {
    arLocked := false.B
  }.elsewhen(io.out.AR.ARVALID && !io.out.AR.ARREADY) {
    arLocked := true.B
    arLockB := chooseBNow
  }.elsewhen(!io.out.AR.ARVALID) {
    arLocked := false.B
  }

  when(chooseA) {
    io.out.AR.ARVALID := io.a.AR.ARVALID
    io.out.AR.ARID := AReadID
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
    io.out.AR.ARID := bId
    io.out.AR.ARADDR := io.b.AR.ARADDR
    io.out.AR.ARLEN := io.b.AR.ARLEN
    io.out.AR.ARSIZE := io.b.AR.ARSIZE
    io.out.AR.ARBURST := io.b.AR.ARBURST
    io.out.AR.ARPROT := io.b.AR.ARPROT
    io.out.AR.ARCACHE := io.b.AR.ARCACHE
    io.out.AR.ARQOS := io.b.AR.ARQOS
    io.b.AR.ARREADY := io.out.AR.ARREADY
  }
  when(io.b.AR.ARVALID) {
    assert(bIdValid, "PTW AXI read uses an ID outside the reserved 3..15 range")
  }

  val arFire = io.out.AR.ARVALID && io.out.AR.ARREADY
  when(arFire) {
    when(chooseB) { bBusy(bId) := true.B }
      .otherwise { aBusy := true.B }
    lastGrantB := chooseA
  }

  val rid = io.out.R.RID
  val aAcceptedNow = arFire && !chooseB
  val bAcceptedNow = arFire && chooseB
  // 零延迟从设备可能在 AR 握手同拍返回 R；把本拍刚接受的 ID 也算作
  // 预期响应，避免丢掉第一拍。
  val aSelected = rid === AReadID && (aBusy || aAcceptedNow)
  val bSelected = rid >= PtwBaseID && rid <= PtwLastID &&
    (bBusy(rid) || (bAcceptedNow && bId === rid))
  when(aSelected) {
    io.a.R.RVALID := io.out.R.RVALID
    io.a.R.RID := AReadID
    io.a.R.RDATA := io.out.R.RDATA
    io.a.R.RRESP := io.out.R.RRESP
    io.a.R.RLAST := io.out.R.RLAST
    io.out.R.RREADY := io.a.R.RREADY
  }.elsewhen(bSelected) {
    io.b.R.RVALID := io.out.R.RVALID
    io.b.R.RID := rid
    io.b.R.RDATA := io.out.R.RDATA
    io.b.R.RRESP := io.out.R.RRESP
    io.b.R.RLAST := io.out.R.RLAST
    io.out.R.RREADY := io.b.R.RREADY
  }
  when(io.out.R.RVALID) {
    assert(aSelected || bSelected, "AXI read mux received an unknown RID")
  }
  when(io.out.R.RVALID && io.out.R.RREADY && io.out.R.RLAST) {
    when(aSelected) { aBusy := false.B }
      .elsewhen(bSelected) { bBusy(rid) := false.B }
  }
}
