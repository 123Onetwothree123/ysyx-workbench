package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

// PTW 页表总线到多 outstanding AXI 单拍读/写的适配器；ID 0/1/2 保留，PTW 用 3..15。
class ysyx_26030103_PTWToAXI(
    busDepth: Int = ysyx_26030103_KconfigDefaults.MMUPTWBusDepth,
    axiReadSlots: Int = ysyx_26030103_AXIReadID.PTWSlots,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth
) extends Module {
  require(DataWidth == 32 || DataWidth == 64,
    s"PTWToAXI DataWidth 非法: $DataWidth")
  require(busDepth >= 1, "PTWToAXI 请求队列深度必须 >= 1")
  require(axiReadSlots >= 1 &&
    axiReadSlots <= ysyx_26030103_AXIReadID.PTWSlots,
    "PTWToAXI AXI 读槽位数必须在 1..13（ID 3..15）")
  private val busWide = DataWidth == 64
  private def busWord(data: UInt, address: UInt): UInt =
    if (busWide)
      Mux(address(2), Cat(data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), data(31, 0)))
    else data(31, 0)
  private def busStrobe(strb: UInt, address: UInt): UInt =
    if (busWide)
      Mux(address(2), Cat(strb(3, 0), 0.U(4.W)),
        Cat(0.U(4.W), strb(3, 0)))
    else strb(3, 0)
  private def busSelectWord(data: UInt, address: UInt): UInt =
    if (busWide) Mux(address(2), data(63, 32), data(31, 0))
    else data(31, 0)
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTEBusRequest(AddressWidth)))
    val resp = Decoupled(new ysyx_26030103_PTEBusResponse)
    val axi = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
    val axiWrite = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
  })

  private val readBaseID = ysyx_26030103_AXIReadID.PTWBase.U(4.W)
  private val readLastID =
    (ysyx_26030103_AXIReadID.PTWBase + axiReadSlots).U

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
  val readQueue = Module(new Queue(new ysyx_26030103_PTEBusRequest(AddressWidth), busDepth,
    pipe = true, flow = false))
  val writeQueue = Module(new Queue(new ysyx_26030103_PTEBusRequest(AddressWidth), busDepth,
    pipe = true, flow = false))
  readQueue.io.enq.valid := io.req.valid && !io.req.bits.write
  readQueue.io.enq.bits := io.req.bits
  writeQueue.io.enq.valid := io.req.valid && io.req.bits.write
  writeQueue.io.enq.bits := io.req.bits
  io.req.ready := Mux(io.req.bits.write,
    writeQueue.io.enq.ready, readQueue.io.enq.ready)

  tieOffWrite(io.axi)
  tieOffRead(io.axiWrite)

  class ReadSlot extends Bundle {
    val busy = Bool()
    val done = Bool()
    val origID = UInt(4.W)
    val addr = UInt(AddressWidth.W)
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
  val freeIndex = Mux(arPending, arSlotReg,
    PriorityEncoder(slotFree))
  val readIssue = readQueue.io.deq.valid && (arPending || anySlotFree)
  val freeAXIID = (readBaseID + freeIndex.pad(4))(3, 0)

  io.axi.AR.ARVALID := readIssue
  io.axi.AR.ARID := freeAXIID
  io.axi.AR.ARADDR := readQueue.io.deq.bits.addr
  io.axi.AR.ARLEN := 0.U
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.AR.ARCACHE := 0.U
  io.axi.AR.ARQOS := 0.U
  readQueue.io.deq.ready := readIssue && io.axi.AR.ARREADY

  when(readIssue && !io.axi.AR.ARREADY) {
    arPending := true.B
    arSlotReg := freeIndex
  }.elsewhen(io.axi.AR.ARREADY) {
    arPending := false.B
  }

  when(readQueue.io.deq.fire) {
    readSlot(freeIndex).busy := true.B
    readSlot(freeIndex).done := false.B
    readSlot(freeIndex).origID := readQueue.io.deq.bits.id
    readSlot(freeIndex).addr := readQueue.io.deq.bits.addr
    readSlot(freeIndex).data := 0.U
    readSlot(freeIndex).fault := false.B
  }

  val rid = io.axi.R.RID
  val ridInRange = rid >= readBaseID && rid < readLastID
  // Vec 动态索引只保留实际槽位位宽；范围检查已保证截断后的值不会越界，
  // 也避免小配置下出现“索引位宽大于 Vec 地址位宽”的 elaboration 警告。
  val ridSlot = Mux(ridInRange,
    (rid - readBaseID)(slotIndexBits - 1, 0), 0.U)
  val acceptedSlotMatches =
    readQueue.io.deq.fire && freeAXIID === rid
  val readResponseAddress =
    Mux(acceptedSlotMatches, readQueue.io.deq.bits.addr,
      readSlot(ridSlot).addr)
  val readResponseExpected = ridInRange &&
    (readSlot(ridSlot).busy || acceptedSlotMatches)
  io.axi.R.RREADY := readResponseExpected
  when(io.axi.R.RVALID) {
    assert(readResponseExpected, "PTWToAXI received an unknown read response ID")
    assert(io.axi.R.RLAST, "PTWToAXI single-beat read must end with RLAST")
  }
  when(io.axi.R.RVALID && io.axi.R.RREADY) {
    // 普通响应对应 busy 槽；AR/R 同拍返回时 busy/origId 已在上面的
    // readQueue 握手块中写入，这里只保存数据与错误状态。
    readSlot(ridSlot).done := true.B
    readSlot(ridSlot).data :=
      busSelectWord(io.axi.R.RDATA, readResponseAddress)
    readSlot(ridSlot).fault := io.axi.R.RRESP =/= 0.U
  }

  // 写通道：单笔在途。B 响应先存入独立结果寄存器，不阻塞读通道。
  val writeIdle :: writeRequest :: writeWait :: Nil = Enum(3)
  val writeState = RegInit(writeIdle)
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeDone = RegInit(false.B)
  val writeOrigID = RegInit(0.U(4.W))
  val writeFault = RegInit(false.B)
  val writeHead = writeQueue.io.deq.bits

  io.axiWrite.AW.AWVALID :=
    writeState === writeRequest && writeQueue.io.deq.valid && !awDone
  io.axiWrite.AW.AWID := 0.U
  io.axiWrite.AW.AWADDR := writeHead.addr
  io.axiWrite.AW.AWLEN := 0.U
  io.axiWrite.AW.AWSIZE := 2.U
  io.axiWrite.AW.AWBURST := 1.U
  io.axiWrite.AW.AWPROT := 0.U
  io.axiWrite.AW.AWCACHE := 0.U
  io.axiWrite.AW.AWQOS := 0.U
  io.axiWrite.W.WVALID :=
    writeState === writeRequest && writeQueue.io.deq.valid && !wDone
  io.axiWrite.W.WDATA := busWord(writeHead.data, writeHead.addr)
  io.axiWrite.W.WSTRB := busStrobe(writeHead.strb, writeHead.addr)
  io.axiWrite.W.WLAST := true.B
  io.axiWrite.B.BREADY := writeState === writeWait

  val awFire = io.axiWrite.AW.AWVALID && io.axiWrite.AW.AWREADY
  val wFire = io.axiWrite.W.WVALID && io.axiWrite.W.WREADY
  val writeResponseFire =
    writeState === writeWait && io.axiWrite.B.BVALID &&
      io.axiWrite.B.BREADY

  when(writeState === writeIdle) {
    when(writeQueue.io.deq.valid && !writeDone) {
      writeState := writeRequest
      awDone := false.B
      wDone := false.B
    }
  }
  when(writeState === writeRequest) {
    when(awFire) { awDone := true.B }
    when(wFire) { wDone := true.B }
    when((awDone || awFire) && (wDone || wFire)) {
      writeState := writeWait
    }
  }
  when(writeResponseFire) {
    writeDone := true.B
    writeOrigID := writeHead.id
    writeFault := io.axiWrite.B.BRESP =/= 0.U
    writeState := writeIdle
  }
  writeQueue.io.deq.ready := writeResponseFire

  // 读、写完成结果共享 PTW 响应口；写优先只是固定仲裁策略，读结果
  // 留在槽位里不会丢。
  val doneVec = VecInit((0 until storageSlots).map { i =>
    readSlot(i).busy && readSlot(i).done
  })
  val anyReadDone = doneVec.reduce(_ || _)
  val doneIndex = PriorityEncoder(doneVec)
  io.resp.valid := writeDone || anyReadDone
  io.resp.bits.id := Mux(writeDone, writeOrigID, readSlot(doneIndex).origID)
  io.resp.bits.data := Mux(writeDone, 0.U, readSlot(doneIndex).data)
  io.resp.bits.fault := Mux(writeDone, writeFault, readSlot(doneIndex).fault)
  when(io.resp.fire) {
    when(writeDone) {
      writeDone := false.B
    }.otherwise {
      readSlot(doneIndex).busy := false.B
      readSlot(doneIndex).done := false.B
    }
  }
}
