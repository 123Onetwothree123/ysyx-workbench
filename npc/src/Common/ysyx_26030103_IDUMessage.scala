package ysyx_26030103.common
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction.CtrlWidth
class ysyx_26030103_IDUMessage extends Bundle {
  val Instruction = UInt(32.W)
  val pc = UInt(32.W)
  val snpc = UInt(32.W)

  val ALUCtrl = UInt(CtrlWidth.W)
  //刚加的M扩展
  val IsMDU = Bool()
  val MDUOp = UInt(ysyx_26030103_MDUOp.Width.W)
  val ALU_A = UInt(32.W)
  val ALU_B = UInt(32.W)

  val BranchA = UInt(32.W)
  val BranchB = UInt(32.W)
  val BranchFunct3 = UInt(3.W)
  val IsBranch = Bool()
  val IsJal = Bool()
  val IsJalr = Bool()
  val Immediate = UInt(32.W)

  val Rd = UInt(5.W)
  val RegisterWrite = Bool()
  val WBSelect = UInt(2.W)

  val MemoryValid = Bool()
  val MemoryWrite = Bool()
  val WidthSelect = UInt(2.W)
  val LoadSigned = Bool()
  val StoreData = UInt(32.W)

  val IsCsrrw = Bool()
  val IsCsrrs = Bool()
  val IsEcall = Bool()
  val IsEbreak = Bool()
  val IsMret = Bool()
  // 普通 FENCE（opcode=0001111, funct3=000）。与 FenceI 分开，
  // 由 EXU 将其作为访存顺序屏障处理；它本身不刷新 ICache。
  val IsFence = Bool()
  val IsFenceI = Bool()
  val CSRAddress = UInt(12.W)
  val Rs1 = UInt(5.W)
  val Rs1Data = UInt(32.W)

  // ALU 解码器检测到的非法指令编码
  val ALUCDIllegal = Bool()

  // 异常标记:可能是IFU传下来的取指错(cause=1),也可能是IDU检测到的非法指令(cause=2)
  // 统一在EXU提交点处理,带异常标记的指令不得产生任何副作用
  val ExceptionValid = Bool()
  val ExceptionCause = UInt(4.W)
  // 分支预测信息透传到EXU
  val pred_taken = Bool()
  val pred_target = UInt(32.W)
}
