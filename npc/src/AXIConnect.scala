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
    val SOCXLEN = config.EffectiveSocXLEN
    val SOCMasterBridge = Module(
      new ysyx_26030103_AXI5WidthBridge(
        UpstreamAddressWidth = config.AddressWidth,
        DownstreamAddressWidth = SOCXLEN,
        UpstreamDataWidth = busDataWidth,
        DownstreamDataWidth = SOCXLEN,
        IDWidth = 4,
        // DMA 读独占第二个读槽，CPU 取指停住时仍能下发。
        BypassReadID = Some(ysyx_26030103_AXIReadID.DMA)
      )
    )
    SOCMasterBridge.io.in <> xbar.io.SoCBus
    val SOC = SOCMasterBridge.io.out

    io.master.awvalid := SOC.AW.AWVALID
    io.master.awaddr := SOC.AW.AWADDR
    io.master.awid := SOC.AW.AWID
    io.master.awlen := SOC.AW.AWLEN
    io.master.awsize := SOC.AW.AWSIZE
    io.master.awburst := SOC.AW.AWBURST
    io.master.awlock := 0.U
    io.master.awcache := SOC.AW.AWCACHE
    io.master.awprot := SOC.AW.AWPROT
    io.master.awqos := SOC.AW.AWQOS
    SOC.AW.AWREADY := io.master.awready

    io.master.wvalid := SOC.W.WVALID
    io.master.wdata := SOC.W.WDATA
    io.master.wstrb := SOC.W.WSTRB
    io.master.wlast := SOC.W.WLAST
    SOC.W.WREADY := io.master.wready

    io.master.bready := SOC.B.BREADY
    SOC.B.BID := io.master.bid
    SOC.B.BVALID := io.master.bvalid
    SOC.B.BRESP := io.master.bresp

    io.master.arvalid := SOC.AR.ARVALID
    io.master.araddr := SOC.AR.ARADDR
    io.master.arid := SOC.AR.ARID
    io.master.arlen := SOC.AR.ARLEN
    io.master.arsize := SOC.AR.ARSIZE
    io.master.arburst := SOC.AR.ARBURST
    io.master.arlock := 0.U
    io.master.arcache := SOC.AR.ARCACHE
    io.master.arprot := SOC.AR.ARPROT
    io.master.arqos := SOC.AR.ARQOS
    SOC.AR.ARREADY := io.master.arready

    io.master.rready := SOC.R.RREADY
    SOC.R.RID := io.master.rid
    SOC.R.RVALID := io.master.rvalid
    SOC.R.RRESP := io.master.rresp
    SOC.R.RDATA := io.master.rdata
    SOC.R.RLAST := io.master.rlast

    // ChipLink对端的DMA请求从CPU slave入站。该窗口的实体存储位于
    // FPGA侧：请求经本仲裁器和CPU master送回ChipLink后，在对端
    // master_mem/SimAXIMem终止。这是受控的hairpin proxy，不是无限环回。
    val SOCSlaveBridge = Module(
      new ysyx_26030103_AXI5WidthBridge(
        UpstreamAddressWidth = SOCXLEN,
        DownstreamAddressWidth = config.AddressWidth,
        UpstreamDataWidth = SOCXLEN,
        DownstreamDataWidth = busDataWidth,
        IDWidth = 4,
        TrackExclusive = true
      )
    )
    // LOCK 与桥内部的 AW/AR 事务绑定：在外部地址握手拍锁存，直到对应
    // B/最后一个 R 返回。仲裁器只消费这个锁存值，不再直接采样外部引脚。
    SOCSlaveBridge.io.InAWLock.foreach(_ := io.slave.awlock)
    SOCSlaveBridge.io.InARLock.foreach(_ := io.slave.arlock)
    SOCSlaveBridge.io.in.AW.AWVALID := io.slave.awvalid
    SOCSlaveBridge.io.in.AW.AWADDR := io.slave.awaddr
    SOCSlaveBridge.io.in.AW.AWID := io.slave.awid
    SOCSlaveBridge.io.in.AW.AWLEN := io.slave.awlen
    SOCSlaveBridge.io.in.AW.AWSIZE := io.slave.awsize
    SOCSlaveBridge.io.in.AW.AWBURST := io.slave.awburst
    SOCSlaveBridge.io.in.AW.AWPROT := io.slave.awprot
    SOCSlaveBridge.io.in.AW.AWCACHE := io.slave.awcache
    SOCSlaveBridge.io.in.AW.AWQOS := io.slave.awqos
    io.slave.awready := SOCSlaveBridge.io.in.AW.AWREADY

    SOCSlaveBridge.io.in.W.WVALID := io.slave.wvalid
    SOCSlaveBridge.io.in.W.WDATA := io.slave.wdata
    SOCSlaveBridge.io.in.W.WSTRB := io.slave.wstrb
    SOCSlaveBridge.io.in.W.WLAST := io.slave.wlast
    io.slave.wready := SOCSlaveBridge.io.in.W.WREADY

    io.slave.bvalid := SOCSlaveBridge.io.in.B.BVALID
    io.slave.bresp := SOCSlaveBridge.io.in.B.BRESP
    io.slave.bid := SOCSlaveBridge.io.in.B.BID
    SOCSlaveBridge.io.in.B.BREADY := io.slave.bready

    SOCSlaveBridge.io.in.AR.ARVALID := io.slave.arvalid
    SOCSlaveBridge.io.in.AR.ARADDR := io.slave.araddr
    SOCSlaveBridge.io.in.AR.ARID := io.slave.arid
    SOCSlaveBridge.io.in.AR.ARLEN := io.slave.arlen
    SOCSlaveBridge.io.in.AR.ARSIZE := io.slave.arsize
    SOCSlaveBridge.io.in.AR.ARBURST := io.slave.arburst
    SOCSlaveBridge.io.in.AR.ARPROT := io.slave.arprot
    SOCSlaveBridge.io.in.AR.ARCACHE := io.slave.arcache
    SOCSlaveBridge.io.in.AR.ARQOS := io.slave.arqos
    io.slave.arready := SOCSlaveBridge.io.in.AR.ARREADY

    io.slave.rvalid := SOCSlaveBridge.io.in.R.RVALID
    io.slave.rresp := SOCSlaveBridge.io.in.R.RRESP
    io.slave.rdata := SOCSlaveBridge.io.in.R.RDATA
    io.slave.rlast := SOCSlaveBridge.io.in.R.RLAST
    io.slave.rid := SOCSlaveBridge.io.in.R.RID
    SOCSlaveBridge.io.in.R.RREADY := io.slave.rready

    if (DMAProxyEnable) {
      // 使用桥在外部握手拍锁存的 LOCK，而不是可能早已撤销的实时引脚。
      arbiter.io.DMAAWLock :=
        SOCSlaveBridge.io.OutAWLock.getOrElse(false.B)
      arbiter.io.DMAARLock :=
        SOCSlaveBridge.io.OutARLock.getOrElse(false.B)
      arbiter.io.DMA <> SOCSlaveBridge.io.out
    } else {
      val errorSlave = DMAErrorSlave.get
      errorSlave.io <> SOCSlaveBridge.io.out

      // 未开启ChipLink时仲裁器的DMA输入不可悬空。
      arbiter.io.DMAAWLock := false.B
      arbiter.io.DMAARLock := false.B
      ysyx_26030103_AXI5Idle.MasterOut(arbiter.io.DMA, BeatSize = 0)
    }
    // CLINT 的 io 是 CLINTIO，比 xbar 的 AXI5IO 多出 TimerIRQ/mtime，
    // 整包 <> 会因为多字段失败，只能按通道连。
    xbar.io.CLINT.AW <> CLINT.io.AW
    xbar.io.CLINT.W <> CLINT.io.W
    xbar.io.CLINT.B <> CLINT.io.B
    xbar.io.CLINT.AR <> CLINT.io.AR
    xbar.io.CLINT.R <> CLINT.io.R
    // PLIC 的 bit/寄存器下标就是 source ID（source 0 保留）：
    //   source 1  = CLINT timer
    //   source 10 = UART（与 xv6 memlayout.h 的 UART0_IRQ 一致）
    // io.UARTIRQ 是专供 PLIC 的 UART 中断源：direct-NPC 顶层把 AXIRAM 的
    // UART 中断接到这里，full-SoC 则接 APBUart16550 的 interrupt。不能把
    // full-SoC 的 UART 只接到 io.interrupt（M/S 外部中断直连），否则软件
    // 按 16550/PLIC 语义配置的 source 10 永远看不到中断。
    // 其余 source 保持未连接。
    val PLICSourceBits = (0 until config.MMU.PLICSources).map { sourceIndex =>
      if (sourceIndex == ysyx_26030103_MMUProfile.ClintTimerPLICSource)
        CLINT.io.TimerIRQ && config.MMU.HasSTimerIRQ.B
      else if (sourceIndex == ysyx_26030103_MMUProfile.UartPLICSource) io.UARTIRQ
      else false.B
    }
    PLIC.sources := Cat(PLICSourceBits.reverse)
    xbar.io.PLIC <> PLIC.io

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
