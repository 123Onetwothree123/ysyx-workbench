package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

private[infra] trait AXI5XbarDecode {
  this: ysyx_26030103_AXI5Xbar =>

  private val busLaneBits = log2Ceil(DataWidth / 8)

  private def lastByte(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt
  ): UInt =
    ysyx_26030103_AXI5Burst.lastByte(address, len, size, burst)
  private def inRegion(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      region: ysyx_26030103_PMARegion
  ): Bool = {
    val extendedAddress = Cat(0.U(1.W), address)
    val lastAddress = lastByte(address, len, size, burst)
    extendedAddress >= BigInt(region.Base).U &&
    lastAddress < BigInt(region.EndExclusive).U
  }
  private def inAnyRegion(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      regions: Seq[ysyx_26030103_PMARegion]
  ): Bool =
    regions
      .map(inRegion(address, len, size, burst, _))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  def isCLINT(address: UInt, len: UInt, size: UInt, burst: UInt): Bool = {
    val lastAddress = lastByte(address, len, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), clintBase) &&
    lastAddress < Cat(0.U(1.W), clintEnd)
  }
  def isPLIC(address: UInt, len: UInt, size: UInt, burst: UInt): Bool = {
    val lastAddress = lastByte(address, len, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), plicBase) &&
    lastAddress < Cat(0.U(1.W), plicEnd)
  }
  private val readableRegions = PMARegions.filter(_.Readable)
  private val writableRegions = PMARegions.filter(_.Writable)
  // PLIC/CLINT 同样要过 PMA：要求某个可读/可写区域完整覆盖整个窗口。
  private def coversRegion(region: ysyx_26030103_PMARegion,
                           base: BigInt,
                           size: BigInt): Boolean =
    BigInt(region.Base) <= base &&
      BigInt(region.EndExclusive) >= base + size
  private val readableCLINT = readableRegions.exists(region =>
    coversRegion(region, CLINTBaseValue, CLINTSizeBytes))
  private val writableCLINT = writableRegions.exists(region =>
    coversRegion(region, CLINTBaseValue, CLINTSizeBytes))
  private val readablePLIC = readableRegions.exists(region =>
    coversRegion(region, PLICBaseValue, PLICSizeBytes))
  private val writablePLIC = writableRegions.exists(region =>
    coversRegion(region, PLICBaseValue, PLICSizeBytes))

  def decode(
      address: UInt,
      len: UInt,
      size: UInt,
      burst: UInt,
      isWrite: Boolean
  ): UInt = {
    val configSupported =
      size <= busLaneBits.U && (burst === 0.U || burst === 1.U)
    val permittedRegions = if (isWrite) writableRegions else readableRegions
    val clintPermitted = if (isWrite) writableCLINT else readableCLINT
    val plicPermitted = if (isWrite) writablePLIC else readablePLIC
    // PLIC 叶子只做单拍：不处理 AWLEN/WLAST，读响应只在首拍置 RLAST；
    // burst 非单拍或 PMA 未授权时必须本地判非法，不能按起始地址转发。
    val plicAddress = isPLIC(address, 0.U, size, burst)
    Mux(
      !configSupported,
      targetInvalid,
      Mux(
        clintPermitted.B && isCLINT(address, len, size, burst),
        targetCLINT,
        Mux(
          plicAddress,
          Mux(
            plicPermitted.B && len === 0.U && size <= 2.U,
            targetPLIC,
            targetInvalid
          ),
          Mux(
            inAnyRegion(address, len, size, burst, permittedRegions),
            targetSoCBus,
            targetInvalid
          )
        )
      )
    )
  }
}
