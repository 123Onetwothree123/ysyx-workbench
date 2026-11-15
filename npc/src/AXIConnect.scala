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
    arbiter.io.memory <> xbar.io.in
    val socXLEN = config.EffectiveSocXLEN
    val socMasterBridge = Module(
      new ysyx_26030103_AXI5WidthBridge(
        UpstreamAddressWidth = config.AddressWidth,
        DownstreamAddressWidth = socXLEN,
        UpstreamDataWidth = busDataWidth,
        DownstreamDataWidth = socXLEN,
        IDWidth = 4
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
        IDWidth = 4,
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
      errorSlave.io <> socSlaveBridge.io.out

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
    // CLINT 的 io 是 CLINTIO，比 xbar 的 AXI5IO 多出 timer_irq/mtime，
    // 整包 <> 会因为多字段失败，只能按通道连。
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
    val uartPLICSource = 10
    val plicSourceBits = (0 until config.MMU.plicSources).map { sourceIndex =>
      if (sourceIndex == 1) clint.io.timer_irq && config.MMU.hasSTimerIRQ.B
      else if (sourceIndex == uartPLICSource) io.uart_irq
      else false.B
    }
    plic.sources := Cat(plicSourceBits.reverse)
    xbar.io.PLIC <> plic.io

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
