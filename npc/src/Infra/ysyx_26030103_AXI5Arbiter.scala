package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** IFU、LSU与入站DMA端口的AXI仲裁器。
  *
  * 读写相互独立：LSU写事务等待B响应时，取指或load仍可处于在途状态。
  * 三个读主设备使用不同的下游ID，使一项IFU读、一项LSU读和一项
  * DMA读可以同时在途，并明确响应归属。DMA的上游ID在请求被接受时保存，
  * 统一以下游ID 2发出，返回CPU slave端口时恢复原ID。
  */
class ysyx_26030103_AXI5Arbiter extends Module {
  val io = IO(new Bundle {
    val ifu = Flipped(new ysyx_26030103_AXI5IO(32))
    val lsu = Flipped(new ysyx_26030103_AXI5IO(32))
    val dma = Flipped(new ysyx_26030103_AXI5IO(32))
    // CPU slave口带有AXI exclusive属性，而内部精简AXI bundle没有LOCK字段。
    // 显式旁带进来，任何exclusive DMA请求都必须在本地以DECERR终止，
    // 绝不能静默降级成普通下游访问。
    val dmaARLock = Input(Bool())
    val dmaAWLock = Input(Bool())
    val memory = new ysyx_26030103_AXI5IO(32)
    // 匹配的DMA写B响应首次出现，表示下游写已终止。顶层用这个单拍事件
    // 使写直达DCache失效；后续B反压不重复阻塞CPU load。错误响应也必须
    // 失效，因为SLVERR/DECERR并不保证从设备没有产生部分副作用。
    val DMAWriteInvalidate = Output(Bool())
  })

  private val IFUReadID = 0.U(4.W)
  private val LSUReadID = 1.U(4.W)
  private val DMAReadID = 2.U(4.W)
  private val DMAWriteID = 2.U(4.W)
  private val GrantIFU = 0.U(2.W)
  private val GrantLSU = 1.U(2.W)
  private val GrantDMA = 2.U(2.W)
  private val DECERR = 3.U(2.W)

  private class ReadResponsePayload extends Bundle {
    val Data = UInt(32.W)
    val Resp = UInt(2.W)
    val Last = Bool()
  }

  /** CPU slave只代理ChipLink MEM窗口。使用33位末字节计算同时拒绝越过
    * 4GiB边界、超出32位数据总线拍宽以及不支持的burst类型。
    */
  private def DMARequestPermitted(
      Address: UInt,
      Len: UInt,
      Size: UInt,
      Burst: UInt
  ): Bool = {
    val ExtendedAddress = Cat(0.U(1.W), Address)
    val BeatBytes = 1.U(33.W) << Size
    val LastBeatOffset = Mux(Burst === 1.U, Len * BeatBytes, 0.U)
    val LastByte = ExtendedAddress + LastBeatOffset + BeatBytes - 1.U
    val Supported = Size <= 2.U && (Burst === 0.U || Burst === 1.U)
    Supported && ExtendedAddress >= "hc0000000".U(33.W) &&
    LastByte < "h100000000".U(33.W)
  }

  io.ifu.AW.AWREADY := false.B
  io.ifu.W.WREADY := false.B
  io.ifu.B.BID := 0.U
  io.ifu.B.BRESP := 0.U
  io.ifu.B.BVALID := false.B
  io.ifu.AR.ARREADY := false.B
  io.ifu.R.RID := 0.U
  io.ifu.R.RDATA := 0.U
  io.ifu.R.RRESP := 0.U
  io.ifu.R.RLAST := false.B
  io.ifu.R.RVALID := false.B

  io.lsu.AW.AWREADY := false.B
  io.lsu.W.WREADY := false.B
  io.lsu.B.BID := 0.U
  io.lsu.B.BRESP := 0.U
  io.lsu.B.BVALID := false.B
  io.lsu.AR.ARREADY := false.B
  io.lsu.R.RID := 0.U
  io.lsu.R.RDATA := 0.U
  io.lsu.R.RRESP := 0.U
  io.lsu.R.RLAST := false.B
  io.lsu.R.RVALID := false.B

