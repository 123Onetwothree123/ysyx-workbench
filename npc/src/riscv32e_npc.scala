package ysyx_26030103

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_AXI5._

class riscv32e_npc_AXIRAM extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new ysyx_26030103_AXI5IO(32, 32, 4))
  })

  val depth = 65536
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
        rData  := mem.read((io.axi.AR.ARADDR - 0x80000000L.U) >> 2)
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
        val addr = (awAddr - 0x80000000L.U) >> 2
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
  val io = IO(new ysyx_26030103_IO)

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

  cpu.io.slave_awready := DontCare
  cpu.io.slave_wready  := DontCare
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
  cpu.io.slave_bvalid  := DontCare
  cpu.io.slave_bresp   := DontCare
  cpu.io.slave_bid     := DontCare
  cpu.io.slave_arready := DontCare
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
  cpu.io.slave_rvalid  := DontCare
  cpu.io.slave_rresp   := DontCare
  cpu.io.slave_rdata   := DontCare
  cpu.io.slave_rlast   := DontCare
  cpu.io.slave_rid     := DontCare

  io <> cpu.io

  io.trap_valid.suggestName("trap_valid")
  io.trap_pc.suggestName("trap_pc")
  io.debug_gpr_rdata.suggestName("debug_gpr_rdata")
  io.debug_pc.suggestName("debug_pc")
  io.debug_instructions.suggestName("debug_instructions")
  io.debug_mtrace_valid.suggestName("debug_mtrace_valid")
  io.debug_mtrace_wen.suggestName("debug_mtrace_wen")
  io.debug_mtrace_addr.suggestName("debug_mtrace_addr")
  io.debug_mtrace_wdata.suggestName("debug_mtrace_wdata")
  io.debug_mtrace_rdata.suggestName("debug_mtrace_rdata")
  io.debug_mtrace_width.suggestName("debug_mtrace_width")
  io.debug_access_fault.suggestName("debug_access_fault")
  io.debug_access_fault_resp.suggestName("debug_access_fault_resp")
  io.debug_commit.suggestName("debug_commit")
  io.perf_ifu_fetch.suggestName("perf_ifu_fetch")
  io.perf_exu_done.suggestName("perf_exu_done")
  io.perf_lsu_load.suggestName("perf_lsu_load")
  io.perf_lsu_store.suggestName("perf_lsu_store")
  io.perf_alu_op.suggestName("perf_alu_op")
  io.perf_mem_op.suggestName("perf_mem_op")
  io.perf_csr_op.suggestName("perf_csr_op")
  io.perf_branch_op.suggestName("perf_branch_op")
  io.perf_ifu_stall_pipeline.suggestName("perf_ifu_stall_pipeline")
  io.perf_ifu_stall_axi.suggestName("perf_ifu_stall_axi")
  io.perf_ifu_stall_ar.suggestName("perf_ifu_stall_ar")
  io.perf_ifu_stall_r.suggestName("perf_ifu_stall_r")
  io.perf_ifu_stall_redirect.suggestName("perf_ifu_stall_redirect")
  io.perf_ifu_stall_idle.suggestName("perf_ifu_stall_idle")
  io.perf_execution_active.suggestName("perf_execution_active")
  io.perf_exu_stall_lsu.suggestName("perf_exu_stall_lsu")
  io.perf_lsu_active.suggestName("perf_lsu_active")
  io.perf_lsu_load_active.suggestName("perf_lsu_load_active")
  io.perf_lsu_store_active.suggestName("perf_lsu_store_active")
  io.perf_lsu_stall_read_ar.suggestName("perf_lsu_stall_read_ar")
  io.perf_lsu_stall_read_r.suggestName("perf_lsu_stall_read_r")
  io.perf_lsu_stall_write_req.suggestName("perf_lsu_stall_write_req")
  io.perf_lsu_stall_write_b.suggestName("perf_lsu_stall_write_b")
}
