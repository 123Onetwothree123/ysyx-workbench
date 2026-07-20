package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_AXI5._

class ysyx_26030103_ICache(
    blockSizeLog2: Int = 2,
    indexBits:     Int = 4,
    addrWidth:     Int = 32
) extends Module {
  val tagBits   = addrWidth - indexBits - blockSizeLog2
  val numBlocks = 1 << indexBits

  val io = IO(new Bundle {
    val fetch_addr  = Input(UInt(addrWidth.W))
    val fetch_valid = Input(Bool())
    val fetch_ready = Output(Bool())

    val resp_data  = Output(UInt(32.W))
    val resp_valid = Output(Bool())
    val resp_ready = Input(Bool())

    val axi = new ysyx_26030103_AXI5IO(addrWidth)

    val perf_hit        = Output(Bool())
    val perf_miss       = Output(Bool())
    val perf_refill_req = Output(Bool())
    val perf_refill_resp = Output(Bool())

    val access_fault      = Output(Bool())
    val access_fault_resp = Output(UInt(2.W))
  })

  val valid = RegInit(VecInit(Seq.fill(numBlocks)(false.B)))
  val tag   = Reg(Vec(numBlocks, UInt(tagBits.W)))
  val data  = Reg(Vec(numBlocks, UInt(32.W)))

  val states = Enum(4)
  val state_Idle       = states(0)
  val state_RefillReq  = states(1)
  val state_RefillResp = states(2)
  val state_Resp       = states(3)
  val state = RegInit(state_Idle)

  val index  = io.fetch_addr(indexBits + blockSizeLog2 - 1, blockSizeLog2)
  val reqTag = io.fetch_addr(addrWidth - 1, indexBits + blockSizeLog2)
  val hit    = valid(index) && tag(index) === reqTag

  val fetch_addr_reg  = Reg(UInt(addrWidth.W))
  val fetch_index_reg = Reg(UInt(indexBits.W))
  val fetch_tag_reg   = Reg(UInt(tagBits.W))
  val resp_data_reg   = Reg(UInt(32.W))
  val access_fault_reg     = RegInit(false.B)
  val access_fault_resp_reg = RegInit(0.U(2.W))

  io.access_fault      := access_fault_reg
  io.access_fault_resp := access_fault_resp_reg

  io.axi.AW.AWVALID := false.B
  io.axi.AW.AWID    := 0.U
  io.axi.AW.AWADDR  := 0.U
  io.axi.AW.AWLEN   := 0.U
  io.axi.AW.AWSIZE  := 2.U
  io.axi.AW.AWBURST := 1.U
  io.axi.AW.AWPROT  := 0.U
  io.axi.W.WVALID   := false.B
  io.axi.W.WDATA    := 0.U
  io.axi.W.WSTRB    := 0.U
  io.axi.W.WLAST    := false.B
  io.axi.B.BREADY   := false.B

  io.axi.AR.ARVALID := false.B
  io.axi.AR.ARID    := 0.U
  io.axi.AR.ARADDR  := 0.U
  io.axi.AR.ARLEN   := 0.U
  io.axi.AR.ARSIZE  := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT  := 0.U
  io.axi.R.RREADY   := false.B

  io.fetch_ready := false.B
  io.resp_valid  := false.B
  io.resp_data   := 0.U
  io.perf_hit         := false.B
  io.perf_miss        := false.B
  io.perf_refill_req  := state === state_RefillReq
  io.perf_refill_resp := state === state_RefillResp

  switch(state) {
    is(state_Idle) {
      access_fault_reg     := false.B
      access_fault_resp_reg := 0.U
      io.fetch_ready := true.B
      io.perf_hit  := io.fetch_valid && io.fetch_ready && hit
      io.perf_miss := io.fetch_valid && io.fetch_ready && !hit
      when(io.fetch_valid && io.fetch_ready) {
        fetch_addr_reg  := io.fetch_addr
        fetch_index_reg := index
        fetch_tag_reg   := reqTag
        resp_data_reg   := data(index)
        when(hit) {
          state := state_Resp
        }.otherwise {
          state := state_RefillReq
        }
      }
    }
    is(state_RefillReq) {
      io.axi.AR.ARVALID := true.B
      io.axi.AR.ARADDR  := Cat(fetch_addr_reg(addrWidth - 1, blockSizeLog2), 0.U(blockSizeLog2.W))
      when(io.axi.AR.ARREADY) {
        state := state_RefillResp
      }
    }
    is(state_RefillResp) {
      io.axi.R.RREADY := true.B
      when(io.axi.R.RVALID && io.axi.R.RREADY) {
        when(io.axi.R.RRESP =/= 0.U) {
          access_fault_reg     := true.B
          access_fault_resp_reg := io.axi.R.RRESP
        }.otherwise {
          valid(fetch_index_reg) := true.B
          tag(fetch_index_reg)   := fetch_tag_reg
          data(fetch_index_reg)  := io.axi.R.RDATA
          resp_data_reg          := io.axi.R.RDATA
        }
        state := state_Resp
      }
    }
    is(state_Resp) {
      io.resp_valid := true.B
      io.resp_data  := resp_data_reg
      when(io.resp_ready) {
        state := state_Idle
      }
    }
  }
}