  io.dma.AW.AWREADY := false.B
  io.dma.W.WREADY := false.B
  io.dma.B.BID := 0.U
  io.dma.B.BRESP := 0.U
  io.dma.B.BVALID := false.B
  io.dma.AR.ARREADY := false.B
  io.dma.R.RID := 0.U
  io.dma.R.RDATA := 0.U
  io.dma.R.RRESP := 0.U
  io.dma.R.RLAST := false.B
  io.dma.R.RVALID := false.B
  io.DMAWriteInvalidate := false.B

  io.memory.AW.AWVALID := false.B
  io.memory.AW.AWID := 0.U
  io.memory.AW.AWADDR := 0.U
  io.memory.AW.AWLEN := 0.U
  io.memory.AW.AWSIZE := 2.U
  io.memory.AW.AWBURST := 0.U
  io.memory.AW.AWPROT := 0.U
  io.memory.W.WVALID := false.B
  io.memory.W.WDATA := 0.U
  io.memory.W.WSTRB := 0.U
  io.memory.W.WLAST := false.B
  io.memory.B.BREADY := false.B
  io.memory.AR.ARVALID := false.B
  io.memory.AR.ARID := 0.U
  io.memory.AR.ARADDR := 0.U
  io.memory.AR.ARLEN := 0.U
  io.memory.AR.ARSIZE := 2.U
  io.memory.AR.ARBURST := 0.U
  io.memory.AR.ARPROT := 0.U

  // 读地址仲裁和基于ID的响应路由。
  val ifuReadOutstanding = RegInit(false.B)
  val lsuReadOutstanding = RegInit(false.B)
  val dmaReadOutstanding = RegInit(false.B)
  val ifuReadIngressDone = RegInit(false.B)
  val lsuReadIngressDone = RegInit(false.B)
  val dmaReadIngressDone = RegInit(false.B)
  val dmaReadOriginalID = RegInit(0.U(4.W))
  val dmaReadLocalError = RegInit(false.B)
  val dmaReadErrorLen = RegInit(0.U(8.W))
  val dmaReadErrorBeat = RegInit(0.U(8.W))
  // 初始值设为DMA，使复位后的首次三方竞争从IFU开始。
  val lastReadGrant = RegInit(GrantDMA)
  val readGrantHeld = RegInit(false.B)
  val heldReadGrant = RegInit(GrantIFU)
  val ifuReadRequest = io.ifu.AR.ARVALID && !ifuReadOutstanding
  val lsuReadRequest = io.lsu.AR.ARVALID && !lsuReadOutstanding
  val dmaReadRequest = io.dma.AR.ARVALID && !dmaReadOutstanding
  // 三方round-robin：从上次授权的下一方开始找有效请求。
  val chooseNewIFU = WireDefault(false.B)
  val chooseNewLSU = WireDefault(false.B)
  val chooseNewDMA = WireDefault(false.B)
  when(lastReadGrant === GrantIFU) {
    when(lsuReadRequest) { chooseNewLSU := true.B }
      .elsewhen(dmaReadRequest) { chooseNewDMA := true.B }
      .elsewhen(ifuReadRequest) { chooseNewIFU := true.B }
  }.elsewhen(lastReadGrant === GrantLSU) {
    when(dmaReadRequest) { chooseNewDMA := true.B }
      .elsewhen(ifuReadRequest) { chooseNewIFU := true.B }
      .elsewhen(lsuReadRequest) { chooseNewLSU := true.B }
  }.otherwise {
    when(ifuReadRequest) { chooseNewIFU := true.B }
      .elsewhen(lsuReadRequest) { chooseNewLSU := true.B }
      .elsewhen(dmaReadRequest) { chooseNewDMA := true.B }
  }
  // ARVALID拉高但READY未拉高后，AXI要求AR的每个有效载荷字段保持稳定。
  // 因此，在受阻请求完成握手前，新到达的竞争主设备不能改变授权结果。
  val chooseLSU = Mux(readGrantHeld, heldReadGrant === GrantLSU, chooseNewLSU)
  val chooseIFU = Mux(readGrantHeld, heldReadGrant === GrantIFU, chooseNewIFU)
  val chooseDMA = Mux(readGrantHeld, heldReadGrant === GrantDMA, chooseNewDMA)
  val dmaARPermitted = !io.dmaARLock && DMARequestPermitted(
    io.dma.AR.ARADDR,
    io.dma.AR.ARLEN,
    io.dma.AR.ARSIZE,
    io.dma.AR.ARBURST
  )

