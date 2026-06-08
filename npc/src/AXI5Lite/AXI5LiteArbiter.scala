package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteArbiter extends Module {
  val io = IO(new Bundle {
    val ifu = Flipped(new AXI5LiteIO(32))
    val lsu = Flipped(new AXI5LiteIO(32))
    val memory = new AXI5LiteIO(32)
  })

  val states = Enum(5)
  val StatesIdle = states(0)
  val StatesReadRequest = states(1)
  val StatesReadResponse = states(2)
  val StatesWriteRequest = states(3)
  val StatesWriteResponse = states(4)
  val state = RegInit(StatesIdle)

  val GrantIFU = 0.U(1.W)
  val GrantLSU = 1.U(1.W)
  val grant = RegInit(GrantIFU)

  val AWDone = RegInit(false.B)
  val WDone = RegInit(false.B)

  io.ifu.AW.AWREADY := false.B
  io.ifu.W.WREADY := false.B
  io.ifu.B.BRESP := 0.U
  io.ifu.B.BVALID := false.B
  io.ifu.AR.ARREADY := false.B
  io.ifu.R.RDATA := 0.U
  io.ifu.R.RRESP := 0.U
  io.ifu.R.RVALID := false.B

  io.lsu.AW.AWREADY := false.B
  io.lsu.W.WREADY := false.B
  io.lsu.B.BRESP := 0.U
  io.lsu.B.BVALID := false.B
  io.lsu.AR.ARREADY := false.B
  io.lsu.R.RDATA := 0.U
  io.lsu.R.RRESP := 0.U
  io.lsu.R.RVALID := false.B

  io.memory.AW.AWVALID := false.B
  io.memory.AW.AWADDR := 0.U
  io.memory.AW.AWPROT := 0.U
  io.memory.W.WVALID := false.B
  io.memory.W.WDATA := 0.U
  io.memory.W.WSTRB := 0.U
  io.memory.B.BREADY := false.B
  io.memory.AR.ARVALID := false.B
  io.memory.AR.ARADDR := 0.U
  io.memory.AR.ARPROT := 0.U
  io.memory.R.RREADY := false.B

  val LSUWriteRequest = io.lsu.AW.AWVALID || io.lsu.W.WVALID
  val LSUReadRequest = io.lsu.AR.ARVALID
  val IFUReadRequest = io.ifu.AR.ARVALID

  switch(state) {
    is(StatesIdle) {
      AWDone := false.B
      WDone := false.B
      when(LSUWriteRequest) {
        grant := GrantLSU
        state := StatesWriteRequest
      }.elsewhen(LSUReadRequest) {
        grant := GrantLSU
        state := StatesReadRequest
      }.elsewhen(IFUReadRequest) {
        grant := GrantIFU
        state := StatesReadRequest
      }
    }
    is(StatesReadRequest) {
      when(grant === GrantLSU) {
        io.memory.AR.ARVALID := io.lsu.AR.ARVALID
        io.memory.AR.ARADDR := io.lsu.AR.ARADDR
        io.memory.AR.ARPROT := io.lsu.AR.ARPROT
        io.lsu.AR.ARREADY := io.memory.AR.ARREADY
      }.otherwise {
        io.memory.AR.ARVALID := io.ifu.AR.ARVALID
        io.memory.AR.ARADDR := io.ifu.AR.ARADDR
        io.memory.AR.ARPROT := io.ifu.AR.ARPROT
        io.ifu.AR.ARREADY := io.memory.AR.ARREADY
      }
      when(io.memory.AR.ARVALID && io.memory.AR.ARREADY) {
        state := StatesReadResponse
      }
    }
    is(StatesReadResponse) {
      when(grant === GrantLSU) {
        io.lsu.R.RDATA := io.memory.R.RDATA
        io.lsu.R.RRESP := io.memory.R.RRESP
        io.lsu.R.RVALID := io.memory.R.RVALID
        io.memory.R.RREADY := io.lsu.R.RREADY
      }.otherwise {
        io.ifu.R.RDATA := io.memory.R.RDATA
        io.ifu.R.RRESP := io.memory.R.RRESP
        io.ifu.R.RVALID := io.memory.R.RVALID
        io.memory.R.RREADY := io.ifu.R.RREADY
      }
      when(io.memory.R.RVALID && io.memory.R.RREADY) {
        state := StatesIdle
      }
    }
    is(StatesWriteRequest) {
      val AWAlreadyDone = AWDone
      val WAlreadyDone = WDone
      val AWFire = !AWAlreadyDone && io.lsu.AW.AWVALID && io.memory.AW.AWREADY
      val WFire = !WAlreadyDone && io.lsu.W.WVALID && io.memory.W.WREADY

      io.memory.AW.AWVALID := io.lsu.AW.AWVALID && !AWAlreadyDone
      io.memory.AW.AWADDR := io.lsu.AW.AWADDR
      io.memory.AW.AWPROT := io.lsu.AW.AWPROT
      io.lsu.AW.AWREADY := io.memory.AW.AWREADY && !AWAlreadyDone

      io.memory.W.WVALID := io.lsu.W.WVALID && !WAlreadyDone
      io.memory.W.WDATA := io.lsu.W.WDATA
      io.memory.W.WSTRB := io.lsu.W.WSTRB
      io.lsu.W.WREADY := io.memory.W.WREADY && !WAlreadyDone

      when(AWFire) {
        AWDone := true.B
      }
      when(WFire) {
        WDone := true.B
      }
      when((AWAlreadyDone || AWFire) && (WAlreadyDone || WFire)) {
        state := StatesWriteResponse
      }
    }
    is(StatesWriteResponse) {
      io.lsu.B.BRESP := io.memory.B.BRESP
      io.lsu.B.BVALID := io.memory.B.BVALID
      io.memory.B.BREADY := io.lsu.B.BREADY
      when(io.memory.B.BVALID && io.memory.B.BREADY) {
        state := StatesIdle
      }
    }
  }
}
