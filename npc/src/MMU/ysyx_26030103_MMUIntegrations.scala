package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage
import _root_.ysyx_26030103.common.ysyx_26030103_AtomicOp

/** IFU/LSU 双端口 MMU 仲裁器。
  *
  * 每个客户端最多保留一笔在途翻译；两笔可以同时进入 MMU 的独立上下文，
  * 响应按请求 ID 回到对应客户端，不再要求 IFU 的翻译先完成。
  */
class ysyx_26030103_MMURequestArbiter(
    XLEN: Int = 32,
    AddressWidth: Int = 32
) extends Module {
  val io = IO(new Bundle {
    val iReq = Flipped(Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth)))
    val iResp = Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
    val dReq = Flipped(Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth)))
    val dResp = Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
    val mmuReq = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val mmuResp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
    // TLB 全量刷新会清空 MMU 内所有在途翻译，且不会再返回响应；仲裁器
    // 必须同步清掉 outstanding，否则客户端会永远等不到翻译结果。
    val flush = Input(Bool())
  })

  val iOutstanding = RegInit(false.B)
  val dOutstanding = RegInit(false.B)
  io.iReq.ready := false.B
  io.dReq.ready := false.B
  io.mmuReq.valid := false.B
  io.mmuReq.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  io.iResp.valid := false.B
  io.iResp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
  io.dResp.valid := false.B
  io.dResp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
  io.mmuResp.ready := false.B

  // 选择一笔尚未在途的客户端请求送入 MMU。flush 当拍不接收新请求，
  // 避免请求刚发出就被清空却没有任何响应。
  val chooseD = !io.flush && !dOutstanding && io.dReq.valid &&
    (iOutstanding || !io.iReq.valid)
  val chooseI = !io.flush && !iOutstanding && io.iReq.valid && !chooseD
  when(chooseD) {
    io.dReq.ready := io.mmuReq.ready
    io.mmuReq.valid := io.dReq.valid
    io.mmuReq.bits := io.dReq.bits
    when(io.dReq.fire) { dOutstanding := true.B }
  }.elsewhen(chooseI) {
    io.iReq.ready := io.mmuReq.ready
    io.mmuReq.valid := io.iReq.valid
    io.mmuReq.bits := io.iReq.bits
    when(io.iReq.fire) { iOutstanding := true.B }
  }

  // MMU 响应携带客户端 ID：0=IFU，1=LSU。
  when(io.mmuResp.valid) {
    when(io.mmuResp.bits.id === 0.U) {
      io.iResp.valid := true.B
      io.iResp.bits := io.mmuResp.bits
      io.mmuResp.ready := io.iResp.ready
      when(io.iResp.fire) { iOutstanding := false.B }
    }.otherwise {
      io.dResp.valid := true.B
      io.dResp.bits := io.mmuResp.bits
      io.mmuResp.ready := io.dResp.ready
      when(io.dResp.fire) { dOutstanding := false.B }
    }
  }
  // MMU 在 flush 时直接丢弃在途翻译，不会回响应；这里同步撤销
  // outstanding，让客户端可以在下一拍重新发起翻译。
  when(io.flush) {
    iOutstanding := false.B
    dOutstanding := false.B
  }
}

/** 取指地址翻译。
  *
  * PIPT 模式：翻译期间 IFU 的 FetchValid 保持，fetchValid 由顶层按
  * translationValid 门控。
  * VIPT 并行模式（ParallelVipt=true）：顶层把 ICache 已受理但缺 PA 的请求
  * 接到 fetchValid/fetchVaddr，翻译结果附带请求 VA 作为标签；ICache 只有在
  * 标签匹配时才接收 PA。取指 MMU 不再跑在已受理请求之前。
  */
