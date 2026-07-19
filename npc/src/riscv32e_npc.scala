package ysyx_26030103

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_AXI5._

class riscv32e_npc_AXIRAM extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new ysyx_26030103_AXI5IO(32, 32, 4))
  })

  val depth = 65536
  val mask  = (depth - 1).U(32.W)
  val mem = SyncReadMem(depth, UInt(32.W))

  val sIdle :: sReadResp :: sWriteResp :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val arAddr = Reg(UInt(32.W))
  val rData  = Reg(UInt(32.W))

  val awAddr = Reg(UInt(32.W))
  val wData  = Reg(UInt(32.W))
  val wStrb  = Reg(UInt(4.W))

  io.axi.AR.ARREADY := state === sIdle
  io.axi.R.RVALID   := state === sReadResp
  io.axi.R.RDATA    := rData
  io.axi.R.RRESP    := 0.U
  io.axi.R.RLAST    := true.B
  io.axi.R.RID      := 0.U

  io.axi.AW.AWREADY := state === sIdle
  io.axi.W.WREADY   := state === sIdle
  io.axi.B.BVALID   := state === sWriteResp
  io.axi.B.BRESP    := 0.U
  io.axi.B.BID      := 0.U

  switch (state) {
    is (sIdle) {
      when (io.axi.AR.ARVALID && io.axi.AR.ARREADY) {
        arAddr := io.axi.AR.ARADDR
        val addr = ((io.axi.AR.ARADDR - 0x80000000L.U) >> 2) & mask
        rData  := mem.read(addr)
        state  := sReadResp
      }.elsewhen (io.axi.AW.AWVALID && io.axi.W.WVALID) {
        awAddr := io.axi.AW.AWADDR
        wData  := io.axi.W.WDATA
        wStrb  := io.axi.W.WSTRB
        state  := sWriteResp
      }
    }
    is (sReadResp) {
      when (io.axi.R.RREADY) {
        state := sIdle
      }
    }
    is (sWriteResp) {
      when (io.axi.B.BREADY) {
        val addr = ((awAddr - 0x80000000L.U) >> 2) & mask
        val old  = mem.read(addr)
        val mask = Cat(
          Mux(wStrb(3), 0xff.U(8.W), 0.U(8.W)),
          Mux(wStrb(2), 0xff.U(8.W), 0.U(8.W)),
          Mux(wStrb(1), 0xff.U(8.W), 0.U(8.W)),
          Mux(wStrb(0), 0xff.U(8.W), 0.U(8.W)))
        mem.write(addr, (wData & mask) | (old & ~mask))
        state := sIdle
      }
    }
  }
}

class riscv32e_npc_SimTop extends Module {
  val cpu = Module(new ysyx_26030103)
  val ram = Module(new riscv32e_npc_AXIRAM)

  cpu.io.interrupt := false.B

  cpu.io.master_awready := ram.io.axi.AW.AWREADY
  ram.io.axi.AW.AWVALID := cpu.io.master_awvalid
  ram.io.axi.AW.AWADDR  := cpu.io.master_awaddr
  ram.io.axi.AW.AWID    := cpu.io.master_awid
  ram.io.axi.AW.AWLEN   := cpu.io.master_awlen
  ram.io.axi.AW.AWSIZE  := cpu.io.master_awsize
  ram.io.axi.AW.AWBURST := cpu.io.master_awburst
  ram.io.axi.AW.AWPROT  := cpu.io.master_awprot

  cpu.io.master_wready := ram.io.axi.W.WREADY
  ram.io.axi.W.WVALID  := cpu.io.master_wvalid
  ram.io.axi.W.WDATA   := cpu.io.master_wdata
  ram.io.axi.W.WSTRB   := cpu.io.master_wstrb
  ram.io.axi.W.WLAST   := cpu.io.master_wlast

  cpu.io.master_bvalid := ram.io.axi.B.BVALID
  cpu.io.master_bresp  := ram.io.axi.B.BRESP
  cpu.io.master_bid    := ram.io.axi.B.BID
  ram.io.axi.B.BREADY  := cpu.io.master_bready

  cpu.io.master_arready := ram.io.axi.AR.ARREADY
  ram.io.axi.AR.ARVALID := cpu.io.master_arvalid
  ram.io.axi.AR.ARADDR  := cpu.io.master_araddr
  ram.io.axi.AR.ARID    := cpu.io.master_arid
  ram.io.axi.AR.ARLEN   := cpu.io.master_arlen
  ram.io.axi.AR.ARSIZE  := cpu.io.master_arsize
  ram.io.axi.AR.ARBURST := cpu.io.master_arburst
  ram.io.axi.AR.ARPROT  := cpu.io.master_arprot

  cpu.io.master_rvalid := ram.io.axi.R.RVALID
  cpu.io.master_rresp  := ram.io.axi.R.RRESP
  cpu.io.master_rdata  := ram.io.axi.R.RDATA
  cpu.io.master_rlast  := ram.io.axi.R.RLAST
  cpu.io.master_rid    := ram.io.axi.R.RID
  ram.io.axi.R.RREADY  := cpu.io.master_rready

