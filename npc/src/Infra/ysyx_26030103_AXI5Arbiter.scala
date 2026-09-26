package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** IFU与LSU端口的AXI仲裁器。
  *
  * 读写相互独立：LSU写事务等待B响应时，取指或load仍可处于在途状态。
  * 两个读主设备使用不同的下游ID，使一项IFU读和一项LSU读可以同时在途，
  * 并明确响应的归属。每个源仍限制为一项读事务，以匹配阻塞式ICache/DCache端口。
  */
class ysyx_26030103_AXI5Arbiter extends Module {
  val io = IO(new Bundle {
    val ifu = Flipped(new ysyx_26030103_AXI5IO(32))
    val lsu = Flipped(new ysyx_26030103_AXI5IO(32))
    val memory = new ysyx_26030103_AXI5IO(32)
  })

  private val IFUReadID = 0.U(4.W)
  private val LSUReadID = 1.U(4.W)
  private val GrantIFU = false.B
  private val GrantLSU = true.B

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
  val lastReadGrant = RegInit(GrantLSU)
  val readGrantHeld = RegInit(false.B)
  val heldReadGrant = RegInit(GrantIFU)
  val ifuReadRequest = io.ifu.AR.ARVALID && !ifuReadOutstanding
  val lsuReadRequest = io.lsu.AR.ARVALID && !lsuReadOutstanding
  val chooseNewLSU =
    lsuReadRequest && (!ifuReadRequest || lastReadGrant === GrantIFU)
  val chooseNewIFU = ifuReadRequest && !chooseNewLSU
  // ARVALID拉高但READY未拉高后，AXI要求AR的每个有效载荷字段保持稳定。
  // 因此，在受阻请求完成握手前，新到达的竞争主设备不能改变授权结果。
  val chooseLSU = Mux(readGrantHeld, heldReadGrant === GrantLSU, chooseNewLSU)
  val chooseIFU = Mux(readGrantHeld, heldReadGrant === GrantIFU, chooseNewIFU)

