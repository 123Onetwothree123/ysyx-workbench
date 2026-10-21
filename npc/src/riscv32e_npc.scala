package ysyx_26030103

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.infra._

class riscv32e_npc_AXIRAM extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new ysyx_26030103_AXI5IO(32, 32, 4))
    // direct-NPC 在构造 DUT 后由 C++ 将命令行镜像写入 RAM。
    // 这使 RTL 和 DiffTest/调试器共用同一份 FlashMemory，
    // 不再依赖当前工作目录下可能过期的 program.hex。
    val hostWriteValid = Input(Bool())
    val hostWriteIndex = Input(UInt(24.W))
    val hostWriteData = Input(UInt(32.W))
  })

  // direct-NPC 使用 64MiB RAM；足以容纳 Nanos-lite 的常驻数据，
  // 同时避免仿真初始化 128MiB 主机内存的过长启动时间。
  val depth = 64 * 1024 * 1024 / 4
  val wordIndexBits = 24
  val mem = SyncReadMem(depth, UInt(32.W))

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
  val RamBase = "h80000000".U(32.W)
  val RamLast = "h83ffffff".U(32.W)
  val UARTAddress = "h10000000".U(32.W)

  // 只有 AxSIZE 选中的每个字节都位于 64MiB 仿真内存窗口内时，该拍才属于
  // RAM。尤其要避免窗口外地址通过截断后的 SyncReadMem 索引发生回绕。
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
  val readWordIndex = ((arAddr - RamBase) >> 2)(wordIndexBits - 1, 0)
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

  // SyncReadMem 会在读请求后一拍返回数据。因此，部分写必须先读取旧字，再在
  // 后续状态中合并并写回；如果在请求当拍使用读结果，就会合并陈旧或未定义的
  // 数据。
  val writeAddressIsRAM = IsRAMBeat(awAddr, awSize)
  val writeAddressIsUART = awAddr === UARTAddress
  val writeWordIndex = ((awAddr - RamBase) >> 2)(wordIndexBits - 1, 0)
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

  // RAM 只保留一个写端口。主机初始化发生在 reset 期间，
  // 并且优先于 AXI 写，避免为 SyncReadMem 推导额外写端口。
  val cpuWriteValid = WireDefault(false.B)
  val cpuWriteIndex = WireDefault(0.U(16.W))
  val cpuWriteData = WireDefault(0.U(32.W))
  when(io.hostWriteValid || cpuWriteValid) {
    mem.write(
      Mux(io.hostWriteValid, io.hostWriteIndex, cpuWriteIndex),
      Mux(io.hostWriteValid, io.hostWriteData, cpuWriteData)
    )
  }

  // 尚未收集完整的写事务优先于读事务。AW 与 W 独立接收，可以按任意顺序到达。
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
      // 发起同步读；writeOld 会在 sWriteCommit 状态中有效。
      state := sWriteCommit
    }
    is(sWriteCommit) {
      when(!writeConfigError) {
        when(writeAddressIsRAM) {
          cpuWriteValid := true.B
          cpuWriteIndex := writeWordIndex
          cpuWriteData := writeMerged
        }.elsewhen(writeAddressIsUART) {
          // UART 是只有副作用的目标，并且刻意与 RAM 窗口分离。只有低字节使能时
          // 才会输出字符。
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
          writeResp := SLVERR // WLAST 过早到达
        }
        state := sWriteResp
      }.elsewhen(expectedLastBeat) {
        // 已收到 AWLEN 指定的拍数但仍未见 WLAST。继续排空到 WLAST，避免上游
        // 写数据通道永久阻塞，随后报告 SLVERR。
        writeResp := SLVERR
        wPending := false.B
        state := sWriteDrain
      }.otherwise {
        writeBeat := writeBeat + 1.U
        when(awBurst === 1.U) { // INCR 递增地址；FIXED 保持地址不变
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
