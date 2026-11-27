package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_LoadFormat

private[mem] trait LSUHelpers {
  this: ysyx_26030103_LSU =>

  private[mem] def addressOf(value: UInt): UInt = value(AddressWidth - 1, 0)

  private[mem] def addressOf(value: UInt, addressWidth: Int): UInt =
    value(addressWidth - 1, 0)

  private[mem] case class AccessPlan(
      last: UInt,
      singleGranule: Bool,
      crossPage: Bool,
      page1Va: UInt,
      cause46: Bool,
      cause57: Bool,
      misaligned: Bool,
      needsSegments: Bool
  )

  private[mem] def Page1Va(va: UInt, addressWidth: Int): UInt =
    Cat(va(addressWidth - 1, 12) + 1.U, 0.U(12.W))

  private[mem] def CrossesPage(
      upper: UInt,
      base: UInt,
      addressWidth: Int
  ): Bool =
    upper(addressWidth - 1, 12) =/= base(addressWidth - 1, 12)

  private[mem] def Page1Paddr(
      base: UInt,
      page1Pa: UInt,
      page1Valid: Bool,
      va: UInt,
      addressWidth: Int
  ): UInt = {
    val page1Base = Mux(
      page1Valid,
      page1Pa & ~"hfff".U(addressWidth.W),
      (base & ~"hfff".U(addressWidth.W)) + "h1000".U(addressWidth.W)
    )
    page1Base | va(11, 0)
  }

  private[mem] def PlanAccess(
      va: UInt,
      pa: UInt,
      width: UInt,
      memoryValid: Bool,
      isAtomic: Bool,
      enable: Bool,
      mmioFault: Bool,
      ncSplit: Bool,
      addressWidth: Int,
      granuleBytes: Int
  ): AccessPlan = {
    val bytes = addressOf(va, addressWidth)
    val physical = addressOf(pa, addressWidth)
    val last = bytes + accessByteSize(width) - 1.U
    val (mapped, cacheable) = classifyPMA(physical, memoryValid)
    val mmio = mapped && !cacheable
    val naturalMisaligned = addressIsMisaligned(physical, width)
    val cause46 = memoryValid && naturalMisaligned &&
      (!enable || isAtomic ||
        (mmio && !mmioFault && !ncSplit))
    val cause57 = memoryValid && naturalMisaligned && enable &&
      !isAtomic && mmio && mmioFault
    val misaligned = cause46 || cause57
    val singleGranule =
      (bytes & ~((granuleBytes - 1).U(addressWidth.W))) ===
        (last & ~((granuleBytes - 1).U(addressWidth.W)))
    val needsSegments = memoryValid && !isAtomic && enable &&
      !misaligned && (naturalMisaligned || !singleGranule)
    AccessPlan(
      last = last,
      singleGranule = singleGranule,
      crossPage = CrossesPage(last, bytes, addressWidth),
      page1Va = Page1Va(bytes, addressWidth),
      cause46 = cause46,
      cause57 = cause57,
      misaligned = misaligned,
      needsSegments = needsSegments
    )
  }

  private[mem] def atomicSignExtend32(value: UInt): UInt =
    if (XLEN == 32) value
    else Cat(Fill(XLEN - 32, value(31)), value)

  // 自然对齐要求，仅用于 AMO/LR/SC。普通访存允许非对齐。
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

  // 访问字节数：1/2/4/8。
  private[mem] def accessByteSize(width: UInt): UInt = 1.U << width

  // 覆盖 [address, address+size) 的拆分粒度块数，由配置粒度决定。
  private[mem] def accessGranuleCount(
      address: UInt,
      width: UInt,
      GranuleLog2: Int
  ): UInt = {
    val offset = address(GranuleLog2 - 1, 0)
    (((offset + accessByteSize(width)) - 1.U) >> GranuleLog2) +& 1.U
  }

  // 用按地址递增保存的粒度块拼出非对齐 load 结果。
  private[mem] def assembleSegmentedLoad(
      words: Seq[UInt],
      address: UInt,
      width: UInt,
      signed: Bool,
      GranuleLog2: Int
  ): UInt = {
    val stream = Cat(words.reverse)
    val shifted = stream >> (address(GranuleLog2 - 1, 0) << 3)
    def extend(value: UInt, bits: Int): UInt =
      if (XLEN == bits) value
      else Mux(
        signed,
        Cat(Fill(XLEN - bits, value(bits - 1)), value),
        Cat(0.U((XLEN - bits).W), value)
      )
    val byteResult = extend(shifted(7, 0), 8)
    val halfResult = extend(shifted(15, 0), 16)
    val wordResult = extend(shifted(31, 0), 32)
    MuxLookup(width, wordResult)(
      Seq(0.U -> byteResult, 1.U -> halfResult, 2.U -> wordResult,
        3.U -> shifted(63, 0))
    )
  }

  // 非对齐 store 中第 SegmentIndex 个粒度块的写数据与字节掩码。
  private[mem] def segmentedStorePayload(
      storeData: UInt,
      address: UInt,
      width: UInt,
      SegmentIndex: UInt,
      GranuleLog2: Int
  ): (UInt, UInt) = {
    val GranuleBytes = 1 << GranuleLog2
    val SegmentAddress = Cat(address(AddressWidth - 1, GranuleLog2),
      0.U(GranuleLog2.W)) + (SegmentIndex << GranuleLog2)
    val endAddress = address + accessByteSize(width)
    val lanes = (0 until GranuleBytes).map { lane =>
      val byteAddress = SegmentAddress + lane.U
      val valid = byteAddress >= address && byteAddress < endAddress
      val byteOffset = byteAddress - address
      val byteData = (storeData >> (byteOffset << 3))(7, 0)
      (Mux(valid, byteData, 0.U(8.W)), valid)
    }
    val data = Cat(lanes.map(_._1).reverse)
    val strobe = Cat(lanes.map(_._2).reverse)
    (data, strobe)
  }

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
          is("b01".U) {
            data := Cat(0.U(8.W), storeData(15, 0), 0.U(8.W))
            mask := "b0110".U
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

  // LB/LH/LW 按 Signed 扩展到 XLEN，LBU/LHU/LWU 零扩展；LD/FLD 直接拼 64 位。
  private[mem] def formatLoad(
      wordLow: UInt,
      wordHigh: UInt,
      address: UInt,
      width: UInt,
      signed: Bool
  ): UInt = ysyx_26030103_LoadFormat.FormatLoad(
    wordLow,
    wordHigh,
    address,
    width,
    signed,
    XLEN,
    WideLoad = true
  )
}
