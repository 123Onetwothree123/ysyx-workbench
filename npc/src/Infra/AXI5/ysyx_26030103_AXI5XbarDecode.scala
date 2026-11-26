package ysyx_26030103.infra

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

private[infra] trait AXI5XbarDecode {
  this: ysyx_26030103_AXI5Xbar =>

  private val busLaneBits = log2Ceil(DataWidth / 8)

  private def lastByte(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt
  ): UInt =
    ysyx_26030103_AXI5Burst.lastByte(address, length, size, burst)
  private def inRegion(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt,
      region: ysyx_26030103_PMARegion
  ): Bool = {
    val extendedAddress = Cat(0.U(1.W), address)
    val lastAddress = lastByte(address, length, size, burst)
    extendedAddress >= BigInt(region.Base).U &&
    lastAddress < BigInt(region.EndExclusive).U
  }
  private def inAnyRegion(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt,
      regions: Seq[ysyx_26030103_PMARegion]
  ): Bool =
    ysyx_26030103_AnyOf(
      regions.map(inRegion(address, length, size, burst, _))
    )

  def isCLINT(address: UInt, length: UInt, size: UInt, burst: UInt): Bool = {
    val lastAddress = lastByte(address, length, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), CLINTBase) &&
    lastAddress < Cat(0.U(1.W), CLINTEnd)
  }
  def isPLIC(address: UInt, length: UInt, size: UInt, burst: UInt): Bool = {
    val lastAddress = lastByte(address, length, size, burst)
    Cat(0.U(1.W), address) >= Cat(0.U(1.W), PLICBase) &&
    lastAddress < Cat(0.U(1.W), PLICEnd)
  }
  private val readableRegions = PMARegions.filter(_.Readable)
  private val writableRegions = PMARegions.filter(_.Writable)
  // PLIC/CLINT 同样要过 PMA：要求某个可读/可写区域完整覆盖整个窗口。
  private def coversRegion(region: ysyx_26030103_PMARegion,
                           base: BigInt,
                           size: BigInt): Boolean =
    BigInt(region.Base) <= base &&
      BigInt(region.EndExclusive) >= base + size
  private val ReadableCLINT = readableRegions.exists(region =>
    coversRegion(region, CLINTBaseValue, CLINTSizeBytes))
  private val WritableCLINT = writableRegions.exists(region =>
    coversRegion(region, CLINTBaseValue, CLINTSizeBytes))
  private val ReadablePLIC = readableRegions.exists(region =>
    coversRegion(region, PLICBaseValue, PLICSizeBytes))
  private val WritablePLIC = writableRegions.exists(region =>
    coversRegion(region, PLICBaseValue, PLICSizeBytes))

  def decode(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt,
      isWrite: Boolean
  ): UInt = {
    val configSupported =
      size <= busLaneBits.U && (burst === 0.U || burst === 1.U)
    val permittedRegions = if (isWrite) writableRegions else readableRegions
    val CLINTPermitted = if (isWrite) WritableCLINT else ReadableCLINT
    val PLICPermitted = if (isWrite) WritablePLIC else ReadablePLIC
    // PLIC 叶子只做单拍：不处理 AWLEN/WLAST，读响应只在首拍置 RLAST；
    // burst 非单拍或 PMA 未授权时必须本地判非法，不能按起始地址转发。
    val PLICAddress = isPLIC(address, 0.U, size, burst)
    Mux(
      !configSupported,
      targetInvalid,
      Mux(
        CLINTPermitted.B && isCLINT(address, length, size, burst),
        TargetCLINT,
        Mux(
          PLICAddress,
          Mux(
            PLICPermitted.B && length === 0.U && size <= 2.U,
            TargetPLIC,
            targetInvalid
          ),
          Mux(
            inAnyRegion(address, length, size, burst, permittedRegions),
            targetSoCBus,
            targetInvalid
          )
        )
      )
    )
  }
}
