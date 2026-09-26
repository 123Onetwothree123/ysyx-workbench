package ysyx_26030103.infra

import chisel3._

/**
  * 为CPU的入站AXI端口提供协议完整的终止响应。
  *
  * 当前SoC没有在此端口后提供存储。尤其是，将ChipLink MEM请求转发至CPU主端口，
  * 会把同一个0xc0000000--0xffffffff地址再次路由回ChipLink并形成环路。因此，
  * 拒绝事务比挂起请求方或错误确认从未提交的写事务更合适。
  *
  * AXI写地址与写数据通道相互独立，因此此从设备按任意顺序接收两者，并在返回
  * DECERR前排空直至WLAST的每个W数据拍。读事务返回ARLEN + 1个DECERR响应拍，
  * 并在遵守响应反压的同时保持RID/RLAST。
  */
class ysyx_26030103_AXI5DMAErrorSlave(
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    IdWidth: Int = 4
) extends Module {
  val io = IO(new ysyx_26030103_AXI5Slave(AddressWidth, DataWidth, IdWidth))

  private val DECERR = "b11".U(2.W)

  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool

  // 写通道：有意分别独立收集AW与W。
  val awHeld = RegInit(false.B)
  val awId = RegInit(0.U(IdWidth.W))
  val wLastSeen = RegInit(false.B)
  val bValid = RegInit(false.B)

  io.AW.AWREADY := !awHeld && !bValid
  io.W.WREADY := !wLastSeen && !bValid
  io.B.BID := awId
  io.B.BRESP := DECERR
  io.B.BVALID := bValid
  // 在独立Verilator回归中保持响应叶节点可见；否则CIRCT可以将这些常量
  // 传播到父模块中。
  dontTouch(io.B.BRESP)

  val awFire = io.AW.AWVALID && io.AW.AWREADY
  val wFire = io.W.WVALID && io.W.WREADY
  val wLastFire = wFire && io.W.WLAST
  val haveAWAfterThisCycle = awHeld || awFire
  val haveLastWAfterThisCycle = wLastSeen || wLastFire

  when(awFire) {
    awHeld := true.B
    awId := io.AW.AWID
  }
  when(wLastFire) {
    wLastSeen := true.B
  }
  when(!bValid && haveAWAfterThisCycle && haveLastWAfterThisCycle) {
    bValid := true.B
  }
  when(io.B.BVALID && io.B.BREADY) {
    awHeld := false.B
    wLastSeen := false.B
    bValid := false.B
  }

  // 读通道：允许一项突发传输在途，在反压下保持响应稳定，并严格返回
  // ARLEN + 1个数据拍。
  val readActive = RegInit(false.B)
  val readId = RegInit(0.U(IdWidth.W))
  val readLen = RegInit(0.U(8.W))
  val readBeat = RegInit(0.U(8.W))

  io.AR.ARREADY := !readActive
  io.R.RID := readId
  io.R.RDATA := 0.U
  io.R.RRESP := DECERR
  io.R.RLAST := readBeat === readLen
  io.R.RVALID := readActive
  dontTouch(io.R.RRESP)

  when(io.AR.ARVALID && io.AR.ARREADY) {
    readActive := true.B
    readId := io.AR.ARID
    readLen := io.AR.ARLEN
    readBeat := 0.U
  }
  when(io.R.RVALID && io.R.RREADY) {
    when(io.R.RLAST) {
      readActive := false.B
    }.otherwise {
      readBeat := readBeat + 1.U
    }
  }

}
