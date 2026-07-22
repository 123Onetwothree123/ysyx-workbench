package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.system.SimAXIMem

object AXI4SlaveNodeGenerator {
  def apply(params: Option[MasterPortParams], address: Seq[AddressSet])(implicit
      valName: ValName
  ) =
    AXI4SlaveNode(
      params
        .map(p =>
          AXI4SlavePortParameters(
            slaves = Seq(
              AXI4SlaveParameters(
                address = address,
                executable = p.executable,
                supportsWrite = TransferSizes(1, p.maxXferBytes),
                supportsRead = TransferSizes(1, p.maxXferBytes)
              )
            ),
            beatBytes = p.beatBytes
          )
        )
        .toSeq
    )
}

class ysyxSoCASIC(implicit p: Parameters) extends LazyModule {
  val xbar = AXI4Xbar()
  val xbar2 = AXI4Xbar()
  val apbxbar = LazyModule(new APBFanout).node
  val cpu = LazyModule(new CPU(idBits = ChipLinkParam.idBits))
  val chipMaster =
    if (Config.hasChipLink) Some(LazyModule(new ChipLinkMaster)) else None
  val chiplinkNode =
    if (Config.hasChipLink)
      Some(AXI4SlaveNodeGenerator(p(ExtBus), ChipLinkParam.allSpace))
    else None

  val luart = LazyModule(
    new APBUart16550(AddressSet.misaligned(0x10000000, 0x1000))
  )
  val lgpio = LazyModule(new APBGPIO(AddressSet.misaligned(0x10002000, 0x10)))
  val lkeyboard = LazyModule(
    new APBKeyboard(AddressSet.misaligned(0x10011000, 0x8))
  )
  val lvga = LazyModule(new APBVGA(AddressSet.misaligned(0x21000000, 0x200000)))
  val lspi = LazyModule(
    new APBSPI(
      AddressSet.misaligned(0x10001000, 0x1000) // SPI controller (XIP moved to MROM)
    )
  )
  val lpsram = LazyModule(
    new APBPSRAM(AddressSet.misaligned(0x80000000L, 0x400000))
  )
  val lmrom = LazyModule(
    new AXI4MROM(AddressSet.misaligned(0x30000000, 0x10000000))
  )
  val sramNode = AXI4RAM(
    AddressSet.misaligned(0x0f000000, 0x8000).head,
    false,
    true,
    4,
    None,
    Nil,
    false
  )

  val sdramAddressSet = AddressSet.misaligned(0xa0000000L, 0x2000000)
  val lsdram_axi = AXI4RAM(
    sdramAddressSet.head,
    true,   // executable
    true,   // supportsWrite
    4,      // beatBytes
    None,
    Nil,
    false
  )

  List(
    lspi.node,
    luart.node,
    lpsram.node,
    lgpio.node,
    lkeyboard.node,
    lvga.node
  ).map(_ := apbxbar)
  List(
    apbxbar := APBDelayer() := AXI4ToAPB() := AXI4Buffer(),
    lmrom.node,
    sramNode,
    lsdram_axi := ysyx.AXI4Delayer()
  ).map(_ := xbar2)
  xbar2 := AXI4UserYanker(Some(1)) := AXI4Fragmenter() := xbar
  if (Config.hasChipLink) chiplinkNode.get := xbar
  xbar := cpu.masterNode

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    // generate delayed reset for cpu, since chiplink should finish reset
    // to initialize some async modules before accept any requests from cpu
    cpu.module.reset := SynchronizerShiftReg(reset.asBool, 10) || reset.asBool

    val fpga_io =
      if (Config.hasChipLink)
        Some(IO(chiselTypeOf(chipMaster.get.module.fpga_io)))
      else None