  when(chooseLSU) {
    io.memory.AR.ARVALID := io.lsu.AR.ARVALID
    io.memory.AR.ARID := LSUReadID
    io.memory.AR.ARADDR := io.lsu.AR.ARADDR
    io.memory.AR.ARLEN := io.lsu.AR.ARLEN
    io.memory.AR.ARSIZE := io.lsu.AR.ARSIZE
    io.memory.AR.ARBURST := io.lsu.AR.ARBURST
    io.memory.AR.ARPROT := io.lsu.AR.ARPROT
    io.lsu.AR.ARREADY := io.memory.AR.ARREADY
  }.elsewhen(chooseDMA) {
    when(dmaARPermitted) {
      io.memory.AR.ARVALID := io.dma.AR.ARVALID
      io.memory.AR.ARID := DMAReadID
      io.memory.AR.ARADDR := io.dma.AR.ARADDR
      io.memory.AR.ARLEN := io.dma.AR.ARLEN
      io.memory.AR.ARSIZE := io.dma.AR.ARSIZE
      io.memory.AR.ARBURST := io.dma.AR.ARBURST
      io.memory.AR.ARPROT := io.dma.AR.ARPROT
      io.dma.AR.ARREADY := io.memory.AR.ARREADY
    }.otherwise {
      // 窗口外请求在本地接受并生成等长DECERR burst，绝不进入CPU Xbar。
      io.dma.AR.ARREADY := true.B
    }
  }.elsewhen(chooseIFU) {
    io.memory.AR.ARVALID := io.ifu.AR.ARVALID
    io.memory.AR.ARID := IFUReadID
    io.memory.AR.ARADDR := io.ifu.AR.ARADDR
    io.memory.AR.ARLEN := io.ifu.AR.ARLEN
    io.memory.AR.ARSIZE := io.ifu.AR.ARSIZE
    io.memory.AR.ARBURST := io.ifu.AR.ARBURST
    io.memory.AR.ARPROT := io.ifu.AR.ARPROT
    io.ifu.AR.ARREADY := io.memory.AR.ARREADY
  }

  val readAddressFire = io.memory.AR.ARVALID && io.memory.AR.ARREADY
  val dmaLocalARFire = chooseDMA && io.dma.AR.ARVALID &&
    io.dma.AR.ARREADY && !dmaARPermitted
  val readAddressAccepted = readAddressFire || dmaLocalARFire
  when(!readGrantHeld && io.memory.AR.ARVALID && !io.memory.AR.ARREADY) {
    readGrantHeld := true.B
    heldReadGrant := Mux(chooseLSU, GrantLSU, Mux(chooseDMA, GrantDMA, GrantIFU))
  }.elsewhen(readGrantHeld && readAddressAccepted) {
    readGrantHeld := false.B
  }
  when(readAddressFire && chooseLSU) {
    lsuReadOutstanding := true.B
    lsuReadIngressDone := false.B
    lastReadGrant := GrantLSU
  }
  when(readAddressFire && chooseIFU) {
    ifuReadOutstanding := true.B
    ifuReadIngressDone := false.B
    lastReadGrant := GrantIFU
  }
  when((readAddressFire && chooseDMA) || dmaLocalARFire) {
    dmaReadOutstanding := true.B
    dmaReadOriginalID := io.dma.AR.ARID
    dmaReadLocalError := dmaLocalARFire
    dmaReadIngressDone := false.B
    when(dmaLocalARFire) {
      dmaReadErrorLen := io.dma.AR.ARLEN
      dmaReadErrorBeat := 0.U
    }
    lastReadGrant := GrantDMA
  }

  // 每个owner使用独立响应队列。DMA队列能容纳AXI4允许的一整笔最大
  // 256-beat burst；因此即使CPU slave端永久RREADY=0，该笔DMA响应也
  // 不会占住共享memory.R并阻止IFU/LSU响应。CPU队列覆盖当前4-beat
  // cache line，并消除不同CPU owner之间的单槽头阻塞。
  private val ifuRQueue = Module(
    new Queue(new ReadResponsePayload, 4, pipe = true, flow = false)
  )
  private val lsuRQueue = Module(
    new Queue(new ReadResponsePayload, 4, pipe = true, flow = false)
  )
  private val dmaRQueue = Module(
    new Queue(new ReadResponsePayload, 256, pipe = true, flow = false)
  )
  for (queue <- Seq(ifuRQueue, lsuRQueue, dmaRQueue)) {
    queue.io.enq.valid := false.B
    queue.io.enq.bits.Data := io.memory.R.RDATA
    queue.io.enq.bits.Resp := io.memory.R.RRESP
    queue.io.enq.bits.Last := io.memory.R.RLAST
  }

