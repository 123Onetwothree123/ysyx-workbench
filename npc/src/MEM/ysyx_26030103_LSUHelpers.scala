package ysyx_26030103.mem

import chisel3._
import chisel3.util._

/** LSU 组合辅助：总线字节/掩码选择、地址裁剪、对齐与 load 数据格式化。 */
private[mem] trait LSUHelpers {
  this: ysyx_26030103_LSU =>

  private[mem] def BusWord(Data: UInt, Address: UInt): UInt =
    if (BusWide)
      Mux(Address(2), Cat(Data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), Data(31, 0)))
    else Data(31, 0)
  private[mem] def BusStrb(Strb: UInt, Address: UInt): UInt =
    if (BusWide)
      Mux(Address(2), Cat(Strb(3, 0), 0.U(4.W)),
        Cat(0.U(4.W), Strb(3, 0)))
    else Strb(3, 0)
  private[mem] def BusSelectWord(Data: UInt, Address: UInt): UInt =
    if (BusWide) Mux(Address(2), Data(63, 32), Data(31, 0))
    else Data(31, 0)

  private[mem] def AddrOf(value: UInt): UInt = value(AddressWidth - 1, 0)

  private[mem] def AtomicSext32(Value: UInt): UInt =
    if (XLEN == 32) Value
    else Cat(Fill(XLEN - 32, Value(31)), Value)

  // LD/SD 要求 8 字节对齐；LW/SW 要求 4 字节；LH/SH、LB/SB 依次降低。
  private[mem] def AddressIsMisaligned(Address: UInt, Width: UInt): Bool =
    Mux(
      Width === "b11".U,
      Address(2, 0) =/= 0.U,
      Mux(
        Width === "b10".U,
        Address(1, 0) =/= 0.U,
        Mux(Width === "b01".U, Address(0), false.B)
      )
    )

  // 总线事务FSM(原LSU逻辑)
  private[mem] def AXISizeFor(Width: UInt): UInt = MuxLookup(Width, 2.U(3.W))(
    Seq("b00".U -> 0.U(3.W), "b01".U -> 1.U(3.W), "b10".U -> 2.U(3.W),
      "b11".U -> 2.U(3.W))
  )

  private[mem] def AlignStore(
      StoreData: UInt,
      Address: UInt,
      Width: UInt
  ): (UInt, UInt) = {
    val Data = WireDefault(StoreData(31, 0))
    val Mask = WireDefault("b1111".U(4.W))
    switch(Width) {
      is("b00".U) {
        switch(Address(1, 0)) {
          is("b00".U) {
            Data := Cat(0.U(24.W), StoreData(7, 0))
            Mask := "b0001".U
          }
          is("b01".U) {
            Data := Cat(
              0.U(16.W),
              StoreData(7, 0),
              0.U(8.W)
            )
            Mask := "b0010".U
          }
          is("b10".U) {
            Data := Cat(
              0.U(8.W),
              StoreData(7, 0),
              0.U(16.W)
            )
            Mask := "b0100".U
          }
          is("b11".U) {
            Data := Cat(StoreData(7, 0), 0.U(24.W))
            Mask := "b1000".U
          }
        }
      }
      is("b01".U) {
        switch(Address(1, 0)) {
          is("b00".U) {
            Data := Cat(
              0.U(16.W),
              StoreData(15, 0)
            )
            Mask := "b0011".U
          }
          is("b10".U) {
            Data := Cat(
              StoreData(15, 0),
              0.U(16.W)
            )
            Mask := "b1100".U
          }
        }
      }
      is("b10".U) {
        Data := StoreData(31, 0)
        Mask := "b1111".U
      }
      is("b11".U) {
        // SD 的第一拍是低 32 位；第二拍由写缓冲的 wbDataHi 提供。
        Data := StoreData(31, 0)
        Mask := "b1111".U
      }
    }
    (Data, Mask)
  }

  // 将最多 64 位的原始小端数据格式化为 XLEN 架构结果。
  //   LB/LH/LW 按 Signed 做符号/零扩展；
  //   LBU/LHU/LWU 零扩展；
  //   LD 直接返回 64 位，忽略 Signed。
  private[mem] def FormatLoad(
      WordLow: UInt,
      WordHigh: UInt,
      Address: UInt,
      Width: UInt,
      Signed: Bool
  ): UInt = {
    val Word = WordLow(31, 0)
    val RawWide = Cat(WordHigh(31, 0), Word)
    val ByteData = MuxLookup(Address(1, 0), Word(7, 0))(
      Seq(1.U -> Word(15, 8), 2.U -> Word(23, 16), 3.U -> Word(31, 24))
    )
    val HalfData = Mux(Address(1), Word(31, 16), Word(15, 0))
    def Extend(Value: UInt, Bits: Int): UInt =
      if (XLEN == Bits) {
        Value
      } else {
        Mux(Signed, Cat(Fill(XLEN - Bits, Value(Bits - 1)), Value),
          Cat(0.U((XLEN - Bits).W), Value))
      }
    val ByteResult = Extend(ByteData, 8)
    val HalfResult = Extend(HalfData, 16)
    val WordResult = Extend(Word, 32)
    MuxLookup(Width, WordResult)(
      Seq(0.U -> ByteResult, 1.U -> HalfResult, 2.U -> WordResult,
        3.U -> RawWide)
    )
  }
}