    // 自己加的，停仿真的
    val trap_valid = IO(Output(Bool()))
    val trap_pc = IO(Output(UInt(32.W)))
    trap_valid := cpu.module.trap_valid
    trap_pc := cpu.module.trap_pc
    // SDB debug的
    val debug_gpr_raddr = IO(Input(UInt(5.W)))
    val debug_gpr_rdata = IO(Output(UInt(32.W)))
    val debug_pc = IO(Output(UInt(32.W)))
    cpu.module.debug_gpr_raddr := debug_gpr_raddr
    debug_gpr_rdata := cpu.module.debug_gpr_rdata
    debug_pc := cpu.module.debug_pc
    val debug_instructions = IO(Output(UInt(32.W)))
    debug_instructions := cpu.module.debug_instructions
    // mtrace
    val debug_mtrace_valid = IO(Output(Bool()))
    val debug_mtrace_wen = IO(Output(Bool()))
    val debug_mtrace_addr = IO(Output(UInt(32.W)))
    val debug_mtrace_wdata = IO(Output(UInt(32.W)))
    val debug_mtrace_rdata = IO(Output(UInt(32.W)))
    val debug_mtrace_width = IO(Output(UInt(2.W)))
    debug_mtrace_valid := cpu.module.debug_mtrace_valid
    debug_mtrace_wen := cpu.module.debug_mtrace_wen
    debug_mtrace_addr := cpu.module.debug_mtrace_addr
    debug_mtrace_wdata := cpu.module.debug_mtrace_wdata
    debug_mtrace_rdata := cpu.module.debug_mtrace_rdata
    debug_mtrace_width := cpu.module.debug_mtrace_width
    // Access Fault
    val debug_access_fault = IO(Output(Bool()))
    debug_access_fault := cpu.module.debug_access_fault
    val debug_access_fault_resp = IO(Output(UInt(2.W)))
    debug_access_fault_resp := cpu.module.debug_access_fault_resp
    val debug_commit = IO(Output(Bool()))
    debug_commit := cpu.module.debug_commit
    val perf_ifu_fetch = IO(Output(Bool()))
    perf_ifu_fetch := cpu.module.perf_ifu_fetch
    val perf_exu_done = IO(Output(Bool()))
    perf_exu_done := cpu.module.perf_exu_done
    val perf_lsu_load = IO(Output(Bool()))
    perf_lsu_load := cpu.module.perf_lsu_load
    val perf_lsu_store = IO(Output(Bool()))
    perf_lsu_store := cpu.module.perf_lsu_store
    val perf_alu_op = IO(Output(Bool()))
    perf_alu_op := cpu.module.perf_alu_op
    val perf_mem_op = IO(Output(Bool()))
    perf_mem_op := cpu.module.perf_mem_op
    val perf_csr_op = IO(Output(Bool()))
    perf_csr_op := cpu.module.perf_csr_op
    val perf_branch_op = IO(Output(Bool()))
    perf_branch_op := cpu.module.perf_branch_op
    val perf_ifu_stall_pipeline = IO(Output(Bool()))
    perf_ifu_stall_pipeline := cpu.module.perf_ifu_stall_pipeline
    val perf_ifu_stall_axi = IO(Output(Bool()))
    perf_ifu_stall_axi := cpu.module.perf_ifu_stall_axi
    val perf_ifu_stall_redirect = IO(Output(Bool()))
    perf_ifu_stall_redirect := cpu.module.perf_ifu_stall_redirect
    val perf_execution_active = IO(Output(Bool()))
    perf_execution_active := cpu.module.perf_execution_active
    val perf_lsu_active = IO(Output(Bool()))
    perf_lsu_active := cpu.module.perf_lsu_active
    val perf_ifu_stall_ar = IO(Output(Bool()))
    perf_ifu_stall_ar := cpu.module.perf_ifu_stall_ar
    val perf_ifu_stall_r = IO(Output(Bool()))
    perf_ifu_stall_r := cpu.module.perf_ifu_stall_r
    val perf_ifu_stall_idle = IO(Output(Bool()))
    perf_ifu_stall_idle := cpu.module.perf_ifu_stall_idle
    val perf_exu_stall_lsu = IO(Output(Bool()))
    perf_exu_stall_lsu := cpu.module.perf_exu_stall_lsu
    val perf_lsu_load_active = IO(Output(Bool()))
    perf_lsu_load_active := cpu.module.perf_lsu_load_active
    val perf_lsu_store_active = IO(Output(Bool()))
    perf_lsu_store_active := cpu.module.perf_lsu_store_active
    val perf_lsu_stall_read_ar = IO(Output(Bool()))
    perf_lsu_stall_read_ar := cpu.module.perf_lsu_stall_read_ar
    val perf_lsu_stall_read_r = IO(Output(Bool()))
    perf_lsu_stall_read_r := cpu.module.perf_lsu_stall_read_r
    val perf_lsu_stall_write_req = IO(Output(Bool()))
    perf_lsu_stall_write_req := cpu.module.perf_lsu_stall_write_req
    val perf_lsu_stall_write_b = IO(Output(Bool()))
    perf_lsu_stall_write_b := cpu.module.perf_lsu_stall_write_b
    val perf_icache_hit = IO(Output(Bool()))
    perf_icache_hit := cpu.module.perf_icache_hit
    val perf_icache_miss = IO(Output(Bool()))
    perf_icache_miss := cpu.module.perf_icache_miss

