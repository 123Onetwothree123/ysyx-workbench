package RV32I
import chisel3._
import chisel3.util._
import RV32I.Message.IFUMessage
class IFU extends Module {
  val io = IO(new Bundle {
    // ok啊，终于把AXI5-Lite总线干出来了，这下这个接口他妈的可以直接连AXI了
    val InstructionReadDATA = Input(UInt(32.W))
    val InstructionResponseValid = Input(Bool()) // 看现在从总线读回来的数据有没有效
    val RedirectTarget = Input(UInt(32.W)) // 跳转地址
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool()) // 异常或者是中断的信号
    val ExceptionTarget = Input(UInt(32.W))
    val out = Decoupled(new IFUMessage) // 丢给IDU
    // 发给总线的
    val InstructionRequestValid = Output(Bool())
    val InstructionRequestReady = Input(Bool())
    val InstructionResponseReady = Output(Bool())
    // 和message的pc不一样，也不对，就是理论上一样实际上不一定一样，就是这个pc是都会动的，但是message的pc的来
    // 源是当时取指的时候的pc，这个pc虽然理论上也应该是IFU跑的时候的pc但是也是，额，算了，就是反正来源不同，实
    // 际上可能一样可能不一样，我也不知道该不该做这个接口，主要是IFU了，先收了PC再说，接口就先搞一个，反正要删
    // 后面再删掉，就反正就是这个就是现在pc的最新的状态，就反正不会有时序的差距的就可以
    val PC = Output(UInt(32.W)) // 后面用AXI发送给外面的存储器，作为要取的指令的地址拿去取数据的
  })
  val PCModule = Module(new PC)
  val NextPCModule = Module(new NextPC)
  val snpc = PCModule.io.PC + 4.U(32.W)
  // 状态机，我真的是服了，谁能想到wait是scala和Java的object的内置方法，居然还不能用这个做变量名
  val states = Enum(4)
  val StatesIdle = states(0)
  val StatesWaitRequest = states(1)
  val StatesWaitResponse = states(2)
  val StatesHold = states(3)
  val state = RegInit(StatesIdle)
  val InstructionReg = RegInit(0.U(32.W))
  val PCReg = RegInit(0.U(32.W))
  val InstructionResponseFire =
    io.InstructionResponseValid && io.InstructionResponseReady
  // 他妈的居然因为要防止报警转错，还要手动去给个初始值，哪个天才做的编译器
  io.InstructionRequestValid := false.B
  io.InstructionResponseReady := false.B
  io.out.valid := false.B
  io.out.bits.Instruction := InstructionReg
  io.out.bits.pc := PCReg
  switch(state) {
    is(StatesIdle) {
      io.InstructionRequestValid := true.B
      PCReg := PCModule.io.PC
      state := Mux(
        io.InstructionRequestReady,
        StatesWaitResponse,
        StatesWaitRequest
      )
    }
    is(StatesWaitRequest) {
      io.InstructionRequestValid := true.B
      when(io.InstructionRequestReady) {
        state := StatesWaitResponse
      }
    }
    is(StatesWaitResponse) {
      io.InstructionResponseReady := true.B
      when(InstructionResponseFire) {
        InstructionReg := io.InstructionReadDATA
        state := StatesHold
      }
    }
    is(StatesHold) {
      io.out.valid := true.B
      when(io.out.valid && io.out.ready) { // 只有IDU真的接走了这条指令，IFU这里才会去进入下一次的取指
        state := StatesIdle
      }
    }
  }
  NextPCModule.io.SNPC := snpc
  NextPCModule.io.Redirect := io.Redirect
  NextPCModule.io.RedirectTarget := io.RedirectTarget
  NextPCModule.io.ExceptionTaken := io.ExceptionTaken
  NextPCModule.io.ExceptionTarget := io.ExceptionTarget
//最开始还是按Verilog的版本写法照抄下来的，后面发现可以这么写，最开始不这么写是怕这样的话，后面的单元不是还没跑完就PC就迭代了
//后面发现写使能可以直接等时钟周期后再更新。
//他妈的，vsc插件的格式化文档居然抽风了，独立行没法格式化，不是，自己做的东西，自己没跑过吗
  PCModule.io.NextPC := NextPCModule.io.NextPC
  PCModule.io.PCEnable := NextPCModule.io.PCEnable && io.out.fire

  io.PC := PCModule.io.PC
}