class ysyx_26030103_IFUMMU(
    ParallelVipt: Boolean = false,
    XLEN: Int = 32,
    AddressWidth: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"IFUMMU XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchReady = Output(Bool())
    val fetchVaddr = Input(UInt(XLEN.W))
    val fetchPaddr = Output(UInt(AddressWidth.W))
    // 该 PA 对应的虚拟地址。VIPT 下 ICache 用它校验“迟到的 PA”属于
    // 当前已受理的请求，而不是上一笔/错路请求。
    val fetchPaddrVaddr = Output(UInt(XLEN.W))
    val translationValid = Output(Bool())
    val cacheReady = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVaddr = Output(UInt(XLEN.W))
    val pmpFault = Input(Bool())
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(XLEN.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })
  val idle :: xwait :: done :: fault :: discard :: Nil = Enum(5)
  val state = RegInit(idle)
  val paddrReg = RegInit(0.U(AddressWidth.W))
  val paddrVaddrReg = RegInit(0.U(XLEN.W))
  val reqVaddrReg = RegInit(0.U(XLEN.W))
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(4.W))
  val faultVaddrReg = RegInit(0.U(XLEN.W))
  // RV64 satp.MODE 在 bit 63:60；RV32 在 bit 31。
  private def satpMode(satp: UInt): UInt =
    if (XLEN == 32) satp(31) else satp(63, 60)
  val bypass = satpMode(io.satp) === 0.U || io.privilege === 3.U

  io.req.valid := false.B
  io.req.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  // flush 后旧 walk 的响应仍可能返回；非等待状态也要接收并丢弃，避免
  // MMU 请求仲裁器卡在响应转发状态。
  io.resp.ready := true.B
  io.fetchPaddr := Mux(bypass, io.fetchVaddr, paddrReg)
  io.fetchPaddrVaddr := Mux(bypass, io.fetchVaddr, paddrVaddrReg)
  // 重定向后 IFU 的虚拟 PC 已改变；即使 flush 晚一拍，旧 paddr 也绝不能
  // 再被当成新 PC 的物理地址送给 ICache。
  // VIPT 下 PMP 也必须在 PA 有效后先通过，才能把 PA 交给 ICache。
  // PIPT 同样不能在 PMP 拒绝的当拍放出 valid：顶层用 translationValid
  // 直接门控 icache.fetch_valid，晚一拍撤销就已经受理了故障取指。
  io.translationValid :=
    (bypass && state === idle && !io.pmpFault) ||
      (state === done && paddrVaddrReg === io.fetchVaddr && !io.pmpFault)
  io.fetchReady := io.translationValid && io.cacheReady
  io.fault := faultReg
  io.faultCause := faultCauseReg
  io.faultVaddr := faultVaddrReg

  when(io.flush) {
    state := idle
    faultReg := false.B
  }.otherwise {
    switch(state) {
      is(idle) {
        when(io.fetchValid) {
          when(bypass) {
            // Bare/M 模式不做页表翻译，但 PMP 仍然必须生效。故障取指
            // 不能进入 ICache，也不能进入 done（没有翻译响应可等）。
            when(io.pmpFault) {
              faultReg := true.B
              faultCauseReg := 1.U
              faultVaddrReg := io.fetchVaddr
              state := fault
            }
          }.otherwise {
            io.req.valid := true.B
            io.req.bits.id := 0.U // IFU
            io.req.bits.vaddr := io.fetchVaddr
            io.req.bits.access := ysyx_26030103_AccessType.Fetch
            io.req.bits.privilege := io.privilege
            io.req.bits.sum := io.sum
            io.req.bits.mxr := io.mxr
            io.req.bits.satp := io.satp
            when(io.req.fire) {
              reqVaddrReg := io.fetchVaddr
              state := xwait
            }
          }
        }
      }
      is(xwait) {
        io.resp.ready := true.B
        when(io.resp.valid) {
          // MMU 响应不带请求序号，只带原始虚拟地址 tval。redirect/flush 后
          // 旧 walk 的响应仍可能迟到；若不校验 tval，就会把旧 PA 当成当前
          // PC 的翻译结果（ICache 随后用新 PC 标签取到旧指令字）。
          when(io.resp.bits.tval =/= reqVaddrReg) {
            // 丢弃迟到响应，继续等待本请求的翻译结果。
          }.elsewhen(io.resp.bits.fault) {
            faultReg := true.B
            faultCauseReg := io.resp.bits.cause(3, 0)
            faultVaddrReg := reqVaddrReg
            state := fault
          }.otherwise {
            paddrReg := io.resp.bits.paddr
            paddrVaddrReg := reqVaddrReg
            state := done
          }
        }
      }
      is(done) {
        when(ParallelVipt.B && io.fetchValid &&
            paddrVaddrReg =/= io.fetchVaddr) {
          // VIPT: 已完成的翻译属于旧请求，直接丢弃，下一拍重新为当前
          // ICache 请求发起翻译。
          state := idle
        }.elsewhen(io.pmpFault) {
          faultReg := true.B
          faultCauseReg := 1.U
          faultVaddrReg := paddrVaddrReg
          state := fault
        }.elsewhen(!ParallelVipt.B && io.fetchValid && io.cacheReady &&
             paddrVaddrReg === io.fetchVaddr) { state := idle }
        // VIPT 下只有 ICache 消费 PA 后 fetchValid 才会撤销；不能因为
        // cacheReady 提前回 idle，否则会对同一请求重复发起翻译。
        when(!io.fetchValid && !io.pmpFault) { state := idle }
      }
      is(fault) {
        // 保持故障直到 EXU 的 trap 冲刷；faultReg 是单拍事件，避免重复注入。
        faultReg := false.B
      }
      is(discard) {
        io.resp.ready := true.B
        when(io.resp.valid) { state := idle }
      }
    }
    when(state === xwait && io.fetchValid === false.B) {
      state := discard
    }
  }
}