    if (Config.hasChipLink) {
      // connect chiplink slave interface to crossbar
      (chipMaster.get.slave zip chiplinkNode.get.in) foreach {
        case (io, (bundle, _)) => io <> bundle
      }

      // connect chiplink dma interface to cpu
      cpu.module.slave <> chipMaster.get.master_mem(0)

      // expose chiplink fpga I/O interface as ports
      fpga_io.get <> chipMaster.get.module.fpga_io
    } else {
      cpu.module.slave := DontCare
    }

    // connect interrupt signal to cpu
    val intr_from_chipSlave = IO(Input(Bool()))
    cpu.module.interrupt := intr_from_chipSlave

    // expose slave I/O interface as ports
    val spi = IO(chiselTypeOf(lspi.module.spi_bundle))
    val uart = IO(chiselTypeOf(luart.module.uart))
    val psram = IO(chiselTypeOf(lpsram.module.qspi_bundle))
    val gpio = IO(chiselTypeOf(lgpio.module.gpio_bundle))
    val ps2 = IO(chiselTypeOf(lkeyboard.module.ps2_bundle))
    val vga = IO(chiselTypeOf(lvga.module.vga_bundle))
    uart <> luart.module.uart
    spi <> lspi.module.spi_bundle
    psram <> lpsram.module.qspi_bundle
    gpio <> lgpio.module.gpio_bundle
    ps2 <> lkeyboard.module.ps2_bundle
    vga <> lvga.module.vga_bundle
  }
}

class ysyxSoCFPGA(implicit p: Parameters) extends ChipLinkSlave

class ysyxSoCFull(implicit p: Parameters) extends LazyModule {
  val asic = LazyModule(new ysyxSoCASIC)
  ElaborationArtefacts.add("graphml", graphML)

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    val masic = asic.module

    if (Config.hasChipLink) {
      val fpga = LazyModule(new ysyxSoCFPGA)
      val mfpga = Module(fpga.module)
      masic.dontTouchPorts()

      masic.fpga_io.get.b2c <> mfpga.fpga_io.c2b
      mfpga.fpga_io.b2c <> masic.fpga_io.get.c2b

      (fpga.master_mem zip fpga.axi4MasterMemNode.in).map {
        case (io, (_, edge)) =>
          val mem = LazyModule(
            new SimAXIMem(
              edge,
              base = ChipLinkParam.mem.base,
              size = ChipLinkParam.mem.mask + 1
            )
          )
          Module(mem.module)
          mem.io_axi4.head <> io
      }

      fpga.master_mmio.map(_ := DontCare)
      fpga.slave.map(_ := DontCare)
    }

    masic.intr_from_chipSlave := false.B

