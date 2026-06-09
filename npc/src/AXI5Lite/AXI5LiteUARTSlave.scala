package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteUARTSlave extends Module {
  val io = IO(new AXI5LiteSlave(32))
  io.ACLK := clock.asBool
  io.ARESETn := !reset.asBool
  val UartRegAddress = "h10000000".U(32.W)
  val OKAY = 0.U(2.W)
  val SLVERR = 2.U(2.W) // ARM这个命名真的是太有水平了，直接写SLAVE_ERROR都更清晰易懂
  // 烦死这个初始化了，真的是纯粹的浪费生命
  val AWValidReg = RegInit(false.B)
  val WValidReg = RegInit(false.B)
  val AWAddressReg = Reg(UInt(32.W))
  val WDataReg = Reg(UInt(32.W))
  val W_STRB_Reg = Reg(UInt(4.W))
  val BValidReg = RegInit(false.B)
  val BRESPReg = RegInit(OKAY)
  val RValidReg = RegInit(false.B)
  val RDataReg = RegInit(0.U(32.W))
  val RRESPReg = RegInit(OKAY)
  // 这行代码是AI写的，反正就是得在没有缓存写地址并且也没有等待B响应的时候，才接收新的AW
  val AWReady = !AWValidReg && !BValidReg
  val WReady =
    !WValidReg && !BValidReg // 这些已经下面一行都是一样的设计思路，反正就是因为没有做队列，所以一次只能做一件事情
  val ARReady = !RValidReg
  io.AW.AWREADY := AWReady
  io.W.WREADY := WReady
  io.B.BVALID := BValidReg
  io.B.BRESP := BRESPReg
  io.AR.ARREADY := ARReady
  io.R.RVALID := RValidReg
  io.R.RDATA := RDataReg
  io.R.RRESP := RRESPReg
  // 妈的能给io类一个fire，结果自己的fire还得自己手写
  val AWFire = io.AW.AWVALID && io.AW.AWREADY
  val WFire = io.W.WVALID && io.W.WREADY
  val BFire = io.B.BVALID && io.B.BREADY
  val ARFire = io.AR.ARVALID && io.AR.ARREADY
  val RFire = io.R.RVALID && io.R.RREADY
  when(AWFire) {
    AWAddressReg := io.AW.AWADDR
    AWValidReg := true.B
  }
  when(WFire) {
    WDataReg := io.W.WDATA
    W_STRB_Reg := io.W.WSTRB
    WValidReg := true.B
  }
  // 必须得都激活才可以跑
  when(AWValidReg && WValidReg && !BValidReg) {
    val HitUART = AWAddressReg === UartRegAddress
    // 和C++一样，就只看最低的一个字节输出
    when(HitUART && W_STRB_Reg(0)) {
      printf("%c", WDataReg(7, 0))
    }
    BRESPReg := Mux(HitUART, OKAY, SLVERR)
    BValidReg := true.B
    AWValidReg := false.B
    WValidReg := false.B
  }
  // B跑完了就关掉
  when(BFire) {
    BValidReg := false.B
  }
  // 收到读地址后，准备一个读的回复
  when(ARFire) {
    val IsUART = io.AR.ARADDR === UartRegAddress
    RDataReg := 0.U // 现在只要写，不需要读，所以直接返回0，这也是AI的建议
    RRESPReg := Mux(IsUART, OKAY, SLVERR)
    RValidReg := true.B
  }
  // 和B一样
  when(RFire) {
    RValidReg := false.B
  }
}
