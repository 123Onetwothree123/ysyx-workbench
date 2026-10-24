package ysyx_26030103

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

class riscv32e_npc_AXIRAM(val ramBytes: Long = 64L * 1024L * 1024L) extends Module {
  require(ramBytes > 0, "direct-NPC RAM 大小必须为正")
  require((ramBytes & (ramBytes - 1)) == 0, "direct-NPC RAM 大小必须是 2 的幂")
  require(ramBytes % 4 == 0, "direct-NPC RAM 大小必须是 4 字节的倍数")
  val io = IO(new Bundle {
    val axi = Flipped(new ysyx_26030103_AXI5IO(32, 32, 4))
    // direct-NPC 在构造 DUT 后由 C++ 将命令行镜像写入 RAM。
    // 这使 RTL 和 DiffTest/调试器共用同一份 FlashMemory，
    // 不再依赖当前工作目录下可能过期的 program.hex。
    // 地址端口固定 32 位，由 wordIndexBits 截取实际深度；这样 64/128 MiB
    // 共用同一个 SimTop 端口，不需要为每种 RAM 大小重写顶层。
    val hostWriteValid = Input(Bool())
    val hostWriteIndex = Input(UInt(32.W))
    val hostWriteData = Input(UInt(32.W))
    // 仿真 stdin/测试平台注入的 UART 接收字节。rxReady 为高表示 FIFO
    // 还能接收；上层不注入时保持 valid=0 即可，不会阻塞仿真。
    val uartRxValid = Input(Bool())
    val uartRxData = Input(UInt(8.W))
    val uartRxReady = Output(Bool())
    val uartIrq = Output(Bool())
  })

  // RAM 大小由 Kconfig 的 NPC_RAM_SIZE 决定（默认 64MiB，Linux U-Boot
  // 配置为 128MiB）。深度与地址位宽都从同一个字节数推导，避免 PMA、
  // AXI 窗口和 SyncReadMem 索引各写一份常量。
  val depth = (ramBytes / 4).toInt
  val wordIndexBits = log2Ceil(depth)
  val mem = SyncReadMem(depth, UInt(32.W))
  // 16550 兼容的 UART：TX 保持 printf 行为，RX 由仿真顶层注入。
  val uart = Module(new ysyx_26030103_UART16550())

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
  val RamBase = "h80000000".U(32.W)
  val RamLast = (0x80000000L + ramBytes - 1L).U(32.W)
  val UartBase = "h10000000".U(32.W)
  val UartLast = "h1000001f".U(32.W)

  // UART 窗口只有 8 个字节寄存器。与 RAM 一样，必须保证整个访问都落在
  // 窗口内，避免跨窗口访问被错误地当成 UART 寄存器。
  def IsUARTBeat(address: UInt, size: UInt): Bool = {
    val lastStart = MuxLookup(size, 0.U(32.W))(
      Seq(
        0.U -> UartLast,
        1.U -> (UartLast - 1.U),
        2.U -> (UartLast - 3.U)
      )
    )
    size <= 2.U && address >= UartBase && address <= lastStart
  }

