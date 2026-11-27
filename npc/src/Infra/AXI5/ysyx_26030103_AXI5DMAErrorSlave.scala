package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._

// CPU 入站 AXI 端口的终止从设备：PMA 未启用 ChipLink MEM 窗口时由顶层
// 例化，拒绝所有入站事务，避免未连接端口挂起；启用时顶层改接对端存储。
// 写通道独立接收 AW/W，排空到 WLAST 才回 DECERR；读返回 ARLEN+1 个
// DECERR 拍，并保持 RID/RLAST。
class ysyx_26030103_AXI5DMAErrorSlave(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    IDWidth: Int = ysyx_26030103_KconfigDefaults.AXIIDWidth
) extends Module with ysyx_26030103_AXI5ChannelFire {
  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IDWidth))

  private val DECERR = ysyx_26030103_AXI5Resp.DECERR

  // 写通道：有意分别独立收集AW与W。
  val awRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5AWChannelRegs(AddressWidth, IDWidth))
  val wRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5WChannelRegs(DataWidth))
  val bRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5BChannelRegs)
  val arRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5ARChannelRegs(AddressWidth, IDWidth))
  val rRegs = ysyx_26030103_AXI5ChannelRegs(
    new ysyx_26030103_AXI5RChannelRegs(DataWidth, IDWidth))

  io.AW.AWREADY := !awRegs.valid && !bRegs.valid
  io.W.WREADY := !wRegs.last && !bRegs.valid
  io.B.BID := awRegs.id
  io.B.BRESP := DECERR
  io.B.BVALID := bRegs.valid
  // 在独立Verilator回归中保持响应叶节点可见；否则CIRCT可以将这些常量
  // 传播到父模块中。
  dontTouch(io.B.BRESP)

  val AWFire = AwFire(io.AW.AWVALID, io.AW.AWREADY)
  val wFire = WFire(io.W.WVALID, io.W.WREADY)
  val bFire = BFire(io.B.BVALID, io.B.BREADY)
  val wLastFire = wFire && io.W.WLAST
  val HaveAWAfterThisCycle = awRegs.valid || AWFire
  val haveLastWAfterThisCycle = wRegs.last || wLastFire

  when(AWFire) {
    awRegs.valid := true.B
    awRegs.id := io.AW.AWID
  }
  when(wLastFire) {
    wRegs.last := true.B
  }
  when(!bRegs.valid && HaveAWAfterThisCycle && haveLastWAfterThisCycle) {
    bRegs.valid := true.B
  }
  when(bFire) {
    awRegs.valid := false.B
    wRegs.last := false.B
    bRegs.valid := false.B
  }

  // 读通道：允许一项突发传输在途，在反压下保持响应稳定，并严格返回
  // ARLEN + 1个数据拍。
  val readBeat = RegInit(0.U(8.W))

  io.AR.ARREADY := !rRegs.valid
  io.R.RID := arRegs.id
  io.R.RDATA := 0.U
  io.R.RRESP := DECERR
  // RLAST 属于数据拍的一部分，必须与 RVALID 同步：复位后与突发结束
  // 之后不得脱离 RVALID 断言（与 CLINT 从设备的门控方式保持一致）。
  io.R.RLAST := rRegs.valid &&
    ysyx_26030103_AXI5Beat.IsLast(readBeat, arRegs.length)
  io.R.RVALID := rRegs.valid
  dontTouch(io.R.RRESP)

  when(ArFire(io.AR.ARVALID, io.AR.ARREADY)) {
    rRegs.valid := true.B
    arRegs.id := io.AR.ARID
    arRegs.length := io.AR.ARLEN
    readBeat := 0.U
  }
  // 读拍计数不得越过 ARLEN：越界意味着会多发响应拍。
  assert(!rRegs.valid || readBeat <= arRegs.length,
         "DMA error slave read beat counter exceeded ARLEN")
  when(RFire(io.R.RVALID, io.R.RREADY)) {
    when(io.R.RLAST) {
      rRegs.valid := false.B
    }.otherwise {
      readBeat := ysyx_26030103_AXI5Beat.Next(readBeat)
    }
  }

}