  // 有些零延迟从机会在AR握手同拍给出第一拍R。把本拍新接受的owner也
  // 纳入expected；Queue是非flow-through，响应真正出队时原DMA ID已经
  // 在同一上升沿锁存完成。
  val ifuARAcceptedNow = readAddressFire && chooseIFU
  val lsuARAcceptedNow = readAddressFire && chooseLSU
  val dmaARAcceptedNow = readAddressFire && chooseDMA
  val incomingForIFU = io.memory.R.RID === IFUReadID &&
    ((ifuReadOutstanding && !ifuReadIngressDone) || ifuARAcceptedNow)
  val incomingForLSU = io.memory.R.RID === LSUReadID &&
    ((lsuReadOutstanding && !lsuReadIngressDone) || lsuARAcceptedNow)
  val incomingForDMA = io.memory.R.RID === DMAReadID &&
    ((dmaReadOutstanding && !dmaReadLocalError && !dmaReadIngressDone) ||
      dmaARAcceptedNow)
  val incomingRIDExpected = incomingForIFU || incomingForLSU || incomingForDMA

  ifuRQueue.io.enq.valid := io.memory.R.RVALID && incomingForIFU
  lsuRQueue.io.enq.valid := io.memory.R.RVALID && incomingForLSU
  dmaRQueue.io.enq.valid := io.memory.R.RVALID && incomingForDMA
  io.memory.R.RREADY := MuxCase(
    false.B,
    Seq(
      incomingForIFU -> ifuRQueue.io.enq.ready,
      incomingForLSU -> lsuRQueue.io.enq.ready,
      incomingForDMA -> dmaRQueue.io.enq.ready
    )
  )

  ifuRQueue.io.deq.ready := io.ifu.R.RREADY
  io.ifu.R.RID := 0.U
  io.ifu.R.RDATA := ifuRQueue.io.deq.bits.Data
  io.ifu.R.RRESP := ifuRQueue.io.deq.bits.Resp
  io.ifu.R.RLAST := ifuRQueue.io.deq.bits.Last
  io.ifu.R.RVALID := ifuRQueue.io.deq.valid

  lsuRQueue.io.deq.ready := io.lsu.R.RREADY
  io.lsu.R.RID := 0.U
  io.lsu.R.RDATA := lsuRQueue.io.deq.bits.Data
  io.lsu.R.RRESP := lsuRQueue.io.deq.bits.Resp
  io.lsu.R.RLAST := lsuRQueue.io.deq.bits.Last
  io.lsu.R.RVALID := lsuRQueue.io.deq.valid

  dmaRQueue.io.deq.ready := io.dma.R.RREADY && !dmaReadLocalError
  when(!dmaReadLocalError) {
    io.dma.R.RID := dmaReadOriginalID
    io.dma.R.RDATA := dmaRQueue.io.deq.bits.Data
    io.dma.R.RRESP := dmaRQueue.io.deq.bits.Resp
    io.dma.R.RLAST := dmaRQueue.io.deq.bits.Last
    io.dma.R.RVALID := dmaRQueue.io.deq.valid
  }
  when(dmaReadLocalError) {
    io.dma.R.RID := dmaReadOriginalID
    io.dma.R.RDATA := 0.U
    io.dma.R.RRESP := DECERR
    io.dma.R.RLAST := dmaReadErrorBeat === dmaReadErrorLen
    io.dma.R.RVALID := true.B
  }

