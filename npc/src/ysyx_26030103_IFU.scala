package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message.ysyx_26030103_IFUMessage
import _root_.ysyx_26030103.ysyx_26030103_AXI5._
class ysyx_26030103_IFU extends Module {
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
  })
  val PCModule = Module(new ysyx_26030103_PC)
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
  val PrefetchValid = RegInit(false.B)
  val PrefetchInstruction = RegInit(0.U(32.W))
  val PrefetchPC = RegInit(0.U(32.W))
  val PrefetchSent = RegInit(false.B)
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
  // 默认接收 R 通道数据（为 prefetch 做准备）
  io.InstructionBus.R.RREADY := false.B
  // 默认不发出 AR 请求
  io.InstructionBus.AR.ARVALID := false.B
  io.InstructionBus.AR.ARADDR := NextPCModule.io.ysyx_26030103_NextPC
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
        PrefetchSent := false.B
        state := StatesHold
      }
    }
    is(StatesHold) {
      io.out.valid := true.B
      io.InstructionBus.R.RREADY := true.B
      // 在 Hold 期间发起下一次取指
      when(!PrefetchSent) {
        io.InstructionBus.AR.ARVALID := true.B
        when(io.InstructionBus.AR.ARREADY) {
          PrefetchSent := true.B
        }
      }
      // R 通道在 Hold 期间返回的是预取的数据
      when(InstructionResponseFire) {
        PrefetchInstruction := io.InstructionBus.R.RDATA
        PrefetchPC := NextPCModule.io.ysyx_26030103_NextPC
        PrefetchValid := true.B
      }
      when(io.out.valid && io.out.ready) {
        when(PrefetchValid) {
          InstructionReg := PrefetchInstruction
          PCReg := PrefetchPC
          PrefetchValid := false.B
          PrefetchSent := false.B
          state := StatesHold
        }.elsewhen(PrefetchSent) {
          state := StatesWaitResponse
        }.otherwise {
          state := StatesIdle
        }
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
  // 跳转冲刷时，丢弃预取缓存
  when(io.Redirect) {
    PrefetchValid := false.B
    PrefetchSent := false.B
  }
//sdb
  io.DebugPC := PCModule.io.ysyx_26030103_PC
  io.DebugInstructions := InstructionReg
  io.StallPipeline := state === StatesHold
  io.StallAXI := state === StatesWaitRequest || state === StatesWaitResponse
}
