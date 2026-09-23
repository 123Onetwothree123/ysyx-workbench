package ysyx_26030103

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.ysyx_26030103.infra._

class riscv32e_npc_AXIRAM extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new ysyx_26030103_AXI5IO(32, 32, 4))
  })

  val depth = 65536
  val mem = SyncReadMem(depth, UInt(32.W))
  loadMemoryFromFileInline(mem, "build/program.hex")

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
  val RamBase = "h80000000".U(32.W)
  val RamLast = "h8003ffff".U(32.W)
  val UARTAddress = "h10000000".U(32.W)

  // A beat is in RAM only when every byte selected by AxSIZE remains inside
  // the 256 KiB simulation-memory window.  In particular, addresses outside
  // this window must never wrap through a truncated SyncReadMem index.
  def IsRAMBeat(address: UInt, size: UInt): Bool = {
    val lastStart = MuxLookup(size, 0.U(32.W))(
      Seq(
        0.U -> RamLast,
        1.U -> (RamLast - 1.U),
        2.U -> (RamLast - 3.U)
      )
    )
    size <= 2.U && address >= RamBase && address <= lastStart
  }

  val sIdle :: sReadWait :: sReadResp :: sWriteRead :: sWriteCommit :: sWriteDrain :: sWriteResp :: Nil =
    Enum(7)
  val state = RegInit(sIdle)
  val arAddr = RegInit(0.U(32.W))
  val arId = RegInit(0.U(4.W))
  val arLen = RegInit(0.U(8.W))
  val arSize = RegInit(2.U(3.W))
  val arBurst = RegInit(0.U(2.W))
  val readBeat = RegInit(0.U(8.W))
  val readConfigError = RegInit(false.B)
  val readResp = RegInit(OKAY)
  val readStep = MuxLookup(arSize, 0.U(32.W))(
    Seq(
      0.U -> 1.U(32.W),
      1.U -> 2.U(32.W),
      2.U -> 4.U(32.W)
    )
  )
  val readAddressIsRAM = IsRAMBeat(arAddr, arSize)
  val readWordIndex = ((arAddr - RamBase) >> 2)(15, 0)
  val rd = mem.read(
    readWordIndex,
    (state === sReadWait || state === sReadResp) &&
      !readConfigError && readAddressIsRAM
  )

  val awAddr = RegInit(0.U(32.W))
  val awId = RegInit(0.U(4.W))
  val awLen = RegInit(0.U(8.W))
  val awSize = RegInit(2.U(3.W))
  val awBurst = RegInit(0.U(2.W))
  val writeBeat = RegInit(0.U(8.W))
  val writeResp = RegInit(OKAY)
  val writeConfigError = RegInit(false.B)
  val wData = RegInit(0.U(32.W))
  val wStrb = RegInit(0.U(4.W))
  val wLast = RegInit(false.B)
  val awPending = RegInit(false.B)
  val wPending = RegInit(false.B)

  // SyncReadMem returns data one cycle after a read request.  A partial write
  // therefore has to read the old word first, then merge and write it in a
  // later state; using the read result in the request cycle would merge stale
  // or undefined data.
  val writeAddressIsRAM = IsRAMBeat(awAddr, awSize)
  val writeAddressIsUART = awAddr === UARTAddress
  val writeWordIndex = ((awAddr - RamBase) >> 2)(15, 0)
  val writeOld = mem.read(
    writeWordIndex,
    state === sWriteRead && !writeConfigError && writeAddressIsRAM
  )
  val writeMask = Cat(
    Mux(wStrb(3), 0xff.U(8.W), 0.U(8.W)),
    Mux(wStrb(2), 0xff.U(8.W), 0.U(8.W)),
    Mux(wStrb(1), 0xff.U(8.W), 0.U(8.W)),
    Mux(wStrb(0), 0xff.U(8.W), 0.U(8.W))
  )
  val writeMerged = (wData & writeMask) | (writeOld & ~writeMask)
  val writeStep = MuxLookup(awSize, 0.U(32.W))(
    Seq(
      0.U -> 1.U(32.W),
      1.U -> 2.U(32.W),
      2.U -> 4.U(32.W)
    )
  )
  val expectedLastBeat = writeBeat === awLen

  // Give a partially collected write priority over reads.  AW and W are
  // accepted independently and may arrive in either order.
  val writeIncoming = awPending || wPending || io.axi.AW.AWVALID || io.axi.W.WVALID
  io.axi.AW.AWREADY := state === sIdle && !awPending
  io.axi.W.WREADY := (state === sIdle && !wPending) || state === sWriteDrain
  io.axi.AR.ARREADY := state === sIdle && !writeIncoming

  val awFire = io.axi.AW.AWVALID && io.axi.AW.AWREADY
  val wFire = io.axi.W.WVALID && io.axi.W.WREADY
  val wCollectFire = wFire && state === sIdle
  val wDrainFire = wFire && state === sWriteDrain
  val arFire = io.axi.AR.ARVALID && io.axi.AR.ARREADY
  val haveAW = awPending || awFire
  val haveW = wPending || wCollectFire

  when(awFire) {
    awAddr := io.axi.AW.AWADDR
    awId := io.axi.AW.AWID
    awLen := io.axi.AW.AWLEN
    awSize := io.axi.AW.AWSIZE
    awBurst := io.axi.AW.AWBURST
    writeBeat := 0.U
    writeConfigError := io.axi.AW.AWSIZE > 2.U || io.axi.AW.AWBURST > 1.U
    writeResp := Mux(
      io.axi.AW.AWSIZE > 2.U || io.axi.AW.AWBURST > 1.U,
      SLVERR,
      OKAY
    )
    awPending := true.B
  }
  when(wCollectFire) {
    wData := io.axi.W.WDATA
    wStrb := io.axi.W.WSTRB
    wLast := io.axi.W.WLAST
    wPending := true.B
  }

  io.axi.R.RVALID := state === sReadResp
  io.axi.R.RDATA := Mux(readResp === OKAY, rd, 0.U)
  io.axi.R.RRESP := readResp
  io.axi.R.RLAST := state === sReadResp && readBeat === arLen
  io.axi.R.RID := arId

  io.axi.B.BVALID := state === sWriteResp
  io.axi.B.BRESP := writeResp
  io.axi.B.BID := awId

  switch(state) {
    is(sIdle) {
      when(arFire) {
        arAddr := io.axi.AR.ARADDR
        arId := io.axi.AR.ARID
        arLen := io.axi.AR.ARLEN
        arSize := io.axi.AR.ARSIZE
        arBurst := io.axi.AR.ARBURST
        readBeat := 0.U
        readConfigError := io.axi.AR.ARSIZE > 2.U || io.axi.AR.ARBURST > 1.U
        state := sReadWait
      }.elsewhen(haveAW && haveW) {
        state := sWriteRead
      }
    }
    is(sReadWait) {
      readResp := Mux(
        readConfigError,
        SLVERR,
        Mux(readAddressIsRAM, OKAY, DECERR)
      )
      state := sReadResp
    }
    is(sReadResp) {
      when(io.axi.R.RREADY) {
        when(readBeat === arLen) {
          state := sIdle
        }.otherwise {
          readBeat := readBeat + 1.U
          when(arBurst === 1.U && !readConfigError) {
            arAddr := arAddr + readStep
          }
          state := sReadWait
        }
      }
    }
    is(sWriteRead) {
      // Launch the synchronous read.  writeOld is valid in sWriteCommit.
      state := sWriteCommit
    }
    is(sWriteCommit) {
      when(!writeConfigError) {
        when(writeAddressIsRAM) {
          mem.write(writeWordIndex, writeMerged)
        }.elsewhen(writeAddressIsUART) {
          // UART is a side-effect-only target and is deliberately disjoint
          // from the RAM window.  Only an enabled low byte emits a character.
          when(wStrb(0)) {
            printf("%c", wData(7, 0))
          }
        }.otherwise {
          when(writeResp === OKAY) {
            writeResp := DECERR
          }
        }
      }
      when(wLast) {
        when(!expectedLastBeat) {
          writeResp := SLVERR // early WLAST
        }
        state := sWriteResp
      }.elsewhen(expectedLastBeat) {
        // AWLEN beats have arrived without WLAST.  Drain through WLAST so the
        // upstream write-data channel cannot remain wedged, then report SLVERR.
        writeResp := SLVERR
        wPending := false.B
        state := sWriteDrain
      }.otherwise {
        writeBeat := writeBeat + 1.U
        when(awBurst === 1.U) { // INCR; FIXED keeps the same address
          awAddr := awAddr + writeStep
        }
        wPending := false.B
        state := sIdle
      }
    }
    is(sWriteDrain) {
      when(wDrainFire && io.axi.W.WLAST) {
        state := sWriteResp
      }
    }
    is(sWriteResp) {
      when(io.axi.B.BREADY) {
        awPending := false.B
        wPending := false.B
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
  ram.io.axi.AW.AWADDR := cpu.io.master_awaddr
  ram.io.axi.AW.AWID := cpu.io.master_awid
  ram.io.axi.AW.AWLEN := cpu.io.master_awlen
  ram.io.axi.AW.AWSIZE := cpu.io.master_awsize
  ram.io.axi.AW.AWBURST := cpu.io.master_awburst
  ram.io.axi.AW.AWPROT := cpu.io.master_awprot

  cpu.io.master_wready := ram.io.axi.W.WREADY
  ram.io.axi.W.WVALID := cpu.io.master_wvalid
  ram.io.axi.W.WDATA := cpu.io.master_wdata
  ram.io.axi.W.WSTRB := cpu.io.master_wstrb
  ram.io.axi.W.WLAST := cpu.io.master_wlast

  cpu.io.master_bvalid := ram.io.axi.B.BVALID
  cpu.io.master_bresp := ram.io.axi.B.BRESP
  cpu.io.master_bid := ram.io.axi.B.BID
  ram.io.axi.B.BREADY := cpu.io.master_bready

  cpu.io.master_arready := ram.io.axi.AR.ARREADY
  ram.io.axi.AR.ARVALID := cpu.io.master_arvalid
  ram.io.axi.AR.ARADDR := cpu.io.master_araddr
  ram.io.axi.AR.ARID := cpu.io.master_arid
  ram.io.axi.AR.ARLEN := cpu.io.master_arlen
  ram.io.axi.AR.ARSIZE := cpu.io.master_arsize
  ram.io.axi.AR.ARBURST := cpu.io.master_arburst
  ram.io.axi.AR.ARPROT := cpu.io.master_arprot

  cpu.io.master_rvalid := ram.io.axi.R.RVALID
  cpu.io.master_rresp := ram.io.axi.R.RRESP
  cpu.io.master_rdata := ram.io.axi.R.RDATA
  cpu.io.master_rlast := ram.io.axi.R.RLAST
  cpu.io.master_rid := ram.io.axi.R.RID
  ram.io.axi.R.RREADY := cpu.io.master_rready

  cpu.io.slave_awvalid := false.B
  cpu.io.slave_awaddr := 0.U
  cpu.io.slave_awid := 0.U
  cpu.io.slave_awlen := 0.U
  cpu.io.slave_awsize := 0.U
  cpu.io.slave_awburst := 0.U
  cpu.io.slave_awlock := false.B
  cpu.io.slave_awcache := 0.U
  cpu.io.slave_awprot := 0.U
  cpu.io.slave_awqos := 0.U
  cpu.io.slave_wvalid := false.B
  cpu.io.slave_wdata := 0.U
  cpu.io.slave_wstrb := 0.U
  cpu.io.slave_wlast := false.B
  cpu.io.slave_bready := false.B
  cpu.io.slave_arvalid := false.B
  cpu.io.slave_araddr := 0.U
  cpu.io.slave_arid := 0.U
  cpu.io.slave_arlen := 0.U
  cpu.io.slave_arsize := 0.U
  cpu.io.slave_arburst := 0.U
  cpu.io.slave_arlock := false.B
  cpu.io.slave_arcache := 0.U
  cpu.io.slave_arprot := 0.U
  cpu.io.slave_arqos := 0.U
  cpu.io.slave_rready := false.B

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
  val debug_next_pc = IO(Output(UInt(32.W)))
  debug_next_pc := cpu.io.debug_next_pc
  val debug_arch_pc = IO(Output(UInt(32.W)))
  debug_arch_pc := cpu.io.debug_arch_pc
  val debug_instructions = IO(Output(UInt(32.W)))
  debug_instructions := cpu.io.debug_instructions

  val debug_mtrace_valid = IO(Output(Bool()))
  debug_mtrace_valid := cpu.io.debug_mtrace_valid
  val debug_mtrace_pc = IO(Output(UInt(32.W)))
  debug_mtrace_pc := cpu.io.debug_mtrace_pc
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
  val debug_access_fault_pc = IO(Output(UInt(32.W)))
  debug_access_fault_pc := cpu.io.debug_access_fault_pc
  val debug_access_fault_resp = IO(Output(UInt(2.W)))
  debug_access_fault_resp := cpu.io.debug_access_fault_resp
  val debug_commit = IO(Output(Bool()))
  debug_commit := cpu.io.debug_commit
  val debug_trap_valid = IO(Output(Bool()))
  debug_trap_valid := cpu.io.debug_trap_valid
  val debug_trap_pc = IO(Output(UInt(32.W)))
  debug_trap_pc := cpu.io.debug_trap_pc
  val debug_trap_target = IO(Output(UInt(32.W)))
  debug_trap_target := cpu.io.debug_trap_target
  val debug_trap_cause = IO(Output(UInt(32.W)))
  debug_trap_cause := cpu.io.debug_trap_cause
  val debug_csr_mstatus = IO(Output(UInt(32.W)))
  val debug_csr_mtvec = IO(Output(UInt(32.W)))
  val debug_csr_mepc = IO(Output(UInt(32.W)))
  val debug_csr_mcause = IO(Output(UInt(32.W)))
  val debug_trap_mstatus = IO(Output(UInt(32.W)))
  val debug_trap_mtvec = IO(Output(UInt(32.W)))
  val debug_trap_mepc = IO(Output(UInt(32.W)))
  val debug_trap_mcause = IO(Output(UInt(32.W)))
  debug_csr_mstatus := cpu.io.debug_csr_mstatus
  debug_csr_mtvec := cpu.io.debug_csr_mtvec
  debug_csr_mepc := cpu.io.debug_csr_mepc
  debug_csr_mcause := cpu.io.debug_csr_mcause
  debug_trap_mstatus := cpu.io.debug_trap_mstatus
  debug_trap_mtvec := cpu.io.debug_trap_mtvec
  debug_trap_mepc := cpu.io.debug_trap_mepc
  debug_trap_mcause := cpu.io.debug_trap_mcause

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
  val perf_mdu_req = IO(Output(Bool()))
  perf_mdu_req := cpu.io.perf_mdu_req
  val perf_mdu_done = IO(Output(Bool()))
  perf_mdu_done := cpu.io.perf_mdu_done
  val perf_mdu_op = IO(Output(UInt(3.W)))
  perf_mdu_op := cpu.io.perf_mdu_op
  val perf_mdu_active = IO(Output(Bool()))
  perf_mdu_active := cpu.io.perf_mdu_active
  val perf_mdu_wait = IO(Output(Bool()))
  perf_mdu_wait := cpu.io.perf_mdu_wait

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
  val perf_icache_hit = IO(Output(Bool()))
  perf_icache_hit := cpu.io.perf_icache_hit
  val perf_icache_miss = IO(Output(Bool()))
  perf_icache_miss := cpu.io.perf_icache_miss

  val cyc = RegInit(0.U(32.W))
  cyc := cyc + 1.U
  when(cyc < 20.U && trap_valid) {
    printf(
      p"[?] Simulation halt was requested by the custom halt instruction\n"
    )
  }
}
