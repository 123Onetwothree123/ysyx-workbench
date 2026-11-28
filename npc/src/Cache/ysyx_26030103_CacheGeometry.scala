package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.infra.ysyx_26030103_AXI5IO

private[cache] final case class CacheParams(
    enable: Boolean,
    blockSizeLog2: Int,
    indexBits: Int,
    addressWidth: Int,
    busDataWidth: Int,
    viptAliasSafe: Boolean,
    viptAliasScanMode: String,
    viptAliasScanWidth: Int,
    viptAliasDirectoryEntries: Int,
    dmaSnoop: Boolean,
    dmaStartAddress: UInt,
    dmaEndAddress: UInt
)

private[cache] trait CacheGeometry {
  protected def CacheParamsOf: CacheParams
  protected val params: CacheParams = CacheParamsOf

  val tagBits: Int =
    if (params.viptAliasSafe) params.addressWidth - params.blockSizeLog2
    else params.addressWidth - params.indexBits - params.blockSizeLog2
  protected def tagOf(address: UInt): UInt =
    if (params.viptAliasSafe) address(params.addressWidth - 1, params.blockSizeLog2)
    else address(params.addressWidth - 1, params.indexBits + params.blockSizeLog2)
  val NumberBlocks: Int = 1 << params.indexBits
  val wordsPerBlock: Int = 1 << (params.blockSizeLog2 - 2)
  val wordsPerBeat: Int = params.busDataWidth / 32
  val beatsPerBlock: Int = wordsPerBlock / wordsPerBeat
  require(wordsPerBlock % wordsPerBeat == 0,
    s"块内字数($wordsPerBlock)不能被每拍字数($wordsPerBeat)整除")
  protected val aliasScanActive: Boolean =
    params.viptAliasSafe && params.enable &&
      ysyx_26030103_VIPTAliasScan.isScan(params.viptAliasScanMode)
  protected val aliasAllSets: Boolean =
    params.viptAliasSafe && params.enable &&
      ysyx_26030103_VIPTAliasScan.isAllSets(params.viptAliasScanMode)
  protected val aliasDirectoryMode: Boolean =
    params.viptAliasSafe && params.enable &&
      ysyx_26030103_VIPTAliasScan.isDirectory(params.viptAliasScanMode)
  protected val scanWidth: Int =
    ysyx_26030103_VIPTAliasScan.resolveScanWidth(
      params.viptAliasScanMode,
      params.viptAliasScanWidth,
      NumberBlocks
    )
  protected val directoryEntries: Int =
    if (params.viptAliasDirectoryEntries <= 0) NumberBlocks
    else {
      require(params.viptAliasDirectoryEntries <= NumberBlocks,
        s"VIPT alias 目录项数(${params.viptAliasDirectoryEntries}) 不能超过 set 数($NumberBlocks)")
      params.viptAliasDirectoryEntries
    }
  protected val arrayBlocks: Int = if (params.enable) NumberBlocks else 1
  val valid = RegInit(VecInit(Seq.fill(arrayBlocks)(false.B)))
  val tag = Reg(Vec(arrayBlocks, UInt(tagBits.W)))
  protected def LineOf(address: UInt): UInt =
    address(params.addressWidth - 1, params.blockSizeLog2)
}

private[cache] trait CacheAliasSupport { this: CacheGeometry =>
  protected val DMASnoopActive: Boolean = params.dmaSnoop && params.enable
  protected val DMAStartLine: UInt =
    params.dmaStartAddress(params.addressWidth - 1, params.blockSizeLog2)
  protected val DMAEndLine: UInt =
    params.dmaEndAddress(params.addressWidth - 1, params.blockSizeLog2)

  protected def invalidateAliasCopies(aliasTag: UInt, targetIndex: UInt): Unit = {
    if (aliasAllSets) {
      for (i <- valid.indices) {
        when(valid(i) && tag(i) === aliasTag && i.U =/= targetIndex) {
          valid(i) := false.B
        }
      }
    }
  }

  protected def dmaLineOfSet(set: Int): UInt =
    if (params.viptAliasSafe) tag(set)
    else Cat(tag(set), set.U(params.indexBits.W))
  protected def dmaLineInRange(line: UInt): Bool =
    line >= DMAStartLine && line <= DMAEndLine
  // CPU 请求/已受理请求是否落在 DMA 正在改写的物理行范围内。两个 core
  // 共用这条判定，但失效动作和响应撤回仍由各自的时序实现。
  protected def dmaSnoopLineHitNow(dmaValid: Bool, address: UInt): Bool =
    if (!DMASnoopActive) false.B
    else dmaValid && dmaLineInRange(LineOf(address))
  // 在飞 refill 是否与 DMA 范围重叠。refillBusy 由各 core 的 FSM 状态
  // 组合而成；这里只统一“busy + 行范围”的判定，不要求当前 DMA 事件
  // valid —— 原实现把它当作在途事务属性，供 ready/replay 一起使用。
  protected def dmaSnoopRefillHitNow(
      refillBusy: Bool,
      address: UInt
  ): Bool =
    if (!DMASnoopActive) false.B
    else refillBusy && dmaLineInRange(LineOf(address))
  protected def DMASnoopHitVec: UInt =
    if (!DMASnoopActive) 0.U(arrayBlocks.W)
    else
      VecInit(valid.indices.map { set =>
        valid(set) && dmaLineInRange(dmaLineOfSet(set))
      }).asUInt

  protected val aliasDirectory: Option[ysyx_26030103_VIPTAliasDirectory] =
    if (aliasDirectoryMode) {
      Some(
        Module(
          new ysyx_26030103_VIPTAliasDirectory(
            LineBits = params.addressWidth - params.blockSizeLog2,
            SetBits = params.indexBits,
            NumSets = NumberBlocks,
            Entries = directoryEntries
          )
        )
      )
    } else {
      None
    }

  protected def AXIDefaults(
      axi: ysyx_26030103_AXI5IO,
      readReady: Bool,
      readSize: UInt
  ): Unit = {
    axi.AW.AWVALID := false.B
    axi.AW.AWID := 0.U
    axi.AW.AWADDR := 0.U
    axi.AW.AWLEN := 0.U
    axi.AW.AWSIZE := 2.U
    axi.AW.AWBURST := 1.U
    axi.AW.AWCACHE := 0.U
    axi.AW.AWQOS := 0.U
    axi.AW.AWPROT := 0.U
    axi.W.WVALID := false.B
    axi.W.WDATA := 0.U
    axi.W.WSTRB := 0.U
    axi.W.WLAST := false.B
    axi.B.BREADY := false.B
    axi.AR.ARVALID := false.B
    axi.AR.ARID := 0.U
    axi.AR.ARADDR := 0.U
    axi.AR.ARLEN := 0.U
    axi.AR.ARSIZE := readSize
    axi.AR.ARBURST := 1.U
    axi.AR.ARPROT := 0.U
    axi.AR.ARCACHE := 0.U
    axi.AR.ARQOS := 0.U
    axi.R.RREADY := readReady
  }
}
