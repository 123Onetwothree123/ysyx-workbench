package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message.ysyx_26030103_IFUMessage
import _root_.ysyx_26030103.ysyx_26030103_AXI5._
class ysyx_26030103_IFU(resetAddr: Long = 0x30000000L) extends Module {
  val io = IO(new Bundle {
    // ok啊，终于把AXI5总线干出来了，这下这个接口他妈的可以直接连AXI了
    val InstructionBus = new ysyx_26030103_AXI5IO(32)
    val RedirectTarget = Input(UInt(32.W)) // 跳转地址
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool()) // 异常或者是中断的信号
    val ExceptionTarget = Input(UInt(32.W))
    val out = Decoupled(new ysyx_26030103_IFUMessage) // 丢给ysyx_26030103_IDU
    val DebugPC = Output(UInt(32.W))
    val DebugInstructions = Output(UInt(32.W))
    val AccessFault = Output(Bool())
    val AccessFaultResp = Output(UInt(2.W))
    val StallPipeline = Output(Bool())
    val StallAXI = Output(Bool())
    val StallAR = Output(Bool())
    val StallR  = Output(Bool())
    val StallIdle = Output(Bool())
  })
  val PCModule = Module(new ysyx_26030103_PC(resetAddr))
  val NextPCModule = Module(new ysyx_26030103_NextPC)
  val snpc = PCModule.io.ysyx_26030103_PC + 4.U(32.W)
  // 状态机，我真的是服了，谁能想到wait是scala和Java的object的内置方法，居然还不能用这个做变量名
  val states = Enum(4)
  val StatesIdle = states(0)
  val StatesWaitRequest = states(1)
  val StatesWaitResponse = states(2)
  val StatesHold = states(3)
  val state = RegInit(StatesIdle)
  val InstructionReg = RegInit(0.U(32.W))
  val PCReg = RegInit(0.U(32.W))
  val AccessFaultReg = RegInit(false.B)
  val AccessFaultRespReg = RegInit(0.U(2.W))
  io.AccessFault := AccessFaultReg
  io.AccessFaultResp := AccessFaultRespReg
  val InstructionResponseFire =
    io.InstructionBus.R.RVALID && io.InstructionBus.R.RREADY
  // 他妈的居然因为要防止报警转错，还要手动去给个初始值，哪个天才做的编译器
  // 写通道全关，ysyx_26030103_IFU只读不写
  io.InstructionBus.AW.AWVALID := false.B
  io.InstructionBus.AW.AWID := 0.U
  io.InstructionBus.AW.AWADDR := 0.U
  io.InstructionBus.AW.AWLEN := 0.U
  io.InstructionBus.AW.AWSIZE := 2.U
  io.InstructionBus.AW.AWBURST := 1.U
  io.InstructionBus.AW.AWPROT := 0.U
  io.InstructionBus.W.WVALID := false.B
  io.InstructionBus.W.WDATA := 0.U
  io.InstructionBus.W.WSTRB := 0.U
  io.InstructionBus.W.WLAST := false.B
  io.InstructionBus.B.BREADY := false.B
//只有这些用得到了
  io.InstructionBus.AR.ARVALID := false.B
  io.InstructionBus.AR.ARID := 0.U
  io.InstructionBus.AR.ARADDR := 0.U
  io.InstructionBus.AR.ARLEN := 0.U
  io.InstructionBus.AR.ARSIZE := 2.U
  io.InstructionBus.AR.ARBURST := 1.U
  io.InstructionBus.AR.ARPROT := 0.U
  io.InstructionBus.R.RREADY := false.B
  io.out.valid := false.B
  io.out.bits.Instruction := InstructionReg
  io.out.bits.pc := PCReg
  switch(state) {
    is(StatesIdle) {
      AccessFaultReg := false.B
      AccessFaultRespReg := 0.U
      io.InstructionBus.AR.ARVALID := true.B
      io.InstructionBus.AR.ARADDR := PCModule.io.ysyx_26030103_PC
      PCReg := PCModule.io.ysyx_26030103_PC
      state := Mux(
        io.InstructionBus.AR.ARREADY,
        StatesWaitResponse,
        StatesWaitRequest
      )
    }
    is(StatesWaitRequest) {
      io.InstructionBus.AR.ARVALID := true.B
      io.InstructionBus.AR.ARADDR := PCModule.io.ysyx_26030103_PC
      when(io.InstructionBus.AR.ARREADY) {
        state := StatesWaitResponse
      }
    }
    is(StatesWaitResponse) {
      io.InstructionBus.R.RREADY := true.B
      when(InstructionResponseFire) {
        when(io.InstructionBus.R.RRESP =/= 0.U) {
          AccessFaultReg := true.B
          AccessFaultRespReg := io.InstructionBus.R.RRESP
        }.otherwise {
          InstructionReg := io.InstructionBus.R.RDATA
        }
        state := StatesHold
      }
    }
    is(StatesHold) {
      io.out.valid := true.B
      when(
        io.out.valid && io.out.ready
      ) { // 只有ysyx_26030103_IDU真的接走了这条指令，ysyx_26030103_IFU这里才会去进入下一次的取指
        state := StatesIdle
      }
    }
  }
  NextPCModule.io.SNPC := snpc
  NextPCModule.io.Redirect := io.Redirect
  NextPCModule.io.RedirectTarget := io.RedirectTarget
  NextPCModule.io.ExceptionTaken := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget := io.ExceptionTarget
//最开始还是按Verilog的版本写法照抄下来的，后面发现可以这么写，最开始不这么写是怕这样的话，后面的单元不是还没跑完就ysyx_26030103_PC就迭代了
//后面发现写使能可以直接等时钟周期后再更新。
//他妈的，vsc插件的格式化文档居然抽风了，独立行没法格式化，不是，自己做的东西，自己没跑过吗
  PCModule.io.ysyx_26030103_NextPC := NextPCModule.io.ysyx_26030103_NextPC
  PCModule.io.PCEnable := NextPCModule.io.PCEnable && io.out.fire
//sdb
  io.DebugPC := PCModule.io.ysyx_26030103_PC
  io.DebugInstructions := InstructionReg
  val prev_state = RegInit(StatesIdle)
  prev_state := state
  when (state =/= prev_state) {
    printf(p"[IFU STATE ${prev_state}->${state}]\n")
  }
  io.StallPipeline := state === StatesHold
  when (state === StatesHold) {
    printf(p"[IFU HOLD pc=0x${Hexadecimal(PCReg)}]\n")
  }
  io.StallAXI := state === StatesWaitRequest || state === StatesWaitResponse
  io.StallAR := state === StatesWaitRequest
  io.StallR  := state === StatesWaitResponse
  io.StallIdle := state === StatesIdle
}