  // 只有 AxSIZE 选中的每个字节都位于当前 RAM 窗口内时，该拍才属于
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
  val readIsUart = RegInit(false.B)
  val readUartData = RegInit(0.U(8.W))
  val readStep = MuxLookup(arSize, 0.U(32.W))(
    Seq(
      0.U -> 1.U(32.W),
      1.U -> 2.U(32.W),
      2.U -> 4.U(32.W)
    )
  )
  val readAddressIsRAM = IsRAMBeat(arAddr, arSize)
  val readAddressIsUART = IsUARTBeat(arAddr, arSize)
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
  val writeAddressIsUART = IsUARTBeat(awAddr, awSize)
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
  // 必须和 hostWriteIndex 一样使用完整的 wordIndexBits；此前写成 16 位，
  // 64MiB RAM 的 CPU store 会在 256KiB 处回绕，高地址栈写入全部丢失。
  val cpuWriteIndex = WireDefault(0.U(wordIndexBits.W))
  val cpuWriteData = WireDefault(0.U(32.W))
  // 主机地址端口固定 32 位，只取实际深度需要的低位，避免 128MiB 时
  // 高 bit 丢失（64MiB 时多余的高位自然被忽略）。
  val hostWriteIndex = io.hostWriteIndex(wordIndexBits - 1, 0)
  when(io.hostWriteValid || cpuWriteValid) {
    mem.write(
      Mux(io.hostWriteValid, hostWriteIndex, cpuWriteIndex),
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

  // UART 寄存器是单拍字节访问。读请求在 AR 握手当拍采样，读 RBR 的
  // 弹出副作用也只发生一次；写请求在 sWriteCommit 产生 THR/寄存器写。
  val uartArFire = arFire &&
    IsUARTBeat(io.axi.AR.ARADDR, io.axi.AR.ARSIZE) &&
    io.axi.AR.ARBURST <= 1.U && io.axi.AR.ARLEN === 0.U
  uart.io.rxValid := io.uartRxValid
  uart.io.rxData := io.uartRxData
  uart.io.rdAddr := io.axi.AR.ARADDR(4, 0)
  uart.io.rdEn := uartArFire
  uart.io.wrAddr := awAddr(4, 0)
  uart.io.wrEn := state === sWriteCommit && !writeConfigError &&
    writeAddressIsUART && wStrb =/= 0.U
  // AXI 字节通道由地址低两位选择；xv6 的 UART 寄存器都是单字节访问。
  uart.io.wrData := (wData >> (awAddr(1, 0) << 3))(7, 0)
  io.uartRxReady := uart.io.rxReady
  io.uartIrq := uart.io.irq

  when(awFire) {
    awAddr := io.axi.AW.AWADDR
    awId := io.axi.AW.AWID
    awLen := io.axi.AW.AWLEN
    awSize := io.axi.AW.AWSIZE
    awBurst := io.axi.AW.AWBURST
    writeBeat := 0.U
    writeConfigError := io.axi.AW.AWSIZE > 2.U || io.axi.AW.AWBURST > 1.U ||
      (IsUARTBeat(io.axi.AW.AWADDR, io.axi.AW.AWSIZE) &&
        io.axi.AW.AWLEN =/= 0.U)
    writeResp := Mux(
      io.axi.AW.AWSIZE > 2.U || io.axi.AW.AWBURST > 1.U ||
        (IsUARTBeat(io.axi.AW.AWADDR, io.axi.AW.AWSIZE) &&
          io.axi.AW.AWLEN =/= 0.U),
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
  // UART 是字节寄存器，按 16550 常见做法复制到四个字节通道；LSU 会按
  // 访问地址选择正确通道，因此字/半字/字节读都能得到同一寄存器值。
  io.axi.R.RDATA := Mux(
    readIsUart,
    Cat(readUartData, readUartData, readUartData, readUartData),
    Mux(readResp === OKAY, rd, 0.U)
  )
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
        readIsUart := uartArFire
        readUartData := uart.io.rdData
        // UART 不支持突发读；突发请求按配置错误安全终止，且不产生
        // RBR 弹出等副作用。
        readConfigError := io.axi.AR.ARSIZE > 2.U || io.axi.AR.ARBURST > 1.U ||
          (IsUARTBeat(io.axi.AR.ARADDR, io.axi.AR.ARSIZE) &&
            io.axi.AR.ARLEN =/= 0.U)
        state := sReadWait
      }.elsewhen(haveAW && haveW) {
        state := sWriteRead
      }
    }
    is(sReadWait) {
      readResp := Mux(
        readConfigError,
        SLVERR,
        Mux(readAddressIsRAM || readAddressIsUART, OKAY, DECERR)
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
          // UART 写由 uart.io.wrEn 在 sWriteCommit 产生副作用（THR 打印或
          // 控制寄存器更新），这里不写 RAM。
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
