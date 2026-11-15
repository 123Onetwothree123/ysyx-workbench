package ysyx_26030103
import chisel3._
import chisel3.util._

import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.idu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.wbu._
import _root_.ysyx_26030103.mmu._

private[ysyx_26030103] trait AXIConnect { this: ysyx_26030103 =>
  def connectAXI(): Unit = {
    arbiter.io.memory.AW <> xbar.io.in.AW
    arbiter.io.memory.W <> xbar.io.in.W
    arbiter.io.memory.B <> xbar.io.in.B
    arbiter.io.memory.AR <> xbar.io.in.AR
    arbiter.io.memory.R <> xbar.io.in.R
    val socXLEN = config.EffectiveSocXLEN
    val socMasterBridge = Module(
      new ysyx_26030103_AXI5WidthBridge(
        UpstreamAddressWidth = config.AddressWidth,
        DownstreamAddressWidth = socXLEN,
        UpstreamDataWidth = busDataWidth,
        DownstreamDataWidth = socXLEN,
        IdWidth = 4
      )
    )
    socMasterBridge.io.in <> xbar.io.SoCBus
    val soc = socMasterBridge.io.out

    io.master.awvalid := soc.AW.AWVALID
    io.master.awaddr := soc.AW.AWADDR
    io.master.awid := soc.AW.AWID
    io.master.awlen := soc.AW.AWLEN
    io.master.awsize := soc.AW.AWSIZE
    io.master.awburst := soc.AW.AWBURST
    io.master.awlock := 0.U
    io.master.awcache := soc.AW.AWCACHE
    io.master.awprot := soc.AW.AWPROT
    io.master.awqos := soc.AW.AWQOS
    soc.AW.AWREADY := io.master.awready

    io.master.wvalid := soc.W.WVALID
    io.master.wdata := soc.W.WDATA
    io.master.wstrb := soc.W.WSTRB
    io.master.wlast := soc.W.WLAST
    soc.W.WREADY := io.master.wready

    io.master.bready := soc.B.BREADY
    soc.B.BID := io.master.bid
    soc.B.BVALID := io.master.bvalid
    soc.B.BRESP := io.master.bresp

    io.master.arvalid := soc.AR.ARVALID
    io.master.araddr := soc.AR.ARADDR
    io.master.arid := soc.AR.ARID
    io.master.arlen := soc.AR.ARLEN
    io.master.arsize := soc.AR.ARSIZE
    io.master.arburst := soc.AR.ARBURST
    io.master.arlock := 0.U
    io.master.arcache := soc.AR.ARCACHE
    io.master.arprot := soc.AR.ARPROT
    io.master.arqos := soc.AR.ARQOS
    soc.AR.ARREADY := io.master.arready

    io.master.rready := soc.R.RREADY
    soc.R.RID := io.master.rid
    soc.R.RVALID := io.master.rvalid
    soc.R.RRESP := io.master.rresp
    soc.R.RDATA := io.master.rdata
    soc.R.RLAST := io.master.rlast

    // ChipLink对端的DMA请求从CPU slave入站。该窗口的实体存储位于
    // FPGA侧：请求经本仲裁器和CPU master送回ChipLink后，在对端
    // master_mem/SimAXIMem终止。这是受控的hairpin proxy，不是无限环回。
    val socSlaveBridge = Module(
      new ysyx_26030103_AXI5WidthBridge(
        UpstreamAddressWidth = socXLEN,
        DownstreamAddressWidth = config.AddressWidth,
        UpstreamDataWidth = socXLEN,
        DownstreamDataWidth = busDataWidth,
        IdWidth = 4,
        TrackExclusive = true
      )
    )
    // LOCK 与桥内部的 AW/AR 事务绑定：在外部地址握手拍锁存，直到对应
    // B/最后一个 R 返回。仲裁器只消费这个锁存值，不再直接采样外部引脚。
    socSlaveBridge.io.inAWLock.foreach(_ := io.slave.awlock)
    socSlaveBridge.io.inARLock.foreach(_ := io.slave.arlock)
    socSlaveBridge.io.in.AW.AWVALID := io.slave.awvalid
    socSlaveBridge.io.in.AW.AWADDR := io.slave.awaddr
    socSlaveBridge.io.in.AW.AWID := io.slave.awid
    socSlaveBridge.io.in.AW.AWLEN := io.slave.awlen
    socSlaveBridge.io.in.AW.AWSIZE := io.slave.awsize
    socSlaveBridge.io.in.AW.AWBURST := io.slave.awburst
    socSlaveBridge.io.in.AW.AWPROT := io.slave.awprot
    socSlaveBridge.io.in.AW.AWCACHE := io.slave.awcache
    socSlaveBridge.io.in.AW.AWQOS := io.slave.awqos
    io.slave.awready := socSlaveBridge.io.in.AW.AWREADY

    socSlaveBridge.io.in.W.WVALID := io.slave.wvalid
    socSlaveBridge.io.in.W.WDATA := io.slave.wdata
    socSlaveBridge.io.in.W.WSTRB := io.slave.wstrb
    socSlaveBridge.io.in.W.WLAST := io.slave.wlast
    io.slave.wready := socSlaveBridge.io.in.W.WREADY

    io.slave.bvalid := socSlaveBridge.io.in.B.BVALID
    io.slave.bresp := socSlaveBridge.io.in.B.BRESP
    io.slave.bid := socSlaveBridge.io.in.B.BID
    socSlaveBridge.io.in.B.BREADY := io.slave.bready

    socSlaveBridge.io.in.AR.ARVALID := io.slave.arvalid
    socSlaveBridge.io.in.AR.ARADDR := io.slave.araddr
    socSlaveBridge.io.in.AR.ARID := io.slave.arid
    socSlaveBridge.io.in.AR.ARLEN := io.slave.arlen
    socSlaveBridge.io.in.AR.ARSIZE := io.slave.arsize
    socSlaveBridge.io.in.AR.ARBURST := io.slave.arburst
    socSlaveBridge.io.in.AR.ARPROT := io.slave.arprot
    socSlaveBridge.io.in.AR.ARCACHE := io.slave.arcache
    socSlaveBridge.io.in.AR.ARQOS := io.slave.arqos
    io.slave.arready := socSlaveBridge.io.in.AR.ARREADY

    io.slave.rvalid := socSlaveBridge.io.in.R.RVALID
    io.slave.rresp := socSlaveBridge.io.in.R.RRESP
    io.slave.rdata := socSlaveBridge.io.in.R.RDATA
    io.slave.rlast := socSlaveBridge.io.in.R.RLAST
    io.slave.rid := socSlaveBridge.io.in.R.RID
    socSlaveBridge.io.in.R.RREADY := io.slave.rready

    if (dmaProxyEnable) {
      // 使用桥在外部握手拍锁存的 LOCK，而不是可能早已撤销的实时引脚。
      arbiter.io.dmaAWLock :=
        socSlaveBridge.io.outAWLock.getOrElse(false.B)
      arbiter.io.dmaARLock :=
        socSlaveBridge.io.outARLock.getOrElse(false.B)
      arbiter.io.dma <> socSlaveBridge.io.out
    } else {
      val errorSlave = dmaErrorSlave.get
      errorSlave.io.AW.AWVALID := socSlaveBridge.io.out.AW.AWVALID
      errorSlave.io.AW.AWADDR := socSlaveBridge.io.out.AW.AWADDR
      errorSlave.io.AW.AWID := socSlaveBridge.io.out.AW.AWID
      errorSlave.io.AW.AWLEN := socSlaveBridge.io.out.AW.AWLEN
      errorSlave.io.AW.AWSIZE := socSlaveBridge.io.out.AW.AWSIZE
      errorSlave.io.AW.AWBURST := socSlaveBridge.io.out.AW.AWBURST
      errorSlave.io.AW.AWPROT := socSlaveBridge.io.out.AW.AWPROT
      errorSlave.io.AW.AWCACHE := socSlaveBridge.io.out.AW.AWCACHE
      errorSlave.io.AW.AWQOS := socSlaveBridge.io.out.AW.AWQOS
      socSlaveBridge.io.out.AW.AWREADY := errorSlave.io.AW.AWREADY

      errorSlave.io.W.WVALID := socSlaveBridge.io.out.W.WVALID
      errorSlave.io.W.WDATA := socSlaveBridge.io.out.W.WDATA
      errorSlave.io.W.WSTRB := socSlaveBridge.io.out.W.WSTRB
      errorSlave.io.W.WLAST := socSlaveBridge.io.out.W.WLAST
      socSlaveBridge.io.out.W.WREADY := errorSlave.io.W.WREADY

      errorSlave.io.B.BREADY := socSlaveBridge.io.out.B.BREADY
      socSlaveBridge.io.out.B.BVALID := errorSlave.io.B.BVALID
      socSlaveBridge.io.out.B.BRESP := errorSlave.io.B.BRESP
      socSlaveBridge.io.out.B.BID := errorSlave.io.B.BID

      errorSlave.io.AR.ARVALID := socSlaveBridge.io.out.AR.ARVALID
      errorSlave.io.AR.ARADDR := socSlaveBridge.io.out.AR.ARADDR
      errorSlave.io.AR.ARID := socSlaveBridge.io.out.AR.ARID
      errorSlave.io.AR.ARLEN := socSlaveBridge.io.out.AR.ARLEN
      errorSlave.io.AR.ARSIZE := socSlaveBridge.io.out.AR.ARSIZE
      errorSlave.io.AR.ARBURST := socSlaveBridge.io.out.AR.ARBURST
      errorSlave.io.AR.ARPROT := socSlaveBridge.io.out.AR.ARPROT
      errorSlave.io.AR.ARCACHE := socSlaveBridge.io.out.AR.ARCACHE
      errorSlave.io.AR.ARQOS := socSlaveBridge.io.out.AR.ARQOS
      socSlaveBridge.io.out.AR.ARREADY := errorSlave.io.AR.ARREADY

      errorSlave.io.R.RREADY := socSlaveBridge.io.out.R.RREADY
      socSlaveBridge.io.out.R.RVALID := errorSlave.io.R.RVALID
      socSlaveBridge.io.out.R.RRESP := errorSlave.io.R.RRESP
      socSlaveBridge.io.out.R.RDATA := errorSlave.io.R.RDATA
      socSlaveBridge.io.out.R.RLAST := errorSlave.io.R.RLAST
      socSlaveBridge.io.out.R.RID := errorSlave.io.R.RID

      // 未开启ChipLink时仲裁器的DMA输入不可悬空。
      arbiter.io.dmaAWLock := false.B
      arbiter.io.dmaARLock := false.B
      arbiter.io.dma.AW.AWVALID := false.B
      arbiter.io.dma.AW.AWADDR := 0.U
      arbiter.io.dma.AW.AWID := 0.U
      arbiter.io.dma.AW.AWLEN := 0.U
      arbiter.io.dma.AW.AWSIZE := 0.U
      arbiter.io.dma.AW.AWBURST := 0.U
      arbiter.io.dma.AW.AWPROT := 0.U
      arbiter.io.dma.AW.AWCACHE := 0.U
      arbiter.io.dma.AW.AWQOS := 0.U
      arbiter.io.dma.W.WVALID := false.B
      arbiter.io.dma.W.WDATA := 0.U
      arbiter.io.dma.W.WSTRB := 0.U
      arbiter.io.dma.W.WLAST := false.B
      arbiter.io.dma.B.BREADY := false.B
      arbiter.io.dma.AR.ARVALID := false.B
      arbiter.io.dma.AR.ARADDR := 0.U
      arbiter.io.dma.AR.ARID := 0.U
      arbiter.io.dma.AR.ARLEN := 0.U
      arbiter.io.dma.AR.ARSIZE := 0.U
      arbiter.io.dma.AR.ARBURST := 0.U
      arbiter.io.dma.AR.ARPROT := 0.U
      arbiter.io.dma.AR.ARCACHE := 0.U
      arbiter.io.dma.AR.ARQOS := 0.U
      arbiter.io.dma.R.RREADY := false.B
    }
    xbar.io.CLINT.AW <> clint.io.AW
    xbar.io.CLINT.W <> clint.io.W
    xbar.io.CLINT.B <> clint.io.B
    xbar.io.CLINT.AR <> clint.io.AR
    xbar.io.CLINT.R <> clint.io.R
    // PLIC 的 bit/寄存器下标就是 source ID（source 0 保留）：
    //   source 1  = CLINT timer
    //   source 10 = UART（与 xv6 memlayout.h 的 UART0_IRQ 一致）
    // io.uart_irq 是专供 PLIC 的 UART 中断源：direct-NPC 顶层把 AXIRAM 的
    // UART 中断接到这里，full-SoC 则接 APBUart16550 的 interrupt。不能把
    // full-SoC 的 UART 只接到 io.interrupt（M/S 外部中断直连），否则软件
    // 按 16550/PLIC 语义配置的 source 10 永远看不到中断。
    // 其余 source 保持未连接。
    val uartPlicSource = 10
    val plicSourceBits = (0 until config.MMU.plicSources).map { sourceIndex =>
      if (sourceIndex == 1) clint.io.timer_irq && config.MMU.hasSTimerIrq.B
      else if (sourceIndex == uartPlicSource) io.uart_irq
      else false.B
    }
    plic.sources := Cat(plicSourceBits.reverse)
    xbar.io.PLIC.AW <> plic.io.AW
    xbar.io.PLIC.W <> plic.io.W
    xbar.io.PLIC.B <> plic.io.B
    xbar.io.PLIC.AR <> plic.io.AR
    xbar.io.PLIC.R <> plic.io.R

    // AXI响应协议检查: B响应的错误码不再在这里断言——DECERR/SLVERR会由LSU按
    // store访问故障精确提交(cause=7), 顶层断言会抢先把仿真停掉。
    // 这里保留BID/RID协议检查, 并消费这些顶层AXI响应信号。
    // 读ID：0=ICache，1=LSU，2=DMA，3..15=PTW；4位ID已全部分配。
    // 读响应归属由仲裁器的逐ID在途表检查，这里只保留写响应BID约束。
    val bHandshake = io.master.bvalid && io.master.bready
    assert(
      !bHandshake || io.master.bid === 0.U || io.master.bid === 2.U,
      "AXI write: bid=%d",
      io.master.bid
    )
  }
}
