package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

object APBConfig {
  def apbXlen: Int = {
    val value = Config.apbXlen
    require(value == 32 || value == 64, s"APB_XLEN 必须是 32 或 64，当前值 $value")
    value
  }

  def apbBytes: Int = apbXlen / 8

  def peripheralXlen(name: String): Int = checkXlen(Config.peripheralXlen(name), name)

  def peripheralBytes(name: String): Int = peripheralXlen(name) / 8

  def checkXlen(value: Int, name: String): Int = {
    require(value == 32 || value == 64, s"$name 的 APB 宽度必须是 32 或 64，当前值 $value")
    value
  }
}

class APBDelayerIO(val addrBits: Int = 32, val dataBits: Int = 32) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits, dataBits)))
  val out = new APBBundle(APBBundleParameters(addrBits, dataBits))
}

class apb_delayer(val addrBits: Int = 32, val dataBits: Int = 32) extends BlackBox {
  // perip/amba/apb_delayer.v 仍是 32 位实现，64 位请用 APBDelayerChisel
  val io = IO(new APBDelayerIO(addrBits, dataBits))
}

class APBDelayerChisel(
    val addrBits: Int = 32,
    val dataBits: Int = Config.apbXlen,
    val CPU_MHZ: Int = Config.apbCpuFreqMhz,
    val DEVICE_MHZ: Int = Config.apbDeviceFreqMhz,
    val S: Int = Config.apbDelayScale
) extends Module {
  require(CPU_MHZ > 0, "APB CPU frequency must be positive")
  require(DEVICE_MHZ > 0, "APB device frequency must be positive")
  require(S > 0, "APB delay scale must be positive")
  require(dataBits == 32 || dataBits == 64, s"APB dataBits 必须是 32 或 64，当前值 $dataBits")
  val io = IO(new APBDelayerIO(addrBits, dataBits))
  val RTS = CPU_MHZ * S / DEVICE_MHZ
  val AMT = if (RTS > S) {
    RTS - S
  } else {
    0
  }
  if (AMT == 0) {
    io.out <> io.in
  } else {
    val CounterWidth = 24
    val state_idle :: state_count :: state_delay :: Nil = Enum(3)
    val state = RegInit(state_idle)
    val counter = RegInit(0.S(CounterWidth.W))
    val rdata = RegInit(0.U(dataBits.W))
    val slverr = RegInit(false.B)
    // 延迟期间撤销psel/penable，避免从设备把同一事务重复执行
    io.out.psel := io.in.psel && (state =/= state_delay)
    io.out.penable := io.in.penable && (state =/= state_delay)
    io.out.pwrite := io.in.pwrite
    io.out.paddr := io.in.paddr
    io.out.pprot := io.in.pprot
    io.out.pwdata := io.in.pwdata
    io.out.pstrb := io.in.pstrb
    io.out.pauser := io.in.pauser
    io.in.pready := false.B
    io.in.prdata := 0.U(dataBits.W)
    io.in.pslverr := false.B
    io.in.pduser := io.out.pduser
    switch(state) {
      is(state_idle) {
        io.in.pready := io.out.pready
        io.in.prdata := io.out.prdata
        io.in.pslverr := io.out.pslverr
        when(io.in.psel && !io.in.penable) {
          state := state_count
          counter := AMT.S(CounterWidth.W)
        }
      }
      is(state_count) {
        when(!io.in.psel) {
          state := state_idle
        }.elsewhen(io.out.pready && io.in.penable) {
          state := state_delay
          rdata := io.out.prdata
          slverr := io.out.pslverr
        }.otherwise {
          counter := counter + AMT.S(CounterWidth.W)
        }
      }
      is(state_delay) {
        io.in.prdata := rdata
        io.in.pslverr := slverr
        when(counter <= 0.S) {
          state := state_idle
          io.in.pready := true.B
        }.otherwise {
          counter := counter - S.S
        }
      }
    }
  }
}

class APBDelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = APBIdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      require(edgeIn.bundle.dataBits == edgeOut.bundle.dataBits,
        "APBDelayer 两侧 APB 数据宽度必须一致")
      val delayer = Module(new APBDelayerChisel(in.paddr.getWidth, in.pwdata.getWidth))
      delayer.io.clock := clock
      delayer.io.reset := reset
      delayer.io.in <> in
      out <> delayer.io.out
    }
  }
}

