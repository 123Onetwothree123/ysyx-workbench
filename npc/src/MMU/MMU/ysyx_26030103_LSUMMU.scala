package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage
import _root_.ysyx_26030103.common.ysyx_26030103_AtomicOp
import _root_.ysyx_26030103.common.ysyx_26030103_RISCVFields

/** EXU 虚拟访存地址翻译。PIPT 下翻译完成后才把指令交给 LSU，故障无总线副作用；
  * VIPT（ParallelVIPT=true）下 load/store 先以 VA 交给 LSU（DCache 用 VA 选
  * index），PA 带 VA 标签从 memPhysicalAddress* 返回，store 在 PA 匹配前只登记
  * 写缓冲；故障经 memKill 撤销已受理请求，由 faultCommit 提交精确 page fault。
  */
class ysyx_26030103_LSUMMU(
    val ParallelVIPT: Boolean = false,
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth
) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "LSUMMU")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(XLEN.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val faultCommit = Output(Bool())
    val faultCause = Output(UInt(32.W))
    val faultTval = Output(UInt(XLEN.W))
    val FaultPC = Output(UInt(XLEN.W))
    val busy = Output(Bool())
    val PMPAddress = Output(UInt(AddressWidth.W))
    // 本次数据访问最后一个字节的物理地址，供 PMP 做完整范围检查。
    val PMPLastAddress = Output(UInt(AddressWidth.W))
    // AMO 同时需要读/写权限；LR 只读，SC/普通 store 只写。
    val PMPRead = Output(Bool())
    val PMPWrite = Output(Bool())
    val PMPFault = Input(Bool())
    // VIPT 并行模式下，已先行交给 LSU 的 load 所使用的翻译结果。
    val memPhysicalAddress = Output(UInt(AddressWidth.W))
    val memPhysicalAddressValid = Output(Bool())
    val memPhysicalAddressVirtualAddress = Output(UInt(XLEN.W))
    // MMU 翻译故障单拍脉冲：撤销 LSU/DCache 中尚未拿到 PA 的请求。
    val memKill = Output(Bool())
    // LSU 是否仍持有更老/当前指令。VIPT 并行模式用它保证翻译结果在
    // 被消费前不被下一笔请求覆盖。
    val memActive = Input(Bool())
    // LSU 已为更老/当前指令提交访存陷阱（misaligned/PMP/总线故障）；VIPT 下
    // 该指令可能仍在翻译中，必须撤销，避免迟到的 page fault 造成第二次异常。
    val memTrap = Input(Bool())
    val request = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val response = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })
  // fwd 是 VIPT 并行专用：先把原始 VA 请求交给 LSU，再等待 MMU。
  // extraRequest/extraWait 在 memActive 保持期间翻译跨页访问的第二页。
  val states = Enum(9)
  val List(idle, translationRequest, xwait, done, fault, fwd,
      extraRequest, extraWait, extraDone) = states
  val state = RegInit(idle)
  val MessageReg = RegInit(0.U.asTypeOf(new ysyx_26030103_EXUMessage(XLEN)))
  val MessageBypass = RegInit(false.B)
  val physicalAddressReg = RegInit(0.U(AddressWidth.W))
  val physicalAddressVirtualAddressReg = RegInit(0.U(XLEN.W))
  // VIPT 并行：PA 有效标志独立于状态机保持，直到 LSU 真正排空；避免
  // 流水冲刷使 state 提前离开 done 时 DCache 永远等不到 PA。
  val physicalAddressValidReg = RegInit(false.B)
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(32.W))
  val faultTvalReg = RegInit(0.U(XLEN.W))
  val FaultPCReg = RegInit(0.U(XLEN.W))
  val bypass = ysyx_26030103_RISCVFields.SatpMode(io.satp, XLEN) === 0.U ||
    io.privilege === 3.U
  val isMem = io.in.bits.MemoryValid
  // AMO/LR/SC 的自然对齐必须在翻译前判定，避免页错误/访问故障抢先
  val AtomicAddressMisaligned = io.in.bits.IsAtomic &&
    MuxLookup(io.in.bits.WidthSelect, false.B)(Seq(
      1.U -> io.in.bits.ALUResult(0),
      2.U -> io.in.bits.ALUResult(1, 0).orR,
      3.U -> io.in.bits.ALUResult(2, 0).orR
    ))

  io.in.ready := false.B
  io.out.valid := false.B
  io.out.bits := io.in.bits
  io.request.valid := false.B
  io.request.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  io.response.ready := true.B
  io.faultCommit := faultReg
  io.faultCause := faultCauseReg
  io.faultTval := faultTvalReg
  io.FaultPC := FaultPCReg
  io.busy := state =/= idle
  // idle 拍也要把即将直通的访存地址交给 PMP：satp.MODE=0 或 M 模式时顶层把
  // LSU.PmpFault 绑 0，必须由 LSUMMU 自己检查，否则 bare/M 模式 PMP 失效。
  val PMPMessage = Mux(state === idle, io.in.bits, MessageReg)
  io.PMPAddress := Mux(state === idle, io.in.bits.ALUResult,
    Mux(state === xwait || state === extraWait, io.response.bits.paddr,
      physicalAddressReg))
  // 与 pmpAddr 使用同一个物理基址，再加上本次访问宽度对应的末字节偏移
  // （0/1/2/3 = 1/2/4/8 字节），覆盖可能跨 4B PMP granule 的访问。
  val PMPLastOffset = MuxLookup(PMPMessage.WidthSelect, 0.U(AddressWidth.W))(
    Seq(
      0.U -> 0.U(AddressWidth.W),
      1.U -> 1.U(AddressWidth.W),
      2.U -> 3.U(AddressWidth.W),
      3.U -> 7.U(AddressWidth.W)
    )
  )
  // 跨页访问按页内子区间分别做 PMP 检查。
  val msgVa = MessageReg.ALUResult(AddressWidth - 1, 0)
  val msgBytes = 1.U << MessageReg.WidthSelect
  val msgLastVa = msgVa + msgBytes - 1.U
  val msgCrossPage = msgLastVa(AddressWidth - 1, 12) =/=
    msgVa(AddressWidth - 1, 12)
  val msgLen0 = Mux(msgCrossPage, 4096.U(AddressWidth.W) - msgVa(11, 0),
    msgBytes)
  val msgPage1Va = Cat(msgVa(AddressWidth - 1, 12) + 1.U, 0.U(12.W))
  val msgBaseOffset = Mux(msgCrossPage, msgLen0 - 1.U, PMPLastOffset)
  val msgExtraOffset = msgBytes - msgLen0 - 1.U
  io.PMPLastAddress := io.PMPAddress +
    Mux(state === extraWait || state === extraDone, msgExtraOffset,
      Mux(state === idle, PMPLastOffset, msgBaseOffset))
  io.PMPWrite := PMPMessage.MemoryWrite
  // 普通 load 与 LR 读；AMO 读+写；SC 只写。
  io.PMPRead := !PMPMessage.MemoryWrite ||
    (PMPMessage.IsAtomic &&
      PMPMessage.AtomicOp =/= ysyx_26030103_AtomicOp.SC)
  io.memPhysicalAddress := physicalAddressReg
  if (ParallelVIPT) {
    io.memPhysicalAddressValid := physicalAddressValidReg
  } else {
    // PIPT 下 done 给出基页 PA；跨页时 extraDone 再补发第二页 PA。
    io.memPhysicalAddressValid :=
      state === done || (state === extraDone && physicalAddressValidReg)
  }
  io.memPhysicalAddressVirtualAddress := physicalAddressVirtualAddressReg
  io.memKill := false.B

  private def CommitFault(cause: UInt, tval: UInt, pc: UInt): Unit = {
    faultReg := true.B
    faultCauseReg := cause
    faultTvalReg := tval
    FaultPCReg := pc
  }

  private def IssueTranslation(va: UInt, id: UInt): Unit = {
    io.request.valid := true.B
    io.request.bits.ID := id
    io.request.bits.vaddr := va
    io.request.bits.access := Mux(MessageReg.MemoryWrite,
      ysyx_26030103_AccessType.Store, ysyx_26030103_AccessType.Load)
    io.request.bits.privilege := io.privilege
    io.request.bits.sum := io.sum
    io.request.bits.mxr := io.mxr
    io.request.bits.satp := io.satp
  }

  private def HandleTranslationResponse(reqVa: UInt, nextState: UInt,
      onSuccessExtra: => Unit): Unit = {
    when(io.response.valid && io.response.bits.tval === reqVa) {
      when(io.response.bits.fault) {
        CommitFault(io.response.bits.cause, MessageReg.ALUResult, MessageReg.PC)
        state := fault
      }.elsewhen(io.PMPFault) {
        // 页表翻译成功但物理地址被PMP拒绝：load=5，store/AMO=7
        CommitFault(ysyx_26030103_RISCVFields.AccessFaultCause(MessageReg.MemoryWrite),
          MessageReg.ALUResult, MessageReg.PC)
        state := fault
      }.otherwise {
        physicalAddressReg := io.response.bits.paddr
        physicalAddressVirtualAddressReg := reqVa
        onSuccessExtra
        state := nextState
      }
    }
  }

  when(state === translationRequest) {
    IssueTranslation(MessageReg.ALUResult, 1.U)
    when(io.request.fire) { state := xwait }
  }
  when(state === extraRequest) {
    IssueTranslation(msgPage1Va, 1.U)
    when(io.request.fire) { state := extraWait }
  }

  if (!ParallelVIPT) {
    // 原有 PIPT 路径：翻译完成后才发 LSU
    when(state === idle) {
      // 始终使用同一个握手条件，避免 in.ready 依赖 in.bits 形成组合环。
      io.in.ready := io.out.ready
      when(!isMem) {
        io.out.valid := io.in.valid
        io.out.bits := io.in.bits
      }.elsewhen(io.in.valid && AtomicAddressMisaligned) {
        // 对齐检查失败：不翻译、不进入 LSU
        when(io.in.fire) {
          CommitFault(ysyx_26030103_RISCVFields.MisalignedCause(io.in.bits.MemoryWrite),
            io.in.bits.ALUResult, io.in.bits.PC)
          state := fault
        }
      }.elsewhen(bypass) {
        // Bare/M 模式没有页表翻译，但 PMP 仍必须检查。必须用 in.fire 而不是
        // valid：LSU 忙时请求尚未被受理，不能提前锁存/提交 PMP 故障。
        when(io.in.fire && io.PMPFault) {
          CommitFault(ysyx_26030103_RISCVFields.AccessFaultCause(io.in.bits.MemoryWrite),
            io.in.bits.ALUResult, io.in.bits.PC)
          state := fault
        }.otherwise {
          io.out.valid := io.in.valid
          io.out.bits := io.in.bits
        }
      }.otherwise {
        when(io.in.fire) {
          MessageReg := io.in.bits
          state := translationRequest
        }
      }
    }
    when(state === xwait) {
      // 同IFU：只接受tval与当前请求VA一致的响应，迟到旧响应丢弃；PIPT下请求尚未交给LSU，故障无总线副作用
      HandleTranslationResponse(MessageReg.ALUResult, done, ())
    }
    when(state === done) {
      // done 拍正把已翻译的访存指令送给 LSU；MessageReg 仍被占用，同拍不能再
      // 接收 EXU 的下一笔请求，否则 io.in.fire 会把它吞掉却不保存。
      io.in.ready := false.B
      io.out.valid := true.B
      io.out.bits := MessageReg
      io.out.bits.ALUResult := physicalAddressReg
      when(io.out.fire) {
        state := Mux(msgCrossPage, extraRequest, idle)
      }
    }
    when(state === extraWait) {
      HandleTranslationResponse(msgPage1Va, extraDone, {
        physicalAddressValidReg := true.B
      })
    }
    when(state === extraDone) {
      // 第二页 PA 保持到 LSU 排空。
      when(!io.memActive) {
        physicalAddressValidReg := false.B
        state := idle
      }
    }
    when(io.flush) {
      state := idle
      faultReg := false.B
      physicalAddressValidReg := false.B
    }
  } else {
    // VIPT 并行路径
    // 只有 LSU 完全空闲时才受理新的内存指令，保证 physicalAddressReg 在 DCache
    // 取用前不被下一笔覆盖，且翻译故障的 kill 不会误伤更老指令。in.ready 不
    // 能依赖 in.bits（否则 EXU 形成组合环），故统一取“LSU 空闲且下游可接收”。
    when(state === idle) {
      io.in.ready := io.out.ready && !io.memActive
      when(io.in.fire) {
        // 新请求受理时先撤销上一笔 PA，防止相同 VA 的旧 PA 被误用。
        physicalAddressValidReg := false.B
      }
      when(!isMem) {
        // 非访存指令组合直通。
        io.out.valid := io.in.valid
        io.out.bits := io.in.bits
      }.elsewhen(io.in.fire) {
        // load/store 都先锁存原始 VA 并转发给 LSU：store 此时只进入写
        // 缓冲登记，不会发 AW/W；PA 由翻译结果带标签返回。
        when(AtomicAddressMisaligned) {
          // 对齐检查失败：不转发、不翻译
          CommitFault(ysyx_26030103_RISCVFields.MisalignedCause(io.in.bits.MemoryWrite),
            io.in.bits.ALUResult, io.in.bits.PC)
          state := fault
        }.elsewhen(bypass && io.PMPFault) {
          // Bare/M 模式没有翻译，但 PMP 必须先生效；不要进入 fwd，
          // 否则故障 load/store 会被 LSU 当成正常请求接收。
          CommitFault(ysyx_26030103_RISCVFields.AccessFaultCause(io.in.bits.MemoryWrite),
            io.in.bits.ALUResult, io.in.bits.PC)
          state := fault
        }.otherwise {
          MessageReg := io.in.bits
          MessageBypass := bypass
          state := fwd
          when(bypass) {
            // bypass 时 VA==PA，可以在 LSU 受理前就给出有效 PA。
            physicalAddressReg := io.in.bits.ALUResult
            physicalAddressVirtualAddressReg := io.in.bits.VAddress
            physicalAddressValidReg := true.B
          }
        }
      }
    }
    when(state === fwd) {
      // 这里交给 LSU 的 ALUResult 仍是 VA；物理地址只通过 memPaddr 提供。
      // store 也只是在此登记到 LSU 写缓冲，PA 有效前不会发 AW/W。
      io.out.valid := true.B
      io.out.bits := MessageReg
      when(io.out.fire) {
        when(MessageBypass) { state := done }
          .otherwise { state := translationRequest }
      }
    }
    when(state === xwait) {
      // 基页PA先交给LSU，跨页时继续补翻第二页
      HandleTranslationResponse(MessageReg.ALUResult,
        Mux(msgCrossPage, extraRequest, done), {
          physicalAddressValidReg := true.B
        })
      // load已进入LSU；只处理当前请求的故障响应，迟到旧响应和PMP access fault不能误伤新访存
      io.memKill := io.response.valid && io.response.bits.tval === MessageReg.ALUResult &&
        (io.response.bits.fault || io.PMPFault)
    }
    when(state === extraWait) {
      HandleTranslationResponse(msgPage1Va, done, {
        physicalAddressValidReg := true.B
      })
      // 第二页翻译失败同样撤销已经交给 LSU/DCache 的请求。
      io.memKill := io.response.valid &&
        io.response.bits.tval === msgPage1Va &&
        (io.response.bits.fault || io.PMPFault)
    }
    when(io.flush && (state === translationRequest || state === xwait ||
        state === extraRequest || state === extraWait)) {
      // flush 会直接丢弃在途翻译且不再返回响应；继续等 xwait 会永久 busy。
      // 该请求属于可撤销的年轻访存，连同已交给 LSU/DCache 的登记一起 kill。
      io.memKill := true.B
    }
    when(state === done) {
      // 翻译结果保持到 LSU 完全排空，之后才允许下一笔请求覆盖 PA。
      // store 的写缓冲会在 PA 标签匹配后自行完成总线提交，这里不再转发。
      when(!io.memActive) {
        physicalAddressValidReg := false.B
        state := idle
      }
    }
    when(io.flush) {
      // translationRequest/xwait 的 MMU 请求已被 flush 丢弃，不会再返回响应；
      // 必须撤销并 kill 已登记到 LSU/DCache 的年轻访存。done 已有 PA 且可能
      // 已被 LSU 接收，仍保持到 memActive 撤销。
      when(state === idle || state === fwd || state === fault ||
          state === translationRequest || state === xwait ||
          state === extraRequest || state === extraWait) {
        state := idle
        faultReg := false.B
        physicalAddressValidReg := false.B
      }
    }
    // LSU 已为当前指令提交陷阱：该指令不会再消费翻译结果，撤销在途翻译；
    // 迟到响应会在 idle 被 response.ready 排空，仲裁器消费前不会发下一笔 DMMU 请求。
    when(io.memTrap) {
      state := idle
      faultReg := false.B
      physicalAddressValidReg := false.B
    }
  }
}
