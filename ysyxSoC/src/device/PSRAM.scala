package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class QSPIIO extends Bundle {
  val sck = Output(Bool())
  val ce_n = Output(Bool())
  val dio = Analog(4.W)
}

class psram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val qspi = new QSPIIO
  })
}

class psram extends BlackBox {
  val io = IO(Flipped(new QSPIIO))
}

class psramChisel extends RawModule {
  val io = IO(Flipped(new QSPIIO))
  val output = Wire(UInt(4.W))
  val en = Wire(Bool())
  val input = TriStateInBuf(io.dio, output, en)

  val idle :: rx_cmd :: rx_addr :: dummy :: tx_data :: rx_data :: Nil = Enum(6)
  val sck_clock = io.sck.asClock
  val ce_reset = io.ce_n.asAsyncReset

  val state = withClockAndReset(sck_clock, ce_reset) {
    RegInit(rx_cmd)
  }
  val counter = withClockAndReset(sck_clock, ce_reset) {
    RegInit(0.U(4.W))
  }
  val cmd = withClockAndReset(sck_clock, ce_reset) {
    RegInit(0.U(8.W))
  }
  val addr = withClockAndReset(sck_clock, ce_reset) {
    RegInit(0.U(24.W))
  }
  val wdata = withClockAndReset(sck_clock, ce_reset) {
    RegInit(0.U(32.W))
  }
  val QPIMode = withClockAndReset(sck_clock, false.B.asAsyncReset) {
    RegInit(false.B)
  }
  val MemoryAddress = addr(21, 0)

  val memory = withClock(sck_clock) { Mem(1 << 22, UInt(8.W)) }
  val rdata = withClock(sck_clock) {
    Cat(
      memory.read(MemoryAddress + 3.U),
      memory.read(MemoryAddress + 2.U),
      memory.read(MemoryAddress + 1.U),
      memory.read(MemoryAddress + 0.U)
    )
  }
  withClockAndReset(sck_clock, ce_reset) {
    switch(state) {
      is(idle) {
        state := idle
      }
      is(rx_cmd) {
        val cmd_end = Mux(QPIMode, counter === 1.U, counter === 7.U)
        when(cmd_end) {
          counter := 0.U
          when(!QPIMode && Cat(cmd(6,0), input(0)) === "h35".U) {
            QPIMode := true.B
            state := idle
            printf(cf"PSRAM-CHISEL: QPI mode enabled (received 35h)\n")
          }.otherwise {
            state := rx_addr
          }
        }.otherwise {
          counter := counter + 1.U
        }
      }
      is(rx_addr) {
        when(counter === 5.U) {
          counter := 0.U
          printf(cf"PSRAM-CHISEL: cmd=0x${Hexadecimal(cmd)} QPI=${QPIMode} addr=0x${Hexadecimal(addr)}\n")
          state := Mux(
            cmd === "heb".U,
            dummy,
            Mux(cmd === "h38".U, rx_data, idle)
          )
        }.otherwise {
          counter := counter + 1.U
        }
      }
      is(dummy) {
        when(counter === 5.U) {
          counter := 0.U
          state := tx_data
        }.otherwise {
          counter := counter + 1.U
        }
      }
      is(tx_data) {
        when(counter === 7.U) {
          counter := 0.U
          state := idle
        }.otherwise {
          counter := counter + 1.U
        }
      }
      is(rx_data) {
        val new_wdata = Cat(wdata(27, 0), input)
        when(counter === 7.U) {
          counter := 0.U
          state := idle
          memory.write(MemoryAddress, new_wdata(31, 24))
          memory.write(MemoryAddress + 1.U, new_wdata(23, 16))
          memory.write(MemoryAddress + 2.U, new_wdata(15, 8))
          memory.write(MemoryAddress + 3.U, new_wdata(7, 0))
        }.otherwise {
          counter := counter + 1.U
          when(counter === 1.U) { memory.write(MemoryAddress, new_wdata(7, 0)) }
          when(counter === 3.U) {
            memory.write(MemoryAddress + 1.U, new_wdata(7, 0))
          }
          when(counter === 5.U) {
            memory.write(MemoryAddress + 2.U, new_wdata(7, 0))
          }
        }
      }
    }
    when(state === rx_cmd) {
      when(QPIMode) {
        when(counter === 0.U) { cmd := Cat(input, 0.U(4.W)) }
          .elsewhen(counter === 1.U) {
            cmd := Cat(cmd(7, 4), input)
            printf(cf"PSRAM-CHISEL: QPI rx_cmd received nibbles => cmd=0x${Hexadecimal(cmd(7,4))}${Hexadecimal(input)}\n")
          }
      }.otherwise {
        cmd := Cat(cmd(6, 0), input(0))
      }
    }
    when(state === rx_addr) {
      addr := Cat(addr(19, 0), input)
    }
    when(state === rx_data) {
      wdata := Cat(wdata(27, 0), input)
    }
  }
  output := Mux(
    state === tx_data,
    MuxLookup(counter, 0.U)(
      Seq(
        0.U -> rdata(7, 4),
        1.U -> rdata(3, 0),
        2.U -> rdata(15, 12),
        3.U -> rdata(11, 8),
        4.U -> rdata(23, 20),
        5.U -> rdata(19, 16),
        6.U -> rdata(31, 28),
        7.U -> rdata(27, 24)
      )
    ),
    0.U
  )
  en := state === tx_data
}

class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val qspi_bundle = IO(new QSPIIO)

    val mpsram = Module(new psram_top_apb)
    mpsram.io.clock := clock
    mpsram.io.reset := reset
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}
