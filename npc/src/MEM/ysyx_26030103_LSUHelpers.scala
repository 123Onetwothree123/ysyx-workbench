package ysyx_26030103.mem

import chisel3._
import chisel3.util._

private[mem] trait LSUHelpers {
  this: ysyx_26030103_LSU =>

  private[mem] def busWord(data: UInt, address: UInt): UInt =
    if (busWide)
      Mux(address(2), Cat(data(31, 0), 0.U(32.W)),
        Cat(0.U(32.W), data(31, 0)))
    else data(31, 0)
  private[mem] def busStrobe(strobe: UInt, address: UInt): UInt =
    if (busWide)
      Mux(address(2), Cat(strobe(3, 0), 0.U(4.W)),
        Cat(0.U(4.W), strobe(3, 0)))
    else strobe(3, 0)
  private[mem] def busSelectWord(data: UInt, address: UInt): UInt =
    if (busWide) Mux(address(2), data(63, 32), data(31, 0))
    else data(31, 0)

  private[mem] def addressOf(value: UInt): UInt = value(AddressWidth - 1, 0)

  private[mem] def atomicSignExtend32(value: UInt): UInt =
    if (XLEN == 32) value
    else Cat(Fill(XLEN - 32, value(31)), value)

  // LD/SD 要求 8 字节对齐；LW/SW 要求 4 字节；LH/SH、LB/SB 依次降低。
  private[mem] def addressIsMisaligned(address: UInt, width: UInt): Bool =
    Mux(
      width === "b11".U,
      address(2, 0) =/= 0.U,
      Mux(
        width === "b10".U,
        address(1, 0) =/= 0.U,
        Mux(width === "b01".U, address(0), false.B)
      )
    )

  private[mem] def axiSizeFor(width: UInt): UInt = MuxLookup(width, 2.U(3.W))(
    Seq("b00".U -> 0.U(3.W), "b01".U -> 1.U(3.W), "b10".U -> 2.U(3.W),
      "b11".U -> 2.U(3.W))
  )

  private[mem] def alignStore(
      storeData: UInt,
      address: UInt,
      width: UInt
  ): (UInt, UInt) = {
    val data = WireDefault(storeData(31, 0))
    val mask = WireDefault("b1111".U(4.W))
    switch(width) {
      is("b00".U) {
        switch(address(1, 0)) {
          is("b00".U) {
            data := Cat(0.U(24.W), storeData(7, 0))
            mask := "b0001".U
          }
          is("b01".U) {
            data := Cat(
              0.U(16.W),
              storeData(7, 0),
              0.U(8.W)
            )
            mask := "b0010".U
          }
          is("b10".U) {
            data := Cat(
              0.U(8.W),
              storeData(7, 0),
              0.U(16.W)
            )
            mask := "b0100".U
          }
          is("b11".U) {
            data := Cat(storeData(7, 0), 0.U(24.W))
            mask := "b1000".U
          }
        }
      }
      is("b01".U) {
        switch(address(1, 0)) {
          is("b00".U) {
            data := Cat(
              0.U(16.W),
              storeData(15, 0)
            )
            mask := "b0011".U
          }
          is("b10".U) {
            data := Cat(
              storeData(15, 0),
              0.U(16.W)
            )
            mask := "b1100".U
          }
        }
      }
      is("b10".U) {
        data := storeData(31, 0)
        mask := "b1111".U
      }
      is("b11".U) {
        // SD 的第一拍是低 32 位；第二拍由写缓冲的 wbDataHi 提供。
        data := storeData(31, 0)
        mask := "b1111".U
      }
    }
    (data, mask)
  }

  // LB/LH/LW 按 Signed 扩展，LBU/LHU/LWU 零扩展，LD 直接返回 64 位。
  private[mem] def formatLoad(
      wordLow: UInt,
      wordHigh: UInt,
      address: UInt,
      width: UInt,
      signed: Bool
  ): UInt = {
    val word = wordLow(31, 0)
    val rawWide = Cat(wordHigh(31, 0), word)
    val byteData = MuxLookup(address(1, 0), word(7, 0))(
      Seq(1.U -> word(15, 8), 2.U -> word(23, 16), 3.U -> word(31, 24))
    )
    val halfData = Mux(address(1), word(31, 16), word(15, 0))
    def extend(value: UInt, bits: Int): UInt =
      if (XLEN == bits) {
        value
      } else {
        Mux(signed, Cat(Fill(XLEN - bits, value(bits - 1)), value),
          Cat(0.U((XLEN - bits).W), value))
      }
    val byteResult = extend(byteData, 8)
    val halfResult = extend(halfData, 16)
    val wordResult = extend(word, 32)
    MuxLookup(width, wordResult)(
      Seq(0.U -> byteResult, 1.U -> halfResult, 2.U -> wordResult,
        3.U -> rawWide)
    )
  }
}