  val memoryRFire = io.memory.R.RVALID && io.memory.R.RREADY
  when(memoryRFire && io.memory.R.RLAST && incomingForLSU) {
    lsuReadIngressDone := true.B
  }
  when(memoryRFire && io.memory.R.RLAST && incomingForIFU) {
    ifuReadIngressDone := true.B
  }
  when(memoryRFire && io.memory.R.RLAST && incomingForDMA) {
    dmaReadIngressDone := true.B
  }
  when(lsuRQueue.io.deq.fire && lsuRQueue.io.deq.bits.Last) {
    lsuReadOutstanding := false.B
    lsuReadIngressDone := false.B
  }
  when(ifuRQueue.io.deq.fire && ifuRQueue.io.deq.bits.Last) {
    ifuReadOutstanding := false.B
    ifuReadIngressDone := false.B
  }
  when(dmaRQueue.io.deq.fire && dmaRQueue.io.deq.bits.Last) {
    dmaReadOutstanding := false.B
    dmaReadIngressDone := false.B
  }
  when(dmaReadLocalError && io.dma.R.RREADY) {
    when(dmaReadErrorBeat === dmaReadErrorLen) {
      dmaReadLocalError := false.B
      dmaReadOutstanding := false.B
      dmaReadIngressDone := false.B
    }.otherwise {
      dmaReadErrorBeat := dmaReadErrorBeat + 1.U
    }
  }
  when(io.memory.R.RVALID) {
    assert(
      incomingRIDExpected,
      "AXI arbiter received an unknown read response ID"
    )
  }

  // LSU与DMA共享写通道。由于AXI4的W通道不带ID，一旦选中来源，
  // 必须锁定到该事务的WLAST和B响应完成，不得交错两个主设备的W beat。
  val writeStates = Enum(3)
  val WriteIdle = writeStates(0)
  val WriteRequest = writeStates(1)
  val WriteResponse = writeStates(2)
  val writeState = RegInit(WriteIdle)
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeOwner = RegInit(GrantLSU)
  // 初始让LSU在同时请求时先获得写通道，之后在LSU/DMA之间轮转。
  val lastWriteGrant = RegInit(GrantDMA)
  val dmaWriteOriginalID = RegInit(0.U(4.W))
  val dmaWritePermitted = RegInit(false.B)
  val dmaWriteOutstanding = RegInit(false.B)
  val downstreamWriteID = RegInit(0.U(4.W))
  // DMA专属B弹性槽。下游B一旦进入此槽，写仲裁器立即可服务LSU；
  // CPU slave的BREADY只控制该槽出队，不再反压共享memory.B。
  val dmaBValid = RegInit(false.B)
  val dmaBID = RegInit(0.U(4.W))
  val dmaBResp = RegInit(0.U(2.W))
  io.dma.B.BVALID := dmaBValid
  io.dma.B.BID := dmaBID
  io.dma.B.BRESP := dmaBResp
  val dmaBFire = dmaBValid && io.dma.B.BREADY
  when(dmaBFire) {
    dmaBValid := false.B
    dmaWriteOutstanding := false.B
  }

