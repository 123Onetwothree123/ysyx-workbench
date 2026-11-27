package ysyx_26030103

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

class riscv32e_npc_AXIRAM(
    val RAMBytes: Long = ysyx_26030103_KconfigDefaults.NpcRamSize,
    val SOCXLEN: Int = 32,
    val UARTBase: Long = ysyx_26030103_PMAOptions.Default.UARTBase,
    val RAMBase: Long = ysyx_26030103_PMAOptions.Default.NpcRAMBase
) extends Module {
  require(RAMBytes > 0, "direct-NPC RAM 大小必须为正")
  require(SOCXLEN == 32 || SOCXLEN == 64,
    s"riscv32e_npc_AXIRAM 只支持 32/64 位 AXI，收到 SOCXLEN=$SOCXLEN")
  // 每个RAM word的字节数随SoC数据宽度变化；64位时最后一个word
  // 必须完整，因此RAM大小也要能被8整除
  val bytesPerWord = SOCXLEN / 8
  val wordBytesLog2 = log2Ceil(bytesPerWord)
  require(SOCXLEN != 64 || RAMBytes % 8 == 0,
    "direct-NPC 64 位 RAM 大小必须是 8 字节的倍数")
  ysyx_26030103_PMAOptions.ValidateNpcRam(
    RAMBase,
    RAMBytes,
    "direct-NPC RAM 大小必须是 2 的幂",
    "direct-NPC RAM 大小必须是 4 字节的倍数",
    "direct-NPC RAM 基地址必须非负且按 4 字节对齐",
    "direct-NPC RAM 窗口超出 32 位地址空间"
  )
  require(UARTBase % 8 == 0, "direct-NPC UART 基地址必须按 8 字节对齐")
  // AXIRAM的地址/数据同宽，WSTRB随字宽变化；SimTop对32/64都直接
  // 同宽连接，不再插入宽度桥
  val io = IO(new Bundle {
    val AXI = Flipped(new ysyx_26030103_AXI5IO(SOCXLEN, SOCXLEN, 4))
    // direct-NPC 在构造 DUT 后由 C++ 将命令行镜像写入 RAM，使 RTL 和
    // DiffTest/调试器共用同一份 FlashMemory，不再依赖当前目录下可能过期的
    // program.hex。地址端口固定 32 位，由 wordIndexBits 截取实际深度，这样
    // 64/128 MiB 共用同一个 SimTop 端口，不需要为每种 RAM 大小重写顶层。
    val hostWriteValid = Input(Bool())
    val hostWriteIndex = Input(UInt(32.W))
    val hostWriteData = Input(UInt(SOCXLEN.W))
    // 仿真 stdin/测试平台注入的 UART 接收字节。rxReady 为高表示 FIFO
    // 还能接收；上层不注入时保持 valid=0 即可，不会阻塞仿真。
    val UARTRxValid = Input(Bool())
    val UARTRxData = Input(UInt(8.W))
    val UARTRxReady = Output(Bool())
    val UARTTxValid = Output(Bool())
    val UARTTxData = Output(UInt(8.W))
    val UARTIRQ = Output(Bool())
  })

  // RAM大小由Kconfig的NPC_RAM_SIZE决定（默认64MiB，Linux U-Boot
  // 配置为128MiB）。深度与地址位宽都从同一个字节数和当前字宽推导，
  // 避免PMA、AXI窗口和SyncReadMem索引各写一份常量
  val depth = (RAMBytes / bytesPerWord).toInt
  val wordIndexBits = log2Ceil(depth)
  val mem = SyncReadMem(depth, UInt(SOCXLEN.W))
  // 16550 兼容的 UART：TX 保持 printf 行为，RX 由仿真顶层注入。
  val UART = Module(new ysyx_26030103_UART16550())

  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
  val RAMBaseU = RAMBase.U(SOCXLEN.W)
  val RAMLastU = (RAMBase + RAMBytes - 1L).U(SOCXLEN.W)
  val UARTBaseU = UARTBase.U(SOCXLEN.W)
  // 16550 实际寄存器只占偏移 0..7。
  val UARTLastU = (UARTBase + 7L).U(SOCXLEN.W)

  // UART和RAM都要求整个访问落在窗口内，避免跨窗口访问被错误地
  // 当成从设备寄存器。maxSize是该窗口允许的最大AxSIZE，lastStart
  // 由最后一个字节反推本次访问允许的最大起始地址
  def InWindow(
      base: UInt,
      last: UInt,
      address: UInt,
      size: UInt,
      maxSize: Int
  ): Bool = {
    val lastStart = MuxLookup(size, 0.U(SOCXLEN.W))(
      (0 to maxSize).map(index =>
        index.U -> (last - ((1 << index) - 1).U(SOCXLEN.W))
      )
    )
    size <= maxSize.U && address >= base && address <= lastStart
  }

  // UART寄存器按字节编址，单拍只允许size<=2；64位下size=3会
  // 被ConfigError拒绝，避免误触发RBR弹出等副作用
  def isUARTBeat(address: UInt, size: UInt): Bool = {
    InWindow(UARTBaseU, UARTLastU, address, size, wordBytesLog2)
  }

  // 只有 AxSIZE 选中的每个字节都位于当前 RAM 窗口内时，该拍才属于
  // RAM。尤其要避免窗口外地址通过截断后的 SyncReadMem 索引发生回绕。
  def isRAMBeat(address: UInt, size: UInt): Bool = {
    InWindow(RAMBaseU, RAMLastU, address, size, wordBytesLog2)
  }

  def BeatStep(size: UInt): UInt =
    MuxLookup(size, 0.U(SOCXLEN.W))(
      (0 to wordBytesLog2).map(index =>
        index.U -> (1 << index).U(SOCXLEN.W)
      )
    )

  def ConfigError(address: UInt, size: UInt, burst: UInt, len: UInt): Bool =
    size > wordBytesLog2.U || burst > 1.U ||
      (isUARTBeat(address, size) && (size > 2.U || len =/= 0.U))

  def WordIndex(address: UInt): UInt =
    ((address - RAMBaseU) >> wordBytesLog2)(wordIndexBits - 1, 0)

  val sIdle :: sReadWait :: sReadResponse :: sWriteRead :: sWriteCommit :: sWriteDrain :: sWriteResponse :: Nil =
    Enum(7)
  val state = RegInit(sIdle)
  val ARAddress = RegInit(0.U(SOCXLEN.W))
  val arID = RegInit(0.U(4.W))
  val arLen = RegInit(0.U(8.W))
  val arSize = RegInit(2.U(3.W))
  val arBurst = RegInit(0.U(2.W))
  val readBeat = RegInit(0.U(8.W))
  val readConfigError = RegInit(false.B)
  val readResponse = RegInit(OKAY)
  val ReadIsUART = RegInit(false.B)
  val ReadUARTData = RegInit(0.U(8.W))
  val readStep = BeatStep(arSize)
  val ReadAddressIsRAM = isRAMBeat(ARAddress, arSize)
  val ReadAddressIsUART = isUARTBeat(ARAddress, arSize)
  val readWordIndex = WordIndex(ARAddress)
  val readData = mem.read(
    readWordIndex,
    (state === sReadWait || state === sReadResponse) &&
      !readConfigError && ReadAddressIsRAM
  )

  val AWAddress = RegInit(0.U(SOCXLEN.W))
  val awID = RegInit(0.U(4.W))
  val awLen = RegInit(0.U(8.W))
  val awSize = RegInit(2.U(3.W))
  val awBurst = RegInit(0.U(2.W))
  val writeBeat = RegInit(0.U(8.W))
  val writeResponse = RegInit(OKAY)
  val writeConfigError = RegInit(false.B)
  val wData = RegInit(0.U(SOCXLEN.W))
  val wStrobe = RegInit(0.U(bytesPerWord.W))
  val wLast = RegInit(false.B)
  val AWPending = RegInit(false.B)
  val wPending = RegInit(false.B)

  // SyncReadMem 会在读请求后一拍返回数据。因此，部分写必须先读取旧字，再在
  // 后续状态中合并并写回；如果在请求当拍使用读结果，就会合并陈旧或未定义的
  // 数据。
  val WriteAddressIsRAM = isRAMBeat(AWAddress, awSize)
  val WriteAddressIsUART = isUARTBeat(AWAddress, awSize)
  val writeWordIndex = WordIndex(AWAddress)
  val writeOld = mem.read(
    writeWordIndex,
    state === sWriteRead && !writeConfigError && WriteAddressIsRAM
  )
  val writeMask = Cat(
    (bytesPerWord - 1 to 0 by -1).map(index =>
      Mux(wStrobe(index), 0xff.U(8.W), 0.U(8.W))
    )
  )
  val writeMerged = (wData & writeMask) | (writeOld & ~writeMask)
  val writeStep = BeatStep(awSize)
  val expectedLastBeat = writeBeat === awLen

  // RAM 只保留一个写端口。主机初始化发生在 reset 期间，
  // 并且优先于 AXI 写，避免为 SyncReadMem 推导额外写端口。
  val cpuWriteValid = WireDefault(false.B)
  // 必须和 hostWriteIndex 一样使用完整的 wordIndexBits；此前写成 16 位，
  // 64MiB RAM 的 CPU store 会在 256KiB 处回绕，高地址栈写入全部丢失。
  val cpuWriteIndex = WireDefault(0.U(wordIndexBits.W))
  val cpuWriteData = WireDefault(0.U(SOCXLEN.W))
  // 主机地址端口固定 32 位，只取实际深度需要的低位，避免 128MiB 时
  // 高 bit 丢失（64MiB 时多余的高位自然被忽略）。
  val hostWriteWordIndex = io.hostWriteIndex(wordIndexBits - 1, 0)
  when(io.hostWriteValid || cpuWriteValid) {
    mem.write(
      Mux(io.hostWriteValid, hostWriteWordIndex, cpuWriteIndex),
      Mux(io.hostWriteValid, io.hostWriteData, cpuWriteData)
    )
  }

  // 尚未收集完整的写事务优先于读事务。AW 与 W 独立接收，可以按任意顺序到达。
  val writeIncoming = AWPending || wPending || io.AXI.AW.AWVALID || io.AXI.W.WVALID
  io.AXI.AW.AWREADY := state === sIdle && !AWPending
  io.AXI.W.WREADY := (state === sIdle && !wPending) || state === sWriteDrain
  io.AXI.AR.ARREADY := state === sIdle && !writeIncoming

  val AWFire = io.AXI.AW.AWVALID && io.AXI.AW.AWREADY
  val wFire = io.AXI.W.WVALID && io.AXI.W.WREADY
  val wCollectFire = wFire && state === sIdle
  val wDrainFire = wFire && state === sWriteDrain
  val ARFire = io.AXI.AR.ARVALID && io.AXI.AR.ARREADY
  val HaveAW = AWPending || AWFire
  val haveW = wPending || wCollectFire

  // UART 寄存器是单拍字节访问。读请求在 AR 握手当拍采样，读 RBR 的
  // 弹出副作用也只发生一次；写请求在 sWriteCommit 产生 THR/寄存器写。
  val UARTARFire = ARFire &&
    isUARTBeat(io.AXI.AR.ARADDR, io.AXI.AR.ARSIZE) &&
    io.AXI.AR.ARSIZE <= 2.U &&
    io.AXI.AR.ARBURST <= 1.U && io.AXI.AR.ARLEN === 0.U
  UART.io.rxValid := io.UARTRxValid
  UART.io.rxData := io.UARTRxData
  UART.io.rdAddress := io.AXI.AR.ARADDR(2, 0)
  UART.io.ReadEnable := UARTARFire
  UART.io.wrAddress := AWAddress(2, 0)
  UART.io.WriteEnable := state === sWriteCommit && !writeConfigError &&
    WriteAddressIsUART && wStrobe =/= 0.U
  // AXI字节通道由地址低位选择；UART寄存器在32/64位下都只占一个
  // 字节通道，lane数随字宽变化
  val uartByteLane = AWAddress(wordBytesLog2 - 1, 0)
  UART.io.wrData := (wData >> (uartByteLane << 3))(7, 0)
  io.UARTRxReady := UART.io.rxReady
  io.UARTTxValid := UART.io.txValid
  io.UARTTxData := UART.io.txData
  io.UARTIRQ := UART.io.IRQ

  when(AWFire) {
    AWAddress := io.AXI.AW.AWADDR
    awID := io.AXI.AW.AWID
    awLen := io.AXI.AW.AWLEN
    awSize := io.AXI.AW.AWSIZE
    awBurst := io.AXI.AW.AWBURST
    writeBeat := 0.U
    val badWrite = ConfigError(
      io.AXI.AW.AWADDR,
      io.AXI.AW.AWSIZE,
      io.AXI.AW.AWBURST,
      io.AXI.AW.AWLEN
    )
    writeConfigError := badWrite
    writeResponse := Mux(badWrite, SLVERR, OKAY)
    AWPending := true.B
  }
  when(wCollectFire) {
    wData := io.AXI.W.WDATA
    wStrobe := io.AXI.W.WSTRB
    wLast := io.AXI.W.WLAST
    wPending := true.B
  }

  io.AXI.R.RVALID := state === sReadResponse
  // UART是字节寄存器，按16550常见做法复制到所有字节通道；LSU会按
  // 访问地址选择正确通道，因此字/半字/字节读都能得到同一寄存器值
  io.AXI.R.RDATA := Mux(
    ReadIsUART,
    Cat(Seq.fill(bytesPerWord)(ReadUARTData)),
    Mux(readResponse === OKAY, readData, 0.U)
  )
  io.AXI.R.RRESP := readResponse
  io.AXI.R.RLAST := state === sReadResponse && readBeat === arLen
  io.AXI.R.RID := arID

  io.AXI.B.BVALID := state === sWriteResponse
  io.AXI.B.BRESP := writeResponse
  io.AXI.B.BID := awID

  switch(state) {
    is(sIdle) {
      when(ARFire) {
        ARAddress := io.AXI.AR.ARADDR
        arID := io.AXI.AR.ARID
        arLen := io.AXI.AR.ARLEN
        arSize := io.AXI.AR.ARSIZE
        arBurst := io.AXI.AR.ARBURST
        readBeat := 0.U
        ReadIsUART := UARTARFire
        ReadUARTData := UART.io.rdData
        // UART 不支持突发读；突发请求按配置错误安全终止，且不产生
        // RBR 弹出等副作用。
        readConfigError := ConfigError(
          io.AXI.AR.ARADDR,
          io.AXI.AR.ARSIZE,
          io.AXI.AR.ARBURST,
          io.AXI.AR.ARLEN
        )
        state := sReadWait
      }.elsewhen(HaveAW && haveW) {
        state := sWriteRead
      }
    }
    is(sReadWait) {
      readResponse := Mux(
        readConfigError,
        SLVERR,
        Mux(ReadAddressIsRAM || ReadAddressIsUART, OKAY, DECERR)
      )
      state := sReadResponse
    }
    is(sReadResponse) {
      when(io.AXI.R.RREADY) {
        when(readBeat === arLen) {
          state := sIdle
        }.otherwise {
          readBeat := readBeat + 1.U
          when(arBurst === 1.U && !readConfigError) {
            ARAddress := ARAddress + readStep
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
        when(WriteAddressIsRAM) {
          cpuWriteValid := true.B
          cpuWriteIndex := writeWordIndex
          cpuWriteData := writeMerged
        }.elsewhen(WriteAddressIsUART) {
          // UART 写由 UART.io.WrEnable 在 sWriteCommit 产生副作用（THR 打印或
          // 控制寄存器更新），这里不写 RAM。
        }.otherwise {
          when(writeResponse === OKAY) {
            writeResponse := DECERR
          }
        }
      }
      when(wLast) {
        when(!expectedLastBeat) {
          writeResponse := SLVERR // WLAST 过早到达
        }
        state := sWriteResponse
      }.elsewhen(expectedLastBeat) {
        // 已收到 AWLEN 指定的拍数但仍未见 WLAST。继续排空到 WLAST，避免上游
        // 写数据通道永久阻塞，随后报告 SLVERR。
        writeResponse := SLVERR
        wPending := false.B
        state := sWriteDrain
      }.otherwise {
        writeBeat := writeBeat + 1.U
        when(awBurst === 1.U) { // INCR 递增地址；FIXED 保持地址不变
          AWAddress := AWAddress + writeStep
        }
        wPending := false.B
        state := sIdle
      }
    }
    is(sWriteDrain) {
      when(wDrainFire && io.AXI.W.WLAST) {
        state := sWriteResponse
      }
    }
    is(sWriteResponse) {
      when(io.AXI.B.BREADY) {
        AWPending := false.B
        wPending := false.B
        state := sIdle
      }
    }
  }
}
