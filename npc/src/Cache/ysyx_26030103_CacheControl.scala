package ysyx_26030103.cache

import chisel3._
import chisel3.util._

/** ICache/DCache 单 MSHR 回填的共享控制谓词。
  *
  * 只抽纯组合判定：drain 超时、必须为最后一拍却缺 RLAST、提前 RLAST、
  * RRESP 错误码归一化。refill/drain 的状态寄存器、响应寄存器和
  * Reg/SRAM 时序仍留在各自 core，避免把两套微架构差异硬合并。
  */
private[cache] object ysyx_26030103_CacheControl {
  /** 排空计时达到配置阈值。timer 从 0 开始，每拍 +1。 */
  def DrainTimedOut(timer: UInt, timeout: Int): Bool =
    timer === timeout.U

  /** 通道已安静足够久，可放弃一笔无法重新同步的违规事务。 */
  def DrainRetryQuiet(timer: UInt, timeout: Int, readValid: Bool): Bool =
    DrainTimedOut(timer, timeout) && !readValid

  /** 本拍 R 握手完成且带 RLAST。 */
  def ReadLastBeat(readValid: Bool, readLast: Bool): Bool =
    readValid && readLast

  /** 本拍必须结束事务，却没有 RLAST：AXI 协议违规。 */
  def MissingLast(
      readValid: Bool,
      readLast: Bool,
      mustBeLast: Bool
  ): Bool =
    readValid && !readLast && mustBeLast

  /** 从机提前返回 RLAST：burst 尚未到预期最后一拍。 */
  def EarlyLast(readLast: Bool, finalBeat: Bool): Bool =
    readLast && !finalBeat

  /** RRESP 非 OKAY。 */
  def BeatError(response: UInt): Bool =
    response =/= 0.U

  /** 有 AXI 错误码时保留错误码；协议违规/无错误码回退 SLVERR(2)。 */
  def FaultResponse(readError: Bool, response: UInt): UInt =
    Mux(readError, response, 2.U)
}
