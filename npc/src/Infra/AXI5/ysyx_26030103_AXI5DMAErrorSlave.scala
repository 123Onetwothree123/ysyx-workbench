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
) extends Module {
  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IDWidth))

  private val DECERR = "b11".U(2.W)

  // 写通道：有意分别独立收集AW与W。
  val AWHeld = RegInit(false.B)
  val awID = RegInit(0.U(IDWidth.W))
  val wLastSeen = RegInit(false.B)
  val bValid = RegInit(false.B)

  io.AW.AWREADY := !AWHeld && !bValid
  io.W.WREADY := !wLastSeen && !bValid
  io.B.BID := awID
  io.B.BRESP := DECERR
  io.B.BVALID := bValid
  // 在独立Verilator回归中保持响应叶节点可见；否则CIRCT可以将这些常量
  // 传播到父模块中。
  dontTouch(io.B.BRESP)

  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val wFire = io.W.WVALID && io.W.WREADY
  val wLastFire = wFire && io.W.WLAST
  val HaveAWAfterThisCycle = AWHeld || AWFire
  val haveLastWAfterThisCycle = wLastSeen || wLastFire

  when(AWFire) {
    AWHeld := true.B
    awID := io.AW.AWID
  }
  when(wLastFire) {
    wLastSeen := true.B
  }
  when(!bValid && HaveAWAfterThisCycle && haveLastWAfterThisCycle) {
    bValid := true.B
  }
  when(io.B.BVALID && io.B.BREADY) {
    AWHeld := false.B
    wLastSeen := false.B
    bValid := false.B
  }

  // 读通道：允许一项突发传输在途，在反压下保持响应稳定，并严格返回
  // ARLEN + 1个数据拍。
  val readActive = RegInit(false.B)
  val ReadID = RegInit(0.U(IDWidth.W))
  val ReadLength = RegInit(0.U(8.W))
  val readBeat = RegInit(0.U(8.W))

  io.AR.ARREADY := !readActive
  io.R.RID := ReadID
  io.R.RDATA := 0.U
  io.R.RRESP := DECERR
  // RLAST 属于数据拍的一部分，必须与 RVALID 同步：复位后与突发结束
  // 之后不得脱离 RVALID 断言（与 CLINT 从设备的门控方式保持一致）。
  io.R.RLAST := readActive && readBeat === ReadLength
  io.R.RVALID := readActive
  dontTouch(io.R.RRESP)

  when(io.AR.ARVALID && io.AR.ARREADY) {
    readActive := true.B
    ReadID := io.AR.ARID
    ReadLength := io.AR.ARLEN
    readBeat := 0.U
  }
  // 读拍计数不得越过 ARLEN：越界意味着会多发响应拍。
  assert(!readActive || readBeat <= ReadLength,
         "DMA error slave read beat counter exceeded ARLEN")
  when(io.R.RVALID && io.R.RREADY) {
    when(io.R.RLAST) {
      readActive := false.B
    }.otherwise {
      readBeat := readBeat + 1.U
    }
  }

}
