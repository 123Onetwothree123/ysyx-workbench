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
  val cpu = Module(new ysyx_26030103)
  val ram = Module(new riscv32e_npc_AXIRAM)

  // 逐端口声明 IO，与 ysyxSoCFull 命名一致（无 io_ 前缀）
  val clockIO = IO(Input(Clock()))
  val resetIO = IO(Input(Reset()))
  val interrupt = IO(Input(Bool()))
  val trap_valid = IO(Output(Bool()))
  val trap_pc = IO(Output(UInt(32.W)))

  val master_awready = IO(Input(Bool()))
  val master_awvalid = IO(Output(Bool()))
  val master_awaddr  = IO(Output(UInt(32.W)))
  val master_awid    = IO(Output(UInt(4.W)))
  val master_awlen   = IO(Output(UInt(8.W)))
  val master_awsize  = IO(Output(UInt(3.W)))
  val master_awburst = IO(Output(UInt(2.W)))
  val master_awprot  = IO(Output(UInt(3.W)))

  val master_wready = IO(Input(Bool()))
  val master_wvalid = IO(Output(Bool()))
  val master_wdata  = IO(Output(UInt(32.W)))
  val master_wstrb  = IO(Output(UInt(4.W)))
  val master_wlast  = IO(Output(Bool()))

  val master_bready = IO(Output(Bool()))
  val master_bvalid = IO(Input(Bool()))
  val master_bresp  = IO(Input(UInt(2.W)))
  val master_bid    = IO(Input(UInt(4.W)))

  val master_arready = IO(Input(Bool()))
  val master_arvalid = IO(Output(Bool()))
  val master_araddr  = IO(Output(UInt(32.W)))
  val master_arid    = IO(Output(UInt(4.W)))
  val master_arlen   = IO(Output(UInt(8.W)))
  val master_arsize  = IO(Output(UInt(3.W)))
  val master_arburst = IO(Output(UInt(2.W)))
  val master_arprot  = IO(Output(UInt(3.W)))

  val master_rready = IO(Output(Bool()))
  val master_rvalid = IO(Input(Bool()))
  val master_rresp  = IO(Input(UInt(2.W)))
  val master_rdata  = IO(Input(UInt(32.W)))
  val master_rlast  = IO(Input(Bool()))
  val master_rid    = IO(Input(UInt(4.W)))

  val slave_awready = IO(Output(Bool()))
  val slave_awvalid = IO(Input(Bool()))
  val slave_awaddr  = IO(Input(UInt(32.W)))
  val slave_awid    = IO(Input(UInt(4.W)))
  val slave_awlen   = IO(Input(UInt(8.W)))
  val slave_awsize  = IO(Input(UInt(3.W)))
  val slave_awburst = IO(Input(UInt(2.W)))
  val slave_awlock  = IO(Input(Bool()))
  val slave_awcache = IO(Input(UInt(4.W)))
  val slave_awprot  = IO(Input(UInt(3.W)))
  val slave_awqos   = IO(Input(UInt(4.W)))

  val slave_wready = IO(Output(Bool()))
  val slave_wvalid = IO(Input(Bool()))
  val slave_wdata  = IO(Input(UInt(32.W)))
  val slave_wstrb  = IO(Input(UInt(4.W)))
  val slave_wlast  = IO(Input(Bool()))

  val slave_bready = IO(Input(Bool()))
  val slave_bvalid = IO(Output(Bool()))
  val slave_bresp  = IO(Output(UInt(2.W)))
  val slave_bid    = IO(Output(UInt(4.W)))

  val slave_arready = IO(Output(Bool()))
  val slave_arvalid = IO(Input(Bool()))
  val slave_araddr  = IO(Input(UInt(32.W)))
  val slave_arid    = IO(Input(UInt(4.W)))
  val slave_arlen   = IO(Input(UInt(8.W)))
  val slave_arsize  = IO(Input(UInt(3.W)))
  val slave_arburst = IO(Input(UInt(2.W)))
  val slave_arlock  = IO(Input(Bool()))
  val slave_arcache = IO(Input(UInt(4.W)))
  val slave_arprot  = IO(Input(UInt(3.W)))
  val slave_arqos   = IO(Input(UInt(4.W)))

  val slave_rready = IO(Input(Bool()))
  val slave_rvalid = IO(Output(Bool()))
  val slave_rresp  = IO(Output(UInt(2.W)))
  val slave_rdata  = IO(Output(UInt(32.W)))
  val slave_rlast  = IO(Output(Bool()))
  val slave_rid    = IO(Output(UInt(4.W)))

  val debug_gpr_raddr = IO(Input(UInt(5.W)))
  val debug_gpr_rdata = IO(Output(UInt(32.W)))
  val debug_pc = IO(Output(UInt(32.W)))
  val debug_instructions = IO(Output(UInt(32.W)))
  val debug_mtrace_valid = IO(Output(Bool()))
  val debug_mtrace_wen   = IO(Output(Bool()))
  val debug_mtrace_addr  = IO(Output(UInt(32.W)))
  val debug_mtrace_wdata = IO(Output(UInt(32.W)))
  val debug_mtrace_rdata = IO(Output(UInt(32.W)))
  val debug_mtrace_width = IO(Output(UInt(2.W)))
  val debug_access_fault = IO(Output(Bool()))
  val debug_access_fault_resp = IO(Output(UInt(2.W)))
  val debug_commit = IO(Output(Bool()))

  val perf_ifu_fetch  = IO(Output(Bool()))
  val perf_exu_done   = IO(Output(Bool()))
  val perf_lsu_load   = IO(Output(Bool()))
  val perf_lsu_store  = IO(Output(Bool()))
  val perf_alu_op     = IO(Output(Bool()))
  val perf_mem_op     = IO(Output(Bool()))
  val perf_csr_op     = IO(Output(Bool()))
  val perf_branch_op  = IO(Output(Bool()))
  val perf_ifu_stall_pipeline = IO(Output(Bool()))
  val perf_ifu_stall_axi      = IO(Output(Bool()))
  val perf_ifu_stall_ar       = IO(Output(Bool()))
  val perf_ifu_stall_r        = IO(Output(Bool()))
  val perf_ifu_stall_redirect = IO(Output(Bool()))
  val perf_ifu_stall_idle     = IO(Output(Bool()))
  val perf_execution_active   = IO(Output(Bool()))
  val perf_exu_stall_lsu      = IO(Output(Bool()))
  val perf_lsu_active         = IO(Output(Bool()))
  val perf_lsu_load_active    = IO(Output(Bool()))
  val perf_lsu_store_active   = IO(Output(Bool()))
  val perf_lsu_stall_read_ar  = IO(Output(Bool()))
  val perf_lsu_stall_read_r   = IO(Output(Bool()))
  val perf_lsu_stall_write_req = IO(Output(Bool()))
  val perf_lsu_stall_write_b  = IO(Output(Bool()))

  // 连接时钟复位
  cpu.clock := clockIO
  cpu.reset := resetIO
  cpu.io.interrupt := interrupt

  // AXI master → RAM
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

  // slave 悬空
  cpu.io.slave_awvalid := false.B
  cpu.io.slave_wvalid  := false.B
  cpu.io.slave_bready  := false.B
  cpu.io.slave_arvalid := false.B
  cpu.io.slave_rready  := false.B

  // 端口透传
  interrupt           := false.B
  trap_valid          := cpu.io.trap_valid
  trap_pc             := cpu.io.trap_pc
  master_awready      := DontCare
  master_awvalid      := DontCare
  master_awaddr       := DontCare
  master_awid         := DontCare
  master_awlen        := DontCare
  master_awsize       := DontCare
  master_awburst      := DontCare
  master_awprot       := DontCare
  master_wready       := DontCare
  master_wvalid       := DontCare
  master_wdata        := DontCare
  master_wstrb        := DontCare
  master_wlast        := DontCare
  master_bready       := DontCare
  master_bvalid       := DontCare
  master_bresp        := DontCare
  master_bid          := DontCare
  master_arready      := DontCare
  master_arvalid      := DontCare
  master_araddr       := DontCare
  master_arid         := DontCare
  master_arlen        := DontCare
  master_arsize       := DontCare
  master_arburst      := DontCare
  master_arprot       := DontCare
  master_rready       := DontCare
  master_rvalid       := DontCare
  master_rresp        := DontCare
  master_rdata        := DontCare
  master_rlast        := DontCare
  master_rid          := DontCare
  slave_awready       := DontCare
  slave_wready        := DontCare
  slave_bvalid        := DontCare
  slave_bresp         := DontCare
  slave_bid           := DontCare
  slave_arready       := DontCare
  slave_rvalid        := DontCare
  slave_rresp         := DontCare
  slave_rdata         := DontCare
  slave_rlast         := DontCare
  slave_rid           := DontCare
  debug_gpr_raddr     := cpu.io.debug_gpr_raddr
  debug_gpr_rdata     := cpu.io.debug_gpr_rdata
  debug_pc            := cpu.io.debug_pc
  debug_instructions  := cpu.io.debug_instructions
  debug_mtrace_valid  := cpu.io.debug_mtrace_valid
  debug_mtrace_wen    := cpu.io.debug_mtrace_wen
  debug_mtrace_addr   := cpu.io.debug_mtrace_addr
  debug_mtrace_wdata  := cpu.io.debug_mtrace_wdata
  debug_mtrace_rdata  := cpu.io.debug_mtrace_rdata
  debug_mtrace_width  := cpu.io.debug_mtrace_width
  debug_access_fault  := cpu.io.debug_access_fault
  debug_access_fault_resp := cpu.io.debug_access_fault_resp
  debug_commit        := cpu.io.debug_commit
  perf_ifu_fetch      := cpu.io.perf_ifu_fetch
  perf_exu_done       := cpu.io.perf_exu_done
  perf_lsu_load       := cpu.io.perf_lsu_load
  perf_lsu_store      := cpu.io.perf_lsu_store
  perf_alu_op         := cpu.io.perf_alu_op
  perf_mem_op         := cpu.io.perf_mem_op
  perf_csr_op         := cpu.io.perf_csr_op
  perf_branch_op      := cpu.io.perf_branch_op
  perf_ifu_stall_pipeline := cpu.io.perf_ifu_stall_pipeline
  perf_ifu_stall_axi      := cpu.io.perf_ifu_stall_axi
  perf_ifu_stall_ar       := cpu.io.perf_ifu_stall_ar
  perf_ifu_stall_r        := cpu.io.perf_ifu_stall_r
  perf_ifu_stall_redirect := cpu.io.perf_ifu_stall_redirect
  perf_ifu_stall_idle     := cpu.io.perf_ifu_stall_idle
  perf_execution_active   := cpu.io.perf_execution_active
  perf_exu_stall_lsu      := cpu.io.perf_exu_stall_lsu
  perf_lsu_active         := cpu.io.perf_lsu_active
  perf_lsu_load_active    := cpu.io.perf_lsu_load_active
  perf_lsu_store_active   := cpu.io.perf_lsu_store_active
  perf_lsu_stall_read_ar  := cpu.io.perf_lsu_stall_read_ar
  perf_lsu_stall_read_r   := cpu.io.perf_lsu_stall_read_r
  perf_lsu_stall_write_req := cpu.io.perf_lsu_stall_write_req
  perf_lsu_stall_write_b  := cpu.io.perf_lsu_stall_write_b
}
