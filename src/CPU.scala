package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(
    addrBits = 32,
    dataBits = 32,
    idBits = ChipLinkParam.idBits
  )
}

class ysyx_26030103 extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val io_interrupt = Input(Bool())
    val io_master = AXI4Bundle(CPUAXI4BundleParameters())
    val io_slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
    // 加的接口，本来以为OSOC代码只要改一个学号就可以的，结果发现居然还得改，因为OSOC的文档讲了要把这个SOC作为顶层模块
    val io_trap_valid = Output(Bool())
    val io_trap_pc = Output(UInt(32.W))
    // sdb
    val io_debug_gpr_raddr = Input(UInt(5.W))
    val io_debug_gpr_rdata = Output(UInt(32.W))
    val io_debug_pc = Output(UInt(32.W))
    val io_debug_instructions = Output(UInt(32.W))
    // mtrace
    val io_debug_mtrace_valid = Output(Bool())
    val io_debug_mtrace_wen   = Output(Bool())
    val io_debug_mtrace_addr  = Output(UInt(32.W))
    val io_debug_mtrace_wdata = Output(UInt(32.W))
    val io_debug_mtrace_rdata = Output(UInt(32.W))
    val io_debug_mtrace_width = Output(UInt(2.W))
    // Access Fault
    val io_debug_access_fault = Output(Bool())
    val io_debug_access_fault_resp = Output(UInt(2.W))
    val io_debug_commit = Output(Bool())
  })
}

class CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {
  val masterNode = AXI4MasterNode(
    p(ExtIn)
      .map(params =>
        AXI4MasterPortParameters(
          masters = Seq(
            AXI4MasterParameters(name = "cpu", id = IdRange(0, 1 << idBits))
          )
        )
      )
      .toSeq
  )
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (master, _) = masterNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters())))

    val cpu = Module(new ysyx_26030103)
    cpu.io.clock := clock
    cpu.io.reset := reset
    cpu.io.io_interrupt := interrupt
    cpu.io.io_slave <> slave
    master <> cpu.io.io_master

    // 新加的，为了实现仿真停止的，和上面注释一样，接上去的
    val trap_valid = IO(Output(Bool()))
    val trap_pc = IO(Output(UInt(32.W)))
    trap_valid := cpu.io.io_trap_valid
    trap_pc := cpu.io.io_trap_pc
    // sdb
    val debug_gpr_raddr = IO(Input(UInt(5.W)))
    val debug_gpr_rdata = IO(Output(UInt(32.W)))
    val debug_pc = IO(Output(UInt(32.W)))

    cpu.io.io_debug_gpr_raddr := debug_gpr_raddr
    debug_gpr_rdata := cpu.io.io_debug_gpr_rdata
    debug_pc := cpu.io.io_debug_pc
    val debug_instructions = IO(Output(UInt(32.W)))
    debug_instructions := cpu.io.io_debug_instructions
    // mtrace
    val debug_mtrace_valid = IO(Output(Bool()))
    val debug_mtrace_wen   = IO(Output(Bool()))
    val debug_mtrace_addr  = IO(Output(UInt(32.W)))
    val debug_mtrace_wdata = IO(Output(UInt(32.W)))
    val debug_mtrace_rdata = IO(Output(UInt(32.W)))
    val debug_mtrace_width = IO(Output(UInt(2.W)))
    debug_mtrace_valid := cpu.io.io_debug_mtrace_valid
    debug_mtrace_wen   := cpu.io.io_debug_mtrace_wen
    debug_mtrace_addr  := cpu.io.io_debug_mtrace_addr
    debug_mtrace_wdata := cpu.io.io_debug_mtrace_wdata
    debug_mtrace_rdata := cpu.io.io_debug_mtrace_rdata
    debug_mtrace_width := cpu.io.io_debug_mtrace_width
    // Access Fault
    val debug_access_fault = IO(Output(Bool()))
    debug_access_fault := cpu.io.io_debug_access_fault
    val debug_access_fault_resp = IO(Output(UInt(2.W)))
    debug_access_fault_resp := cpu.io.io_debug_access_fault_resp
    val debug_commit = IO(Output(Bool()))
    debug_commit := cpu.io.io_debug_commit
  }
}