/** 把 EXU 的虚拟访存地址翻译成物理地址，再交给 LSU。
  *
  * PIPT（ParallelVipt=false）保持原行为：完整翻译完成后再把指令交给 LSU，
  * MMU 故障不会在 LSU/总线上留下任何副作用。
  *
  * VIPT 并行模式（ParallelVipt=true）下，load/store 都会先以 VA 交给 LSU
  * （DCache 用 VA 选 index），MMU 同时翻译；PA 带 VA 标签通过独立端口返回。
  * store 只登记到写缓冲，LSU 必须等 PA 标签匹配后才允许发 AW/W 或更新
  * DCache。翻译故障通过 memKill 取消 LSU 中已受理的请求，并由 faultCommit
  * 向 EXU 提交精确 page fault，故障 store 不产生任何写副作用。
  */
class ysyx_26030103_LSUMMU(
    val ParallelVipt: Boolean = false,
    val XLEN: Int = 32,
    val AddressWidth: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"LSUMMU XLEN 非法: $XLEN")
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
    val faultPC = Output(UInt(XLEN.W))
    val busy = Output(Bool())
    val pmpAddr = Output(UInt(AddressWidth.W))
    // 本次数据访问最后一个字节的物理地址，供 PMP 做完整范围检查。
    val pmpLastAddr = Output(UInt(AddressWidth.W))
    // AMO 同时需要读/写权限；LR 只读，SC/普通 store 只写。
    val pmpRead = Output(Bool())
    val pmpWrite = Output(Bool())
    val pmpFault = Input(Bool())
    // VIPT 并行模式下，已先行交给 LSU 的 load 所使用的翻译结果。
    val memPaddr = Output(UInt(AddressWidth.W))
    val memPaddrValid = Output(Bool())
    val memPaddrVaddr = Output(UInt(XLEN.W))
    // MMU 翻译故障单拍脉冲：撤销 LSU/DCache 中尚未拿到 PA 的请求。
    val memKill = Output(Bool())
    // LSU 是否仍持有更老/当前指令。VIPT 并行模式用它保证翻译结果在
    // 被消费前不被下一笔请求覆盖。
    val memActive = Input(Bool())
    // LSU 已为更老/当前指令提交访存陷阱（misaligned/PMP/总线故障）。
    // VIPT 下该指令可能仍在 DMMU 翻译中；必须撤销翻译，避免迟到的
    // page fault 在陷阱处理程序中造成第二次异常。
    val memTrap = Input(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })
  // fwd 是 VIPT 并行专用：先把原始 VA 请求交给 LSU，再等待 MMU。
  val idle :: xreq :: xwait :: done :: fault :: fwd :: Nil = Enum(6)
  val state = RegInit(idle)
  val msgReg = RegInit(0.U.asTypeOf(new ysyx_26030103_EXUMessage(XLEN)))
  val msgBypass = RegInit(false.B)
  val paddrReg = RegInit(0.U(AddressWidth.W))
  val paddrVaddrReg = RegInit(0.U(XLEN.W))
  // VIPT 并行：PA 有效标志独立于状态机保持，直到 LSU 真正排空；避免
  // 流水冲刷使 state 提前离开 done 时 DCache 永远等不到 PA。
  val paddrValidReg = RegInit(false.B)
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(32.W))
  val faultTvalReg = RegInit(0.U(XLEN.W))
  val faultPCReg = RegInit(0.U(XLEN.W))
  private def satpMode(satp: UInt): UInt =
    if (XLEN == 32) satp(31) else satp(63, 60)
  val bypass = satpMode(io.satp) === 0.U || io.privilege === 3.U
  val isMem = io.in.bits.MemoryValid

  io.in.ready := false.B
  io.out.valid := false.B
  io.out.bits := io.in.bits
  io.req.valid := false.B
  io.req.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  io.resp.ready := true.B
  io.faultCommit := faultReg
  io.faultCause := faultCauseReg
  io.faultTval := faultTvalReg
  io.faultPC := faultPCReg
  io.busy := state =/= idle
  // idle 拍也要把即将直通的访存地址交给 PMP：MMU 打开但 satp.MODE=0
  // 或当前为 M 模式时 bypass 翻译，顶层仍把 lsu.PmpFault 绑 0，因此
  // 必须由 LSUMMU 自己检查，否则 bare/M 模式的数据 PMP 完全失效。
  val PmpMsg = Mux(state === idle, io.in.bits, msgReg)
  io.pmpAddr := Mux(state === idle, io.in.bits.ALUResult,
    Mux(state === xwait, io.resp.bits.paddr, paddrReg))
  // 与 pmpAddr 使用同一个物理基址，再加上本次访问宽度对应的末字节偏移
  // （0/1/2/3 = 1/2/4/8 字节），覆盖可能跨 4B PMP granule 的访问。
  val PmpLastOffset = MuxLookup(PmpMsg.WidthSelect, 0.U(AddressWidth.W))(
    Seq(
      0.U -> 0.U(AddressWidth.W),
      1.U -> 1.U(AddressWidth.W),
      2.U -> 3.U(AddressWidth.W),
      3.U -> 7.U(AddressWidth.W)
    )
  )
  io.pmpLastAddr := io.pmpAddr + PmpLastOffset
  io.pmpWrite := PmpMsg.MemoryWrite
  // 普通 load 与 LR 读；AMO 读+写；SC 只写。
  io.pmpRead := !PmpMsg.MemoryWrite ||
    (PmpMsg.IsAtomic &&
      PmpMsg.AtomicOp =/= ysyx_26030103_AtomicOp.SC)
  io.memPaddr := paddrReg
  if (ParallelVipt) {
    io.memPaddrValid := paddrValidReg
  } else {
    io.memPaddrValid := state === done
  }
  io.memPaddrVaddr := paddrVaddrReg
  io.memKill := false.B

  if (!ParallelVipt) {
    // 原有 PIPT 路径：翻译完成后才发 LSU
    when(state === idle) {
      // 始终使用同一个握手条件，避免 in.ready 依赖 in.bits 形成组合环。
      io.in.ready := io.out.ready
      when(!isMem) {
        io.out.valid := io.in.valid
        io.out.bits := io.in.bits
      }.elsewhen(bypass) {
        // Bare/M 模式没有页表翻译，但 PMP 仍然必须检查；故障时消费输入
        // 并进入 fault，不能把访存指令直通给 LSU。
        when(io.in.valid && io.pmpFault) {
          faultReg := true.B
          faultCauseReg := Mux(io.in.bits.MemoryWrite, 7.U, 5.U)
          faultTvalReg := io.in.bits.ALUResult
          faultPCReg := io.in.bits.pc
          state := fault
        }.otherwise {
          io.out.valid := io.in.valid
          io.out.bits := io.in.bits
        }
      }.otherwise {
        when(io.in.fire) {
          msgReg := io.in.bits
          state := xreq
        }
      }
    }
    when(state === xreq) {
      io.req.valid := true.B
      io.req.bits.id := 1.U // LSU
      io.req.bits.vaddr := msgReg.ALUResult
      io.req.bits.access := Mux(msgReg.MemoryWrite,
        ysyx_26030103_AccessType.Store, ysyx_26030103_AccessType.Load)
      io.req.bits.privilege := io.privilege
      io.req.bits.sum := io.sum
      io.req.bits.mxr := io.mxr
      io.req.bits.satp := io.satp
      when(io.req.fire) { state := xwait }
    }
    when(state === xwait) {
      io.resp.ready := true.B
      when(io.resp.valid) {
        // 同 IFU：只接受 tval 与当前请求 VA 一致的响应，旧 walk 的迟到
        // 响应必须丢弃，不能把旧 PA 贴到新访存指令上。
        when(io.resp.bits.tval =/= msgReg.ALUResult) {
          // 丢弃迟到响应。
        }.elsewhen(io.resp.bits.fault) {
          faultReg := true.B
          faultCauseReg := io.resp.bits.cause
          faultTvalReg := msgReg.ALUResult
          faultPCReg := msgReg.pc
          state := fault
        }.elsewhen(io.pmpFault) {
          // 页表翻译成功，但物理地址被 PMP 拒绝：load=5，store/AMO=7。
          // PIPT 下请求尚未交给 LSU，因此不会产生任何总线副作用。
          faultReg := true.B
          faultCauseReg := Mux(msgReg.MemoryWrite, 7.U, 5.U)
          faultTvalReg := msgReg.ALUResult
          faultPCReg := msgReg.pc
          state := fault
        }.otherwise {
          paddrReg := io.resp.bits.paddr
          paddrVaddrReg := msgReg.ALUResult
          state := done
        }
      }
    }
    when(state === done) {
      // done 拍正把已翻译的访存指令送给 LSU；msgReg 仍被占用，不能在同拍
      // 再接收 EXU 的下一笔请求，否则 io.in.fire 会把它吞掉却不保存。
      // 这里固定反压一拍，回到 idle 后再受理后续指令。
      io.in.ready := false.B
      io.out.valid := true.B
      io.out.bits := msgReg
      io.out.bits.ALUResult := paddrReg
      when(io.out.fire) { state := idle }
    }
    when(io.flush) {
      state := idle
      faultReg := false.B
    }
  } else {
    // VIPT 并行路径
    // 只有 LSU 完全空闲时才受理新的内存指令，保证 paddrReg 在 DCache
    // 取用前不会被下一笔请求覆盖，同时让翻译故障的 kill 不会误伤更老指令。
    // 注意 in.ready 不能依赖 in.bits（否则 EXU 形成组合环），因此统一取
    // “LSU 空闲且下游可接收”；非访存指令在 LSU 忙时也会被暂缓。
    when(state === idle) {
      io.in.ready := io.out.ready && !io.memActive
      when(io.in.fire) {
        // 新请求受理时先撤销上一笔 PA，防止相同 VA 的旧 PA 被误用。
        paddrValidReg := false.B
      }
      when(!isMem) {
        // 非访存指令组合直通。
        io.out.valid := io.in.valid
        io.out.bits := io.in.bits
      }.elsewhen(io.in.fire) {
        // load/store 都先锁存原始 VA 并转发给 LSU：store 此时只进入写
        // 缓冲登记，不会发 AW/W；PA 由翻译结果带标签返回。
        when(bypass && io.pmpFault) {
          // Bare/M 模式没有翻译，但 PMP 必须先生效；不要进入 fwd，
          // 否则故障 load/store 会被 LSU 当成正常请求接收。
          faultReg := true.B
          faultCauseReg := Mux(io.in.bits.MemoryWrite, 7.U, 5.U)
          faultTvalReg := io.in.bits.ALUResult
          faultPCReg := io.in.bits.pc
          state := fault
        }.otherwise {
          msgReg := io.in.bits
          msgBypass := bypass
          state := fwd
          when(bypass) {
            // bypass 时 VA==PA，可以在 LSU 受理前就给出有效 PA。
            paddrReg := io.in.bits.ALUResult
            paddrVaddrReg := io.in.bits.VAddr
            paddrValidReg := true.B
          }
        }
      }
    }
    when(state === fwd) {
      // 这里交给 LSU 的 ALUResult 仍是 VA；物理地址只通过 memPaddr 提供。
      // store 也只是在此登记到 LSU 写缓冲，PA 有效前不会发 AW/W。
      io.out.valid := true.B
      io.out.bits := msgReg
      when(io.out.fire) {
        when(msgBypass) { state := done }
          .otherwise { state := xreq }
      }
    }
    when(state === xreq) {
      io.req.valid := true.B
      io.req.bits.id := 1.U
      io.req.bits.vaddr := msgReg.ALUResult
      io.req.bits.access := Mux(msgReg.MemoryWrite,
        ysyx_26030103_AccessType.Store, ysyx_26030103_AccessType.Load)
      io.req.bits.privilege := io.privilege
      io.req.bits.sum := io.sum
      io.req.bits.mxr := io.mxr
      io.req.bits.satp := io.satp
      when(io.req.fire) { state := xwait }
    }
    when(state === xwait) {
      io.resp.ready := true.B
      when(io.resp.valid) {
        when(io.resp.bits.tval =/= msgReg.ALUResult) {
          // 丢弃迟到的旧翻译响应；当前请求继续等待自己的响应。
        }.elsewhen(io.resp.bits.fault) {
          // load 已经进入 LSU。先撤销它和 DCache 的等待请求，
          // 下一拍 faultReg 作为精确 page fault 提交给 EXU/CSR。
          faultReg := true.B
          faultCauseReg := io.resp.bits.cause
          faultTvalReg := msgReg.ALUResult
          faultPCReg := msgReg.pc
          state := fault
        }.elsewhen(io.pmpFault) {
          // 翻译成功但 PMP 拒绝：与 page fault 一样撤销已转发给 LSU/DCache
          // 的请求，保证故障 load/store 不产生总线副作用。
          faultReg := true.B
          faultCauseReg := Mux(msgReg.MemoryWrite, 7.U, 5.U)
          faultTvalReg := msgReg.ALUResult
          faultPCReg := msgReg.pc
          state := fault
        }.otherwise {
          paddrReg := io.resp.bits.paddr
          paddrVaddrReg := msgReg.ALUResult
          paddrValidReg := true.B
          state := done
        }
      }
    }
    when(state === xwait) {
      // 只有属于当前请求的故障响应才能 kill LSU/DCache；迟到旧响应的
      // fault 位不得误伤正在等待翻译的新访存。PMP access fault 同样要
      // 撤销已经进入 LSU/DCache 的请求。
      io.memKill := io.resp.valid && io.resp.bits.tval === msgReg.ALUResult &&
        (io.resp.bits.fault || io.pmpFault)
    }
    when(io.flush && (state === xreq || state === xwait)) {
      // MMU/仲裁器 flush 会直接丢弃在途翻译且不再返回响应；此时继续等
      // xwait 会永久保持 busy。该请求尚未拿到 PA，属于可撤销的年轻
      // 访存，连同已交给 LSU/DCache 的登记一起 kill。
      io.memKill := true.B
    }
    when(state === done) {
      // load/store 都已交给 LSU；翻译结果保持到 LSU 完全排空，之后才允许
      // 下一笔请求覆盖 paddrReg。store 的 LSU 写缓冲会在 PA 标签匹配后
      // 自行完成总线提交，因此这里不再重复转发。
      when(!io.memActive) {
        paddrValidReg := false.B
        state := idle
      }
    }
    when(io.flush) {
      // xreq/xwait 的 MMU 请求已被 flush 丢弃，不会再返回响应；必须撤销
      // 并 kill 已登记到 LSU/DCache 的年轻访存。done 表示已有 PA 且可能
      // 已被 LSU 接收，仍按原逻辑保持到 memActive 撤销。
      when(state === idle || state === fwd || state === fault ||
          state === xreq || state === xwait) {
        state := idle
        faultReg := false.B
        paddrValidReg := false.B
      }
    }
    // LSU 已为当前指令提交 misaligned/PMP/总线陷阱：该指令不会再消费
    // 翻译结果。撤销在途翻译；迟到的 MMU 响应会在 idle 被 resp.ready
    // 排空，仲裁器在该响应消费前不会为 DMMU 发起下一笔请求。
    when(io.memTrap) {
      state := idle
      faultReg := false.B
      paddrValidReg := false.B
    }
  }
}