object APBDelayer {
  def apply()(implicit p: Parameters): APBNode = {
    val apbdelay = LazyModule(new APBDelayerWrapper)
    apbdelay.node
  }
}

/** APB 32/64 位转换。
  *
  * 只做总线宽度适配，不改变外设寄存器语义。
  * 写操作保留真实字节地址和 PSTRB lane，部分掩码逐字节下发。
  * 读操作对寄存器类外设只读地址所在半区，另一半年返回 0；
  * readFullWidth=true 用于内存类外设，64 位读会读两个 32 位半区。
  */
class APBWidthConverterIO(val addrBits: Int, val upDataBits: Int, val downDataBits: Int)
    extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits, upDataBits)))
  val out = new APBBundle(APBBundleParameters(addrBits, downDataBits))
}

class APBWidthConverter(
    val addrBits: Int,
    val upDataBits: Int,
    val downDataBits: Int,
    val readFullWidth: Boolean = false
)(implicit p: Parameters)
    extends Module {
  require(upDataBits == 32 || upDataBits == 64,
    s"APB 转换上游宽度必须是 32 或 64，当前值 $upDataBits")
  require(downDataBits == 32 || downDataBits == 64,
    s"APB 转换下游宽度必须是 32 或 64，当前值 $downDataBits")
  require(addrBits >= 3, s"APB 地址宽度至少 3 位，当前值 $addrBits")

  val io = IO(new APBWidthConverterIO(addrBits, upDataBits, downDataBits))

  private val upBytes = upDataBits / 8
  private val downBytes = downDataBits / 8

  if (upDataBits == downDataBits) {
    io.out <> io.in
  } else if (upDataBits > downDataBits) {
    downsize()
  } else {
    upsize()
  }

  private def downsize(): Unit = {
    val idle :: setup :: access :: done :: Nil = Enum(4)
    val Idle = idle
    val Setup = setup
    val Access = access
    val Done = done
    val State = RegInit(Idle)
    val IsWrite = RegInit(false.B)
    val Address = RegInit(0.U(addrBits.W))
    val WriteData = RegInit(0.U(upDataBits.W))
    val RemainingStrobe = RegInit(0.U(upBytes.W))
    val RemainingReadHalves = RegInit(0.U(2.W))
    val ReadData = RegInit(0.U(upDataBits.W))
    val ResponseError = RegInit(false.B)

    val LowStrobe = RemainingStrobe(downBytes - 1, 0)
    val HighStrobe = RemainingStrobe(upBytes - 1, downBytes)
    val UseHigh = !LowStrobe.orR
    val ActiveStrobe = Mux(UseHigh, HighStrobe, LowStrobe)
    val FullWord = ActiveStrobe.andR
    val ByteLane = PriorityEncoder(ActiveStrobe)
    val HalfBase = (Address >> log2Ceil(upBytes)) << log2Ceil(upBytes)
    val WriteAddress =
      (HalfBase + Mux(UseHigh, downBytes.U, 0.U) +
        Mux(FullWord, 0.U, ByteLane))(addrBits - 1, 0)
    val WriteStrobe =
      Mux(FullWord, ActiveStrobe,
        (1.U(downBytes.W) << ByteLane)(downBytes - 1, 0))
    val WriteDataWord =
      Mux(UseHigh, WriteData(upDataBits - 1, downDataBits),
        WriteData(downDataBits - 1, 0))
    val FullMask = (((BigInt(1) << downBytes) - 1).U(upBytes.W) <<
      Mux(UseHigh, downBytes.U, 0.U))(upBytes - 1, 0)
    val OneByteMask =
      (1.U(upBytes.W) << (UseHigh ## ByteLane))(upBytes - 1, 0)
    val ClearMask = Mux(FullWord, FullMask, OneByteMask)

    val ReadHalfSelect = Mux(RemainingReadHalves(0), 0.U(1.W), 1.U(1.W))
    val ReadAddress =
      if (readFullWidth) {
        (HalfBase + (ReadHalfSelect << log2Ceil(downBytes)))(addrBits - 1, 0)
      } else {
        Address
      }
    val ReadClearMask = (1.U(2.W) << ReadHalfSelect)(1, 0)

    io.out.psel := State === Setup || State === Access
    io.out.penable := State === Access
    io.out.pwrite := IsWrite
    io.out.paddr := Mux(IsWrite, WriteAddress, ReadAddress)
    io.out.pprot := APBParameters.PROT_DEFAULT
    io.out.pwdata := Mux(IsWrite, WriteDataWord, 0.U(downDataBits.W))
    io.out.pstrb := Mux(IsWrite, WriteStrobe, 0.U(downBytes.W))
    io.out.pauser := io.in.pauser

    io.in.pready := State === Done
    io.in.prdata := ReadData
    io.in.pslverr := ResponseError
    io.in.pduser := io.out.pduser

    switch(State) {
      is(Idle) {
        when(io.in.psel && !io.in.penable) {
          IsWrite := io.in.pwrite
          Address := io.in.paddr
          WriteData := io.in.pwdata
          RemainingStrobe := Mux(io.in.pwrite, io.in.pstrb, 0.U)
          RemainingReadHalves :=
            (if (readFullWidth) 3.U
             else Mux(io.in.paddr(2), 2.U(2.W), 1.U(2.W)))
          ReadData := 0.U
          ResponseError := false.B
          State := Mux(io.in.pwrite && io.in.pstrb === 0.U, Done, Setup)
        }
      }
      is(Setup) {
        State := Access
      }
      is(Access) {
        when(io.out.pready) {
          ResponseError := ResponseError || io.out.pslverr
          when(IsWrite) {
            val NextStrobe = RemainingStrobe & ~ClearMask
            RemainingStrobe := NextStrobe
            State := Mux(NextStrobe === 0.U, Done, Setup)
          }.otherwise {
            val NextHalves = RemainingReadHalves & ~ReadClearMask
            val HalfData =
              Mux(ReadHalfSelect === 0.U, io.out.prdata.pad(upDataBits),
                (io.out.prdata.pad(upDataBits) << downDataBits)(upDataBits - 1, 0))
            ReadData := ReadData | HalfData
            RemainingReadHalves := NextHalves
            State := Mux(NextHalves === 0.U, Done, Setup)
          }
        }
      }
      is(Done) {
        when(!io.in.psel) {
          State := Idle
        }
      }
    }
  }

  private def upsize(): Unit = {
    val idle :: setup :: access :: done :: Nil = Enum(4)
    val Idle = idle
    val Setup = setup
    val Access = access
    val Done = done
    val State = RegInit(Idle)
    val IsWrite = RegInit(false.B)
    val Address = RegInit(0.U(addrBits.W))
    val WriteData = RegInit(0.U(upDataBits.W))
    val WriteStrobe = RegInit(0.U(upBytes.W))
    val ResponseError = RegInit(false.B)

    val LaneHigh = Address(2)
    val DataShift = Mux(LaneHigh, downDataBits.U, 0.U)
    val StrobeShift = Mux(LaneHigh, downBytes.U, 0.U)
    val DownWriteData =
      (WriteData.pad(downDataBits) << DataShift)(downDataBits - 1, 0)
    val DownWriteStrobe =
      (WriteStrobe.pad(downBytes) << StrobeShift)(downBytes - 1, 0)
    val DownReadData =
      Mux(LaneHigh, io.out.prdata(downDataBits - 1, downDataBits / 2),
        io.out.prdata(downDataBits / 2 - 1, 0))

    io.out.psel := State === Setup || State === Access
    io.out.penable := State === Access
    io.out.pwrite := IsWrite
    io.out.paddr := Address
    io.out.pprot := APBParameters.PROT_DEFAULT
    io.out.pwdata := Mux(IsWrite, DownWriteData, 0.U(downDataBits.W))
    io.out.pstrb := Mux(IsWrite, DownWriteStrobe, 0.U(downBytes.W))
    io.out.pauser := io.in.pauser

    io.in.pready := State === Done
    io.in.prdata := DownReadData
    io.in.pslverr := ResponseError
    io.in.pduser := io.out.pduser

    switch(State) {
      is(Idle) {
        when(io.in.psel && !io.in.penable) {
          IsWrite := io.in.pwrite
          Address := io.in.paddr
          WriteData := io.in.pwdata
          WriteStrobe := io.in.pstrb
          ResponseError := false.B
          State := Mux(io.in.pwrite && io.in.pstrb === 0.U, Done, Setup)
        }
      }
      is(Setup) {
        State := Access
      }
      is(Access) {
        when(io.out.pready) {
          ResponseError := ResponseError || io.out.pslverr
          State := Done
        }
      }
      is(Done) {
        when(!io.in.psel) {
          State := Idle
        }
      }
    }
  }
}
