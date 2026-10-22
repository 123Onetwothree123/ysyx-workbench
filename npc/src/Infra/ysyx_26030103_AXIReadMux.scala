package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

/** 只读 AXI 二选一：ICache/DCache 与 PTW 共用读端口。
  *
  * A 口只允许一笔在途读（ICache refill 或复用 LSU 路径的 DCache refill），
  * 使用 ID 0。B 口是 PTW 页表读，使用 3..15 共 13 个 ID 槽，允许同一时刻
  * 有多笔 AR 在途。响应按 RID 路由，因此 ICache/DCache 的 burst 与多笔
  * PTW 单拍读可以重叠，互不覆盖对方数据。
  *
  * 写通道全部拒绝；复用 LSU 数据通路（passWriteA）时 A 口写通道直通，
  * 不引入新的写主设备。
  */
class ysyx_26030103_AXIReadMux(
    passWriteA: Boolean = false,
    val AddressWidth: Int = 32
) extends Module {
  val io = IO(new Bundle {
    val a = Flipped(new ysyx_26030103_AXI5IO(AddressWidth))
    val b = Flipped(new ysyx_26030103_AXI5IO(AddressWidth))
    val out = new ysyx_26030103_AXI5IO(AddressWidth)
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
  // 初始优先 A；A 获得授权后让 B 优先一次，避免 PTW 连续请求饿死取指。
  val lastGrantB = RegInit(false.B)

  val aReq = io.a.AR.ARVALID && !aBusy
  val bId = io.b.AR.ARID
  val bIdValid = bId >= PtwBaseID && bId <= PtwLastID
  val bReq = io.b.AR.ARVALID && bIdValid && !bBusy(bId)
  val chooseB = bReq && (!aReq || lastGrantB)
  val chooseA = aReq && !chooseB

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
    lastGrantB := chooseB
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

/** 把 PTW 的简单 32 位页表总线转换成多 outstanding 的单拍 AXI 读/写。
  *
  * 读请求进入 readQ 后，适配器为每笔分配一个 AXI ID（3..15）并立即发起
  * 单拍 AR，不等前一笔响应返回。槽位保存 PTW 原事务 ID，响应按 RID 回填
  * 到对应槽位，再以原 ID 送回 PTW，因此页表总线可以乱序完成。
  *
  * 4 位 AXI ID 还要保留 0/1/2 给 ICache/LSU/DMA，所以最多 13 笔 AXI 读
  * 同时在途。busDepth 只决定请求队列深度；当 PTW 配置超过 13 笔总线
  * outstanding 时，多出的请求在队列中等待 ID 槽位，不会丢请求。
  *
  * 写请求走独立 writeQ 和写 FSM，避免 A/D 写回与后续页表读在队列头
  * 互相阻塞。写事务仍保持单笔在途，写响应同样按原事务 ID 返回。
  */
class ysyx_26030103_PTWToAXI(
    busDepth: Int = 16,
    axiReadSlots: Int = ysyx_26030103_AXIReadId.PtwSlots,
    val AddressWidth: Int = 32
) extends Module {
  require(busDepth >= 1, "PTWToAXI 请求队列深度必须 >= 1")
  require(axiReadSlots >= 1 &&
    axiReadSlots <= ysyx_26030103_AXIReadId.PtwSlots,
    "PTWToAXI AXI 读槽位数必须在 1..13（ID 3..15）")
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTEBusRequest(AddressWidth)))
    val resp = Decoupled(new ysyx_26030103_PTEBusResponse)
    val axi = new ysyx_26030103_AXI5IO(AddressWidth)
    val axiWrite = new ysyx_26030103_AXI5IO(AddressWidth)
  })

  private val ReadBaseID = ysyx_26030103_AXIReadId.PtwBase.U(4.W)
  private val ReadLastID =
    (ysyx_26030103_AXIReadId.PtwBase + axiReadSlots).U

  def tieOffWrite(m: ysyx_26030103_AXI5IO): Unit = {
    m.AW.AWVALID := false.B
    m.AW.AWID := 0.U
    m.AW.AWADDR := 0.U
    m.AW.AWLEN := 0.U
    m.AW.AWSIZE := 2.U
    m.AW.AWBURST := 0.U
    m.AW.AWPROT := 0.U
    m.AW.AWCACHE := 0.U
    m.AW.AWQOS := 0.U
    m.W.WVALID := false.B
    m.W.WDATA := 0.U
    m.W.WSTRB := 0.U
    m.W.WLAST := false.B
    m.B.BREADY := false.B
  }
  def tieOffRead(m: ysyx_26030103_AXI5IO): Unit = {
    m.AR.ARVALID := false.B
    m.AR.ARID := 0.U
    m.AR.ARADDR := 0.U
    m.AR.ARLEN := 0.U
    m.AR.ARSIZE := 2.U
    m.AR.ARBURST := 0.U
    m.AR.ARPROT := 0.U
    m.AR.ARCACHE := 0.U
    m.AR.ARQOS := 0.U
    m.R.RREADY := false.B
  }

  // 读/写分流，避免一笔 A/D 写回挡住后续页表读。
  val readQ = Module(new Queue(new ysyx_26030103_PTEBusRequest(AddressWidth), busDepth,
    pipe = true, flow = false))
  val writeQ = Module(new Queue(new ysyx_26030103_PTEBusRequest(AddressWidth), busDepth,
    pipe = true, flow = false))
  readQ.io.enq.valid := io.req.valid && !io.req.bits.write
  readQ.io.enq.bits := io.req.bits
  writeQ.io.enq.valid := io.req.valid && io.req.bits.write
  writeQ.io.enq.bits := io.req.bits
  io.req.ready := Mux(io.req.bits.write,
    writeQ.io.enq.ready, readQ.io.enq.ready)

  tieOffWrite(io.axi)
  tieOffRead(io.axiWrite)

  /** 一个 AXI 读槽：记录 PTW 原事务 ID，并在响应到达后暂存读数据。
    * 槽位在 PTW 真正接收响应前一直占用，因此无需额外响应队列也能承受
    * PTW 侧反压。
    */
  class ReadSlot extends Bundle {
    val busy = Bool()
    val done = Bool()
    val origId = UInt(4.W)
    val data = UInt(32.W)
    val fault = Bool()
  }
  // 至少保留两个物理槽位，让 1 槽配置也有合法的 1 位动态索引；未参与
  // 分配的补齐槽位 busy 恒为 0，不会被调度。
  private val storageSlots = math.max(2, 1 << log2Ceil(axiReadSlots))
  private val slotIndexBits = log2Ceil(storageSlots)
  val readSlot = RegInit(VecInit(
    Seq.fill(storageSlots)(0.U.asTypeOf(new ReadSlot))))

  val slotFree = VecInit((0 until axiReadSlots).map(i => !readSlot(i).busy))
  val anySlotFree = slotFree.reduce(_ || _)
  // ARVALID 拉高后若 ARREADY 迟迟不来，AXI 要求地址与 ID 保持不变。
  // 固定槽位选择，避免等待期间低编号槽位变空导致 ARID 改变。
  val arPending = RegInit(false.B)
  val arSlotReg = RegInit(0.U(slotIndexBits.W))
  val freeIdx = Mux(arPending, arSlotReg,
    PriorityEncoder(slotFree))
  val readIssue = readQ.io.deq.valid && (arPending || anySlotFree)
  val freeAxiId = (ReadBaseID + freeIdx.pad(4))(3, 0)

  io.axi.AR.ARVALID := readIssue
  io.axi.AR.ARID := freeAxiId
  io.axi.AR.ARADDR := readQ.io.deq.bits.addr
  io.axi.AR.ARLEN := 0.U
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.AR.ARCACHE := 0.U
  io.axi.AR.ARQOS := 0.U
  readQ.io.deq.ready := readIssue && io.axi.AR.ARREADY

  when(readIssue && !io.axi.AR.ARREADY) {
    arPending := true.B
    arSlotReg := freeIdx
  }.elsewhen(io.axi.AR.ARREADY) {
    arPending := false.B
  }

  when(readQ.io.deq.fire) {
    readSlot(freeIdx).busy := true.B
    readSlot(freeIdx).done := false.B
    readSlot(freeIdx).origId := readQ.io.deq.bits.id
    readSlot(freeIdx).data := 0.U
    readSlot(freeIdx).fault := false.B
  }

  val rid = io.axi.R.RID
  val ridInRange = rid >= ReadBaseID && rid < ReadLastID
  // Vec 动态索引只保留实际槽位位宽；范围检查已保证截断后的值不会越界，
  // 也避免小配置下出现“索引位宽大于 Vec 地址位宽”的 elaboration 警告。
  val ridSlot = Mux(ridInRange,
    (rid - ReadBaseID)(slotIndexBits - 1, 0), 0.U)
  val acceptedSlotMatches =
    readQ.io.deq.fire && freeAxiId === rid
  val readRespExpected = ridInRange &&
    (readSlot(ridSlot).busy || acceptedSlotMatches)
  io.axi.R.RREADY := readRespExpected
  when(io.axi.R.RVALID) {
    assert(readRespExpected, "PTWToAXI received an unknown read response ID")
    assert(io.axi.R.RLAST, "PTWToAXI single-beat read must end with RLAST")
  }
  when(io.axi.R.RVALID && io.axi.R.RREADY) {
    // 普通响应对应 busy 槽；AR/R 同拍返回时 busy/origId 已在上面的
    // readQ 握手块中写入，这里只保存数据与错误状态。
    readSlot(ridSlot).done := true.B
    readSlot(ridSlot).data := io.axi.R.RDATA
    readSlot(ridSlot).fault := io.axi.R.RRESP =/= 0.U
  }

  // 写通道：单笔在途。B 响应先存入独立结果寄存器，不阻塞读通道。
  val writeIdle :: writeReq :: writeWait :: Nil = Enum(3)
  val writeState = RegInit(writeIdle)
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeDone = RegInit(false.B)
  val writeOrigId = RegInit(0.U(4.W))
  val writeFault = RegInit(false.B)
  val writeHead = writeQ.io.deq.bits

  io.axiWrite.AW.AWVALID :=
    writeState === writeReq && writeQ.io.deq.valid && !awDone
  io.axiWrite.AW.AWID := 0.U
  io.axiWrite.AW.AWADDR := writeHead.addr
  io.axiWrite.AW.AWLEN := 0.U
  io.axiWrite.AW.AWSIZE := 2.U
  io.axiWrite.AW.AWBURST := 1.U
  io.axiWrite.AW.AWPROT := 0.U
  io.axiWrite.AW.AWCACHE := 0.U
  io.axiWrite.AW.AWQOS := 0.U
  io.axiWrite.W.WVALID :=
    writeState === writeReq && writeQ.io.deq.valid && !wDone
  io.axiWrite.W.WDATA := writeHead.data
  io.axiWrite.W.WSTRB := writeHead.strb
  io.axiWrite.W.WLAST := true.B
  io.axiWrite.B.BREADY := writeState === writeWait

  val awFire = io.axiWrite.AW.AWVALID && io.axiWrite.AW.AWREADY
  val wFire = io.axiWrite.W.WVALID && io.axiWrite.W.WREADY
  val writeRespFire =
    writeState === writeWait && io.axiWrite.B.BVALID &&
      io.axiWrite.B.BREADY

  when(writeState === writeIdle) {
    when(writeQ.io.deq.valid && !writeDone) {
      writeState := writeReq
      awDone := false.B
      wDone := false.B
    }
  }
  when(writeState === writeReq) {
    when(awFire) { awDone := true.B }
    when(wFire) { wDone := true.B }
    when((awDone || awFire) && (wDone || wFire)) {
      writeState := writeWait
    }
  }
  when(writeRespFire) {
    writeQ.io.deq.ready := true.B
    writeDone := true.B
    writeOrigId := writeHead.id
    writeFault := io.axiWrite.B.BRESP =/= 0.U
    writeState := writeIdle
  }
  writeQ.io.deq.ready := writeRespFire

  // 读、写完成结果共享 PTW 响应口；写优先只是固定仲裁策略，读结果
  // 留在槽位里不会丢。
  val doneVec = VecInit((0 until storageSlots).map { i =>
    readSlot(i).busy && readSlot(i).done
  })
  val anyReadDone = doneVec.reduce(_ || _)
  val doneIdx = PriorityEncoder(doneVec)
  io.resp.valid := writeDone || anyReadDone
  io.resp.bits.id := Mux(writeDone, writeOrigId, readSlot(doneIdx).origId)
  io.resp.bits.data := Mux(writeDone, 0.U, readSlot(doneIdx).data)
  io.resp.bits.fault := Mux(writeDone, writeFault, readSlot(doneIdx).fault)
  when(io.resp.fire) {
    when(writeDone) {
      writeDone := false.B
    }.otherwise {
      readSlot(doneIdx).busy := false.B
      readSlot(doneIdx).done := false.B
    }
  }
}

/** 写通道二选一：LSU 与 PTW A/D 写回共用仲裁器的 LSU 写端口。
  *
  * 读通道仍由 LSU 独占；PTW 只做单拍写，因此这里只需要处理 AW/W/B。
  */
class ysyx_26030103_AXIWriteMux(
    val PassRead: Boolean = true,
    val AddressWidth: Int = 32
) extends Module {
  val io = IO(new Bundle {
    val a = Flipped(new ysyx_26030103_AXI5IO(AddressWidth))
    val b = Flipped(new ysyx_26030103_AXI5IO(AddressWidth))
    val out = new ysyx_26030103_AXI5IO(AddressWidth)
  })
  val idle :: aWrite :: bWrite :: aResp :: bResp :: Nil = Enum(5)
  val state = RegInit(idle)
  val awDone = RegInit(false.B)
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