  when(chooseLSU) {
    io.memory.AR.ARVALID := io.lsu.AR.ARVALID
    io.memory.AR.ARID := LSUReadID
    io.memory.AR.ARADDR := io.lsu.AR.ARADDR
    io.memory.AR.ARLEN := io.lsu.AR.ARLEN
    io.memory.AR.ARSIZE := io.lsu.AR.ARSIZE
    io.memory.AR.ARBURST := io.lsu.AR.ARBURST
    io.memory.AR.ARPROT := io.lsu.AR.ARPROT
    io.lsu.AR.ARREADY := io.memory.AR.ARREADY
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
  when(!readGrantHeld && io.memory.AR.ARVALID && !io.memory.AR.ARREADY) {
    readGrantHeld := true.B
    heldReadGrant := Mux(chooseLSU, GrantLSU, GrantIFU)
  }.elsewhen(readGrantHeld && readAddressFire) {
    readGrantHeld := false.B
  }
  when(readAddressFire && chooseLSU) {
    lsuReadOutstanding := true.B
    lastReadGrant := GrantLSU
  }
  when(readAddressFire && chooseIFU) {
    ifuReadOutstanding := true.B
    lastReadGrant := GrantIFU
  }

  // 单拍弹性缓冲切断下游响应的组合路径，并在任一源施加反压时保持
  // 完整有效载荷稳定。
  val rSkidValid = RegInit(false.B)
  val rSkidID = RegInit(0.U(4.W))
  val rSkidData = Reg(UInt(32.W))
  val rSkidResp = Reg(UInt(2.W))
  val rSkidLast = Reg(Bool())
  val rForLSU = rSkidID === LSUReadID
  val rForIFU = rSkidID === IFUReadID
  val rOwnerReady = Mux(rForLSU, io.lsu.R.RREADY, io.ifu.R.RREADY)
  val rConsume = rSkidValid && (rForLSU || rForIFU) && rOwnerReady

  io.memory.R.RREADY := !rSkidValid || rConsume
  when(io.memory.R.RVALID && io.memory.R.RREADY) {
    rSkidValid := true.B
    rSkidID := io.memory.R.RID
    rSkidData := io.memory.R.RDATA
    rSkidResp := io.memory.R.RRESP
    rSkidLast := io.memory.R.RLAST
  }.elsewhen(rConsume) {
    rSkidValid := false.B
  }

  when(rSkidValid && rForLSU) {
    io.lsu.R.RID := 0.U
    io.lsu.R.RDATA := rSkidData
    io.lsu.R.RRESP := rSkidResp
    io.lsu.R.RLAST := rSkidLast
    io.lsu.R.RVALID := true.B
  }
  when(rSkidValid && rForIFU) {
    io.ifu.R.RID := 0.U
    io.ifu.R.RDATA := rSkidData
    io.ifu.R.RRESP := rSkidResp
    io.ifu.R.RLAST := rSkidLast
    io.ifu.R.RVALID := true.B
  }

  when(rConsume && rSkidLast && rForLSU) {
    lsuReadOutstanding := false.B
  }
  when(rConsume && rSkidLast && rForIFU) {
    ifuReadOutstanding := false.B
  }
  when(io.memory.R.RVALID && io.memory.R.RREADY) {
    assert(
      io.memory.R.RID === IFUReadID || io.memory.R.RID === LSUReadID,
      "AXI arbiter received an unknown read response ID"
    )
  }

  // 独立的LSU写通道。写事务仍保持有序和精确，但其AW/W/B延迟不再阻塞
  // 无关的取指或数据读取。
  val writeStates = Enum(3)
  val WriteIdle = writeStates(0)
  val WriteRequest = writeStates(1)
  val WriteResponse = writeStates(2)
  val writeState = RegInit(WriteIdle)
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  switch(writeState) {
    is(WriteIdle) {
      awDone := false.B
      wDone := false.B
      when(io.lsu.AW.AWVALID || io.lsu.W.WVALID) {
        writeState := WriteRequest
      }
    }
    is(WriteRequest) {
      val awFire =
        !awDone && io.lsu.AW.AWVALID && io.memory.AW.AWREADY
      val wFire = !wDone && io.lsu.W.WVALID && io.memory.W.WREADY
      val wLastFire = wFire && io.lsu.W.WLAST

      io.memory.AW.AWVALID := io.lsu.AW.AWVALID && !awDone
      io.memory.AW.AWID := io.lsu.AW.AWID
      io.memory.AW.AWADDR := io.lsu.AW.AWADDR
      io.memory.AW.AWLEN := io.lsu.AW.AWLEN
      io.memory.AW.AWSIZE := io.lsu.AW.AWSIZE
      io.memory.AW.AWBURST := io.lsu.AW.AWBURST
      io.memory.AW.AWPROT := io.lsu.AW.AWPROT
      io.lsu.AW.AWREADY := io.memory.AW.AWREADY && !awDone

      io.memory.W.WVALID := io.lsu.W.WVALID && !wDone
      io.memory.W.WDATA := io.lsu.W.WDATA
      io.memory.W.WSTRB := io.lsu.W.WSTRB
      io.memory.W.WLAST := io.lsu.W.WLAST
      io.lsu.W.WREADY := io.memory.W.WREADY && !wDone

      when(awFire) { awDone := true.B }
      when(wLastFire) { wDone := true.B }
      when((awDone || awFire) && (wDone || wLastFire)) {
        writeState := WriteResponse
      }
    }
    is(WriteResponse) {
      io.lsu.B.BID := io.memory.B.BID
      io.lsu.B.BRESP := io.memory.B.BRESP
      io.lsu.B.BVALID := io.memory.B.BVALID
      io.memory.B.BREADY := io.lsu.B.BREADY
      when(io.memory.B.BVALID && io.memory.B.BREADY) {
        writeState := WriteIdle
      }
    }
  }
}