    val flash = Module(new flash)
    flash.io <> masic.spi
    flash.io.ss := masic.spi.ss(0)
    // val bitrev = Module(new bitrev)
    val bitrev = Module(new bitrevChisel)
    bitrev.io <> masic.spi
    bitrev.io.ss := masic.spi.ss(7)
    masic.spi.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_ && _)

    // val psram = Module(new psram)
    val psram = Module(new psramChisel)
    psram.io <> masic.psram

    val externalPins = IO(new Bundle {
      val gpio = chiselTypeOf(masic.gpio)
      val ps2 = chiselTypeOf(masic.ps2)
      val vga = chiselTypeOf(masic.vga)
      val uart = chiselTypeOf(masic.uart)
    })
    externalPins.gpio <> masic.gpio
    externalPins.ps2 <> masic.ps2
    externalPins.vga <> masic.vga
    externalPins.uart <> masic.uart

    // 停仿真的
    val trap_valid = IO(Output(Bool()))
    val trap_pc = IO(Output(UInt(32.W)))
    trap_valid := masic.trap_valid
    trap_pc := masic.trap_pc
    // SDB debug
    val debug_gpr_raddr = IO(Input(UInt(5.W)))
    val debug_gpr_rdata = IO(Output(UInt(32.W)))
    val debug_pc = IO(Output(UInt(32.W)))
    masic.debug_gpr_raddr := debug_gpr_raddr
    debug_gpr_rdata := masic.debug_gpr_rdata
    debug_pc := masic.debug_pc
    val debug_instructions = IO(Output(UInt(32.W)))
    debug_instructions := masic.debug_instructions
    // mtrace
    val debug_mtrace_valid = IO(Output(Bool()))
    val debug_mtrace_wen = IO(Output(Bool()))
    val debug_mtrace_addr = IO(Output(UInt(32.W)))
    val debug_mtrace_wdata = IO(Output(UInt(32.W)))
    val debug_mtrace_rdata = IO(Output(UInt(32.W)))
    val debug_mtrace_width = IO(Output(UInt(2.W)))
    debug_mtrace_valid := masic.debug_mtrace_valid
    debug_mtrace_wen := masic.debug_mtrace_wen
    debug_mtrace_addr := masic.debug_mtrace_addr
    debug_mtrace_wdata := masic.debug_mtrace_wdata
    debug_mtrace_rdata := masic.debug_mtrace_rdata
    debug_mtrace_width := masic.debug_mtrace_width
    // Access Fault
    val debug_access_fault = IO(Output(Bool()))
    debug_access_fault := masic.debug_access_fault
    val debug_access_fault_resp = IO(Output(UInt(2.W)))
    debug_access_fault_resp := masic.debug_access_fault_resp
    // IPC
    val debug_commit = IO(Output(Bool()))
    debug_commit := masic.debug_commit
    val perf_ifu_fetch = IO(Output(Bool()))
    perf_ifu_fetch := masic.perf_ifu_fetch
    val perf_exu_done = IO(Output(Bool()))
    perf_exu_done := masic.perf_exu_done
    val perf_lsu_load = IO(Output(Bool()))
    perf_lsu_load := masic.perf_lsu_load
    val perf_lsu_store = IO(Output(Bool()))
    perf_lsu_store := masic.perf_lsu_store
    val perf_alu_op = IO(Output(Bool()))
    perf_alu_op := masic.perf_alu_op
    val perf_mem_op = IO(Output(Bool()))
    perf_mem_op := masic.perf_mem_op
    val perf_csr_op = IO(Output(Bool()))
    perf_csr_op := masic.perf_csr_op
    val perf_branch_op = IO(Output(Bool()))
    perf_branch_op := masic.perf_branch_op
    val perf_ifu_stall_pipeline = IO(Output(Bool()))
    perf_ifu_stall_pipeline := masic.perf_ifu_stall_pipeline
    val perf_ifu_stall_axi = IO(Output(Bool()))
    perf_ifu_stall_axi := masic.perf_ifu_stall_axi
    val perf_ifu_stall_redirect = IO(Output(Bool()))
    perf_ifu_stall_redirect := masic.perf_ifu_stall_redirect
    val perf_execution_active = IO(Output(Bool()))
    perf_execution_active := masic.perf_execution_active
    val perf_lsu_active = IO(Output(Bool()))
    perf_lsu_active := masic.perf_lsu_active
    val perf_ifu_stall_ar = IO(Output(Bool()))
    perf_ifu_stall_ar := masic.perf_ifu_stall_ar
    val perf_ifu_stall_r = IO(Output(Bool()))
    perf_ifu_stall_r := masic.perf_ifu_stall_r
    val perf_ifu_stall_idle = IO(Output(Bool()))
    perf_ifu_stall_idle := masic.perf_ifu_stall_idle
    val perf_exu_stall_lsu = IO(Output(Bool()))
    perf_exu_stall_lsu := masic.perf_exu_stall_lsu
    val perf_lsu_load_active = IO(Output(Bool()))
    perf_lsu_load_active := masic.perf_lsu_load_active
    val perf_lsu_store_active = IO(Output(Bool()))
    perf_lsu_store_active := masic.perf_lsu_store_active
    val perf_lsu_stall_read_ar = IO(Output(Bool()))
    perf_lsu_stall_read_ar := masic.perf_lsu_stall_read_ar
    val perf_lsu_stall_read_r = IO(Output(Bool()))
    perf_lsu_stall_read_r := masic.perf_lsu_stall_read_r
    val perf_lsu_stall_write_req = IO(Output(Bool()))
    perf_lsu_stall_write_req := masic.perf_lsu_stall_write_req
    val perf_lsu_stall_write_b = IO(Output(Bool()))
    perf_lsu_stall_write_b := masic.perf_lsu_stall_write_b
    val perf_icache_hit = IO(Output(Bool()))
    perf_icache_hit := masic.perf_icache_hit
    val perf_icache_miss = IO(Output(Bool()))
    perf_icache_miss := masic.perf_icache_miss
  }
}
