package ysyx_26030103.infra
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import _root_.ysyx_26030103.common.ysyx_26030103_AXI5Lane.{
  LaneStrobe, LaneWord, SelectWord
}

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

// PTW 页表总线到多 outstanding AXI 单拍读/写的适配器；ID 0/1/2 保留，PTW 用 3..15。
class ysyx_26030103_PTWToAXI(
    busDepth: Int = ysyx_26030103_KconfigDefaults.MMUPTWBusDepth,
    AXIReadSlots: Int = ysyx_26030103_AXIReadID.PTWSlots,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth
) extends Module {
  require(DataWidth == 32 || DataWidth == 64,
    s"PTWToAXI DataWidth 非法: $DataWidth")
  require(busDepth >= 1, "PTWToAXI 请求队列深度必须 >= 1")
  require(AXIReadSlots >= 1 &&
    AXIReadSlots <= ysyx_26030103_AXIReadID.PTWSlots,
    "PTWToAXI AXI 读槽位数必须在 1..13（ID 3..15）")
  private val busWide = DataWidth == 64
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ysyx_26030103_PTEBusRequest(AddressWidth)))
    val response = Decoupled(new ysyx_26030103_PTEBusResponse)
    val AXI = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
    val AXIWrite = new ysyx_26030103_AXI5IO(AddressWidth, DataWidth, 4)
  })

  private val ReadBaseID = ysyx_26030103_AXIReadID.PTWBase.U(4.W)
  private val ReadLastID =
    (ysyx_26030103_AXIReadID.PTWBase + AXIReadSlots).U

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
  readQueue.io.enq.valid := io.request.valid && !io.request.bits.write
  readQueue.io.enq.bits := io.request.bits
  writeQueue.io.enq.valid := io.request.valid && io.request.bits.write
  writeQueue.io.enq.bits := io.request.bits
  io.request.ready := Mux(io.request.bits.write,
    writeQueue.io.enq.ready, readQueue.io.enq.ready)

  tieOffWrite(io.AXI)
  tieOffRead(io.AXIWrite)

  class ReadSlot extends Bundle {
    val busy = Bool()
    val done = Bool()
    val OrigID = UInt(4.W)
    val address = UInt(AddressWidth.W)
    val data = UInt(32.W)
    val fault = Bool()
  }
  // 至少保留两个物理槽位，让 1 槽配置也有合法的 1 位动态索引；未参与
  // 分配的补齐槽位 busy 恒为 0，不会被调度。
  private val storageSlots = math.max(2, 1 << log2Ceil(AXIReadSlots))
  private val slotIndexBits = log2Ceil(storageSlots)
  val readSlot = RegInit(VecInit(
    Seq.fill(storageSlots)(0.U.asTypeOf(new ReadSlot))))

  val slotFree = VecInit((0 until AXIReadSlots).map(i => !readSlot(i).busy))
  val anySlotFree = slotFree.reduce(_ || _)
  // ARVALID 拉高后若 ARREADY 迟迟不来，AXI 要求地址与 ID 保持不变。
  // 固定槽位选择，避免等待期间低编号槽位变空导致 ARID 改变。
  val ARPending = RegInit(false.B)
  val ARSlotReg = RegInit(0.U(slotIndexBits.W))
  val freeIndex = Mux(ARPending, ARSlotReg,
    PriorityEncoder(slotFree))
  val readIssue = readQueue.io.deq.valid && (ARPending || anySlotFree)
  val freeAXIID = (ReadBaseID + freeIndex.pad(4))(3, 0)

  io.AXI.AR.ARVALID := readIssue
  io.AXI.AR.ARID := freeAXIID
  io.AXI.AR.ARADDR := readQueue.io.deq.bits.address
  io.AXI.AR.ARLEN := 0.U
  io.AXI.AR.ARSIZE := 2.U
  io.AXI.AR.ARBURST := 1.U
  io.AXI.AR.ARPROT := 0.U
  io.AXI.AR.ARCACHE := 0.U
  io.AXI.AR.ARQOS := 0.U
  readQueue.io.deq.ready := readIssue && io.AXI.AR.ARREADY

  when(readIssue && !io.AXI.AR.ARREADY) {
    ARPending := true.B
    ARSlotReg := freeIndex
  }.elsewhen(io.AXI.AR.ARREADY) {
    ARPending := false.B
  }

  when(readQueue.io.deq.fire) {
    readSlot(freeIndex).busy := true.B
    readSlot(freeIndex).done := false.B
    readSlot(freeIndex).OrigID := readQueue.io.deq.bits.ID
    readSlot(freeIndex).address := readQueue.io.deq.bits.address
    readSlot(freeIndex).data := 0.U
    readSlot(freeIndex).fault := false.B
  }

  val rid = io.AXI.R.RID
  val ridInRange = rid >= ReadBaseID && rid < ReadLastID
  // Vec 动态索引只保留实际槽位位宽；范围检查已保证截断后的值不会越界，
  // 也避免小配置下出现“索引位宽大于 Vec 地址位宽”的 elaboration 警告。
  val ridSlot = Mux(ridInRange,
    (rid - ReadBaseID)(slotIndexBits - 1, 0), 0.U)
  val acceptedSlotMatches =
    readQueue.io.deq.fire && freeAXIID === rid
  val readResponseAddress =
    Mux(acceptedSlotMatches, readQueue.io.deq.bits.address,
      readSlot(ridSlot).address)
  val readResponseExpected = ridInRange &&
    (readSlot(ridSlot).busy || acceptedSlotMatches)
  io.AXI.R.RREADY := readResponseExpected
  when(io.AXI.R.RVALID) {
    assert(readResponseExpected, "PTWToAXI received an unknown read response ID")
    assert(io.AXI.R.RLAST, "PTWToAXI single-beat read must end with RLAST")
  }
  when(io.AXI.R.RVALID && io.AXI.R.RREADY) {
    // 普通响应对应 busy 槽；AR/R 同拍返回时 busy/origId 已在上面的
    // readQueue 握手块中写入，这里只保存数据与错误状态。
    readSlot(ridSlot).done := true.B
    readSlot(ridSlot).data :=
      SelectWord(io.AXI.R.RDATA, readResponseAddress, busWide)
    readSlot(ridSlot).fault := io.AXI.R.RRESP =/= 0.U
  }

  // 写通道：单笔在途。B 响应先存入独立结果寄存器，不阻塞读通道。
  val writeIdle :: writeRequest :: writeWait :: Nil = Enum(3)
  val writeState = RegInit(writeIdle)
  val AWDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val writeDone = RegInit(false.B)
  val WriteOrigID = RegInit(0.U(4.W))
  val writeFault = RegInit(false.B)
  val writeHead = writeQueue.io.deq.bits

  io.AXIWrite.AW.AWVALID :=
    writeState === writeRequest && writeQueue.io.deq.valid && !AWDone
  io.AXIWrite.AW.AWID := 0.U
  io.AXIWrite.AW.AWADDR := writeHead.address
  io.AXIWrite.AW.AWLEN := 0.U
  io.AXIWrite.AW.AWSIZE := 2.U
  io.AXIWrite.AW.AWBURST := 1.U
  io.AXIWrite.AW.AWPROT := 0.U
  io.AXIWrite.AW.AWCACHE := 0.U
  io.AXIWrite.AW.AWQOS := 0.U
  io.AXIWrite.W.WVALID :=
    writeState === writeRequest && writeQueue.io.deq.valid && !wDone
  io.AXIWrite.W.WDATA :=
    LaneWord(writeHead.data, writeHead.address, busWide)
  io.AXIWrite.W.WSTRB :=
    LaneStrobe(writeHead.strobe, writeHead.address, busWide)
  io.AXIWrite.W.WLAST := true.B
  io.AXIWrite.B.BREADY := writeState === writeWait

  val AWFire = io.AXIWrite.AW.AWVALID && io.AXIWrite.AW.AWREADY
  val wFire = io.AXIWrite.W.WVALID && io.AXIWrite.W.WREADY
  val writeResponseFire =
    writeState === writeWait && io.AXIWrite.B.BVALID &&
      io.AXIWrite.B.BREADY

  when(writeState === writeIdle) {
    when(writeQueue.io.deq.valid && !writeDone) {
      writeState := writeRequest
      AWDone := false.B
      wDone := false.B
    }
  }
  when(writeState === writeRequest) {
    when(AWFire) { AWDone := true.B }
    when(wFire) { wDone := true.B }
    when((AWDone || AWFire) && (wDone || wFire)) {
      writeState := writeWait
    }
  }
  when(writeResponseFire) {
    writeDone := true.B
    WriteOrigID := writeHead.ID
    writeFault := io.AXIWrite.B.BRESP =/= 0.U
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
  io.response.valid := writeDone || anyReadDone
  io.response.bits.ID := Mux(writeDone, WriteOrigID, readSlot(doneIndex).OrigID)
  io.response.bits.data := Mux(writeDone, 0.U, readSlot(doneIndex).data)
  io.response.bits.fault := Mux(writeDone, writeFault, readSlot(doneIndex).fault)
  when(io.response.fire) {
    when(writeDone) {
      writeDone := false.B
    }.otherwise {
      readSlot(doneIndex).busy := false.B
      readSlot(doneIndex).done := false.B
    }
  }
}