  switch(writeState) {
    is(WriteIdle) {
      awDone := false.B
      wDone := false.B
      val LSURequest = io.lsu.AW.AWVALID || io.lsu.W.WVALID
      val DMARequest = !dmaWriteOutstanding &&
        (io.dma.AW.AWVALID || io.dma.W.WVALID)
      val ChooseDMA = DMARequest && (!LSURequest || lastWriteGrant === GrantLSU)
      when(LSURequest || DMARequest) {
        writeOwner := Mux(ChooseDMA, GrantDMA, GrantLSU)
        lastWriteGrant := Mux(ChooseDMA, GrantDMA, GrantLSU)
        writeState := WriteRequest
      }
    }
    is(WriteRequest) {
      val OwnerDMA = writeOwner === GrantDMA
      val SelectedAWValid = Mux(OwnerDMA, io.dma.AW.AWVALID, io.lsu.AW.AWVALID)
      val SelectedWValid = Mux(OwnerDMA, io.dma.W.WVALID, io.lsu.W.WVALID)
      val SelectedWLast = Mux(OwnerDMA, io.dma.W.WLAST, io.lsu.W.WLAST)
      val DMAAWPermitted = !io.dmaAWLock && DMARequestPermitted(
        io.dma.AW.AWADDR,
        io.dma.AW.AWLEN,
        io.dma.AW.AWSIZE,
        io.dma.AW.AWBURST
      )

      io.memory.AW.AWVALID := !awDone && SelectedAWValid &&
        (!OwnerDMA || DMAAWPermitted)
      io.memory.AW.AWID := Mux(OwnerDMA, DMAWriteID, io.lsu.AW.AWID)
      io.memory.AW.AWADDR := Mux(OwnerDMA, io.dma.AW.AWADDR, io.lsu.AW.AWADDR)
      io.memory.AW.AWLEN := Mux(OwnerDMA, io.dma.AW.AWLEN, io.lsu.AW.AWLEN)
      io.memory.AW.AWSIZE := Mux(OwnerDMA, io.dma.AW.AWSIZE, io.lsu.AW.AWSIZE)
      io.memory.AW.AWBURST := Mux(OwnerDMA, io.dma.AW.AWBURST, io.lsu.AW.AWBURST)
      io.memory.AW.AWPROT := Mux(OwnerDMA, io.dma.AW.AWPROT, io.lsu.AW.AWPROT)
      io.lsu.AW.AWREADY := io.memory.AW.AWREADY && !awDone && !OwnerDMA
      io.dma.AW.AWREADY := !awDone && OwnerDMA &&
        Mux(DMAAWPermitted, io.memory.AW.AWREADY, true.B)
      val SelectedAWReady = Mux(OwnerDMA, io.dma.AW.AWREADY, io.lsu.AW.AWREADY)
      val awFire = !awDone && SelectedAWValid && SelectedAWReady

      // DMA的W通道在AW被分类前保持反压。这样即使W先到，也不会在得知
      // 地址合法性之前把数据泄漏到下游；合法AW确认后的各拍再直接流过。
      val DMAWReady = awDone && Mux(dmaWritePermitted, io.memory.W.WREADY, true.B)
      io.memory.W.WVALID := SelectedWValid && !wDone &&
        (!OwnerDMA || (awDone && dmaWritePermitted))
      io.memory.W.WDATA := Mux(OwnerDMA, io.dma.W.WDATA, io.lsu.W.WDATA)
      io.memory.W.WSTRB := Mux(OwnerDMA, io.dma.W.WSTRB, io.lsu.W.WSTRB)
      io.memory.W.WLAST := SelectedWLast
      io.lsu.W.WREADY := io.memory.W.WREADY && !wDone && !OwnerDMA
      io.dma.W.WREADY := DMAWReady && !wDone && OwnerDMA
      val SelectedWReady = Mux(OwnerDMA, io.dma.W.WREADY, io.lsu.W.WREADY)
      val wFire = !wDone && SelectedWValid && SelectedWReady
      val wLastFire = wFire && SelectedWLast

      when(awFire) {
        awDone := true.B
        downstreamWriteID := Mux(OwnerDMA, DMAWriteID, io.lsu.AW.AWID)
        when(OwnerDMA) {
          dmaWriteOriginalID := io.dma.AW.AWID
          dmaWritePermitted := DMAAWPermitted
          dmaWriteOutstanding := true.B
        }
      }
      when(wLastFire) { wDone := true.B }
      when((awDone || awFire) && (wDone || wLastFire)) {
        when(OwnerDMA && !dmaWritePermitted) {
          assert(!dmaBValid, "local DMA write error collided with a pending B")
          dmaBValid := true.B
          dmaBID := dmaWriteOriginalID
          dmaBResp := DECERR
          writeState := WriteIdle
        }.otherwise {
          writeState := WriteResponse
        }
      }
    }
    is(WriteResponse) {
      val OwnerDMA = writeOwner === GrantDMA
      val BIDMatches = io.memory.B.BID === downstreamWriteID
      // 错误BID不属于当前事务，不能吞掉并让本仲裁器与下游
      // fabric对在途事务的认识脱节。仿真assert会立即指出违规从设备。
      io.memory.B.BREADY := BIDMatches &&
        Mux(OwnerDMA, !dmaBValid, io.lsu.B.BREADY)
      when(OwnerDMA) {
        // DMA响应在MatchingBFire时写入独立B槽，下一拍即可释放共享写FSM。
      }.otherwise {
        io.lsu.B.BID := io.memory.B.BID
        io.lsu.B.BRESP := io.memory.B.BRESP
        io.lsu.B.BVALID := io.memory.B.BVALID && BIDMatches
      }
      val MatchingBFire = io.memory.B.BVALID && io.memory.B.BREADY && BIDMatches
      when(io.memory.B.BVALID) {
        assert(BIDMatches, "AXI arbiter received a write response with an unknown ID")
      }
      when(MatchingBFire) {
        when(OwnerDMA) {
          dmaBValid := true.B
          dmaBID := dmaWriteOriginalID
          dmaBResp := io.memory.B.BRESP
          io.DMAWriteInvalidate := true.B
        }
        writeState := WriteIdle
      }
    }
  }
}
