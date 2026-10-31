package ysyx_26030103.common
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction.CtrlWidth
import _root_.ysyx_26030103.fpu.{ysyx_26030103_FpuOp, ysyx_26030103_FpuFmt, ysyx_26030103_FpuRm}

/** 译码级交付给执行级的消息；所有架构数据通路均为 XLEN 位。 */
class ysyx_26030103_IDUMessage(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"IDUMessage XLEN 非法: $XLEN")
  // 原始指令编码（16 位压缩指令零扩展），用于 trace 和非法指令 mtval。
  val Instruction = UInt(32.W)
  // 1=16 位压缩指令；IDU 已用展开后的 32 位形式完成译码。
  val IsCompressed = Bool()
  val pc = UInt(XLEN.W)
  // 顺序后继：16 位指令为 pc+2，32 位指令为 pc+4。
  val snpc = UInt(XLEN.W)

  val ALUCtrl = UInt(CtrlWidth.W)
  //刚加的M扩展
  val IsMDU = Bool()
  val MDUOp = UInt(ysyx_26030103_MDUOp.Width.W)
  val ALU_A = UInt(XLEN.W)
  val ALU_B = UInt(XLEN.W)

  val BranchA = UInt(XLEN.W)
  val BranchB = UInt(XLEN.W)
  val BranchFunct3 = UInt(3.W)
  val IsBranch = Bool()
  val IsJal = Bool()
  val IsJalr = Bool()
  val Immediate = UInt(XLEN.W)

  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)

  val MemoryValid = Bool()
  val MemoryWrite = Bool()
  val WidthSelect = UInt(2.W)
  val LoadSigned = Bool()
  // 与 EXUMessage 一致：保留完整 64 位 store 数据，供 RV64 SD/FSD 和
  // RV32D FSD 使用；整数 store 的高位为零扩展。
  val StoreData = UInt(64.W)
  // A 扩展：LR/SC/AMO 的原子访存标记与 funct5 操作号。
  val IsAtomic = Bool()
  val AtomicOp = UInt(ysyx_26030103_AtomicOp.Width.W)

  val IsCsrrw = Bool()
  val IsCsrrs = Bool()
  val IsCsrrc = Bool()
  val IsEcall = Bool()
  val IsEbreak = Bool()
  // 仿真环境专用的终止请求。它使用 custom-0 空间中的保留编码，和会
  // 正常进入 mtvec 的架构 EBREAK 完全分开。
  val IsSimHalt = Bool()
  val IsMret = Bool()
  val IsSret = Bool()
  val IsSfenceVma = Bool()
  // 普通 FENCE（opcode=0001111, funct3=000）。与 FenceI 分开，
  // 由 EXU 将其作为访存顺序屏障处理；它本身不刷新 ICache。
  val IsFence = Bool()
  val IsFenceI = Bool()
  val CSRAddress = UInt(12.W)
  // CSR 寄存器型指令中这是 rs1/寄存器数据；立即数型指令中这是
  // zimm 字段及其零扩展值。CSRRS[I]/CSRRC[I] 用字段是否为零判断
  // 是否具有写意图。
  val Rs1 = UInt(5.W)
  val Rs1Data = UInt(XLEN.W)

  // F/D 扩展
  // IsFP 覆盖所有 F/D 指令（含 load/store/mv），用于 mstatus.FS 的
  // retire 级 Dirty 更新；IsFPU 只表示需要进入浮点运算单元
  // （算术/比较/转换/分类/符号注入），不含 FLW/FSW/FLD/FSD。
  val IsFP = Bool()
  val IsFPU = Bool()
  val FpOp = UInt(ysyx_26030103_FpuOp.Width.W)
  val FpFmt = UInt(ysyx_26030103_FpuFmt.Width.W)
  val FpSrcFmt = UInt(ysyx_26030103_FpuFmt.Width.W)
  // 已按 frm 解析的舍入模式；DYN 不会进入 EXU/FPU。
  val FpRm = UInt(ysyx_26030103_FpuRm.Width.W)
  val FpIntWord = Bool()
  // 经 FPR 读口与转发后的三个浮点源操作数，统一保留 64 位。
  val FpRs1 = UInt(64.W)
  val FpRs2 = UInt(64.W)
  val FpRs3 = UInt(64.W)
  // 由于 EXUMessage 的 FLEN 固定为 64 的位模式视图，FPU 结果和
  // load 结果都用这组字段传给 WBU；FP64 用于 FLW 的 NaN-boxing。
  val FPRegisterWrite = Bool()
  val FPWriteData = UInt(64.W)
  val FP64 = Bool()

  // 异常标记:可能是IFU传下来的取指错(cause=1),也可能是IDU检测到的非法指令(cause=2)
  // 统一在EXU提交点处理,带异常标记的指令不得产生任何副作用
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
  val AccessFaultResp = UInt(2.W)
  // 分支预测信息透传到EXU
  val pred_taken = Bool()
  val pred_target = UInt(XLEN.W)
}