  cpu.io.slave_awvalid := false.B
  cpu.io.slave_awaddr  := 0.U
  cpu.io.slave_awid    := 0.U
  cpu.io.slave_awlen   := 0.U
  cpu.io.slave_awsize  := 0.U
  cpu.io.slave_awburst := 0.U
  cpu.io.slave_awlock  := false.B
  cpu.io.slave_awcache := 0.U
  cpu.io.slave_awprot  := 0.U
  cpu.io.slave_awqos   := 0.U
  cpu.io.slave_wvalid  := false.B
  cpu.io.slave_wdata   := 0.U
  cpu.io.slave_wstrb   := 0.U
  cpu.io.slave_wlast   := false.B
  cpu.io.slave_bready  := false.B
  cpu.io.slave_arvalid := false.B
  cpu.io.slave_araddr  := 0.U
  cpu.io.slave_arid    := 0.U
  cpu.io.slave_arlen   := 0.U
  cpu.io.slave_arsize  := 0.U
  cpu.io.slave_arburst := 0.U
  cpu.io.slave_arlock  := false.B
  cpu.io.slave_arcache := 0.U
  cpu.io.slave_arprot  := 0.U
  cpu.io.slave_arqos   := 0.U
  cpu.io.slave_rready  := false.B

  val trap_valid = IO(Output(Bool()))
  trap_valid := cpu.io.trap_valid
  val trap_pc = IO(Output(UInt(32.W)))
  trap_pc := cpu.io.trap_pc

  val debug_gpr_raddr = IO(Input(UInt(5.W)))
  cpu.io.debug_gpr_raddr := debug_gpr_raddr
  val debug_gpr_rdata = IO(Output(UInt(32.W)))
  debug_gpr_rdata := cpu.io.debug_gpr_rdata
  val debug_pc = IO(Output(UInt(32.W)))
  debug_pc := cpu.io.debug_pc
  val debug_instructions = IO(Output(UInt(32.W)))
  debug_instructions := cpu.io.debug_instructions

  val debug_mtrace_valid = IO(Output(Bool()))
  debug_mtrace_valid := cpu.io.debug_mtrace_valid
  val debug_mtrace_wen = IO(Output(Bool()))
  debug_mtrace_wen := cpu.io.debug_mtrace_wen
  val debug_mtrace_addr = IO(Output(UInt(32.W)))
  debug_mtrace_addr := cpu.io.debug_mtrace_addr
  val debug_mtrace_wdata = IO(Output(UInt(32.W)))
  debug_mtrace_wdata := cpu.io.debug_mtrace_wdata
  val debug_mtrace_rdata = IO(Output(UInt(32.W)))
  debug_mtrace_rdata := cpu.io.debug_mtrace_rdata
  val debug_mtrace_width = IO(Output(UInt(2.W)))
  debug_mtrace_width := cpu.io.debug_mtrace_width

  val debug_access_fault = IO(Output(Bool()))
  debug_access_fault := cpu.io.debug_access_fault
  val debug_access_fault_resp = IO(Output(UInt(2.W)))
  debug_access_fault_resp := cpu.io.debug_access_fault_resp
  val debug_commit = IO(Output(Bool()))
  debug_commit := cpu.io.debug_commit

  val perf_ifu_fetch = IO(Output(Bool()))
  perf_ifu_fetch := cpu.io.perf_ifu_fetch
  val perf_exu_done = IO(Output(Bool()))
  perf_exu_done := cpu.io.perf_exu_done
  val perf_lsu_load = IO(Output(Bool()))
  perf_lsu_load := cpu.io.perf_lsu_load
  val perf_lsu_store = IO(Output(Bool()))
  perf_lsu_store := cpu.io.perf_lsu_store
  val perf_alu_op = IO(Output(Bool()))
  perf_alu_op := cpu.io.perf_alu_op
  val perf_mem_op = IO(Output(Bool()))
  perf_mem_op := cpu.io.perf_mem_op
  val perf_csr_op = IO(Output(Bool()))
  perf_csr_op := cpu.io.perf_csr_op
  val perf_branch_op = IO(Output(Bool()))
  perf_branch_op := cpu.io.perf_branch_op

  val perf_ifu_stall_pipeline = IO(Output(Bool()))
  perf_ifu_stall_pipeline := cpu.io.perf_ifu_stall_pipeline
  val perf_ifu_stall_axi = IO(Output(Bool()))
  perf_ifu_stall_axi := cpu.io.perf_ifu_stall_axi
  val perf_ifu_stall_ar = IO(Output(Bool()))
  perf_ifu_stall_ar := cpu.io.perf_ifu_stall_ar
  val perf_ifu_stall_r = IO(Output(Bool()))
  perf_ifu_stall_r := cpu.io.perf_ifu_stall_r
  val perf_ifu_stall_redirect = IO(Output(Bool()))
  perf_ifu_stall_redirect := cpu.io.perf_ifu_stall_redirect
  val perf_ifu_stall_idle = IO(Output(Bool()))
  perf_ifu_stall_idle := cpu.io.perf_ifu_stall_idle
  val perf_execution_active = IO(Output(Bool()))
  perf_execution_active := cpu.io.perf_execution_active
  val perf_exu_stall_lsu = IO(Output(Bool()))
  perf_exu_stall_lsu := cpu.io.perf_exu_stall_lsu
  val perf_lsu_active = IO(Output(Bool()))
  perf_lsu_active := cpu.io.perf_lsu_active
  val perf_lsu_load_active = IO(Output(Bool()))
  perf_lsu_load_active := cpu.io.perf_lsu_load_active
  val perf_lsu_store_active = IO(Output(Bool()))
  perf_lsu_store_active := cpu.io.perf_lsu_store_active
  val perf_lsu_stall_read_ar = IO(Output(Bool()))
  perf_lsu_stall_read_ar := cpu.io.perf_lsu_stall_read_ar
  val perf_lsu_stall_read_r = IO(Output(Bool()))
  perf_lsu_stall_read_r := cpu.io.perf_lsu_stall_read_r
  val perf_lsu_stall_write_req = IO(Output(Bool()))
  perf_lsu_stall_write_req := cpu.io.perf_lsu_stall_write_req
  val perf_lsu_stall_write_b = IO(Output(Bool()))
  perf_lsu_stall_write_b := cpu.io.perf_lsu_stall_write_b
}
