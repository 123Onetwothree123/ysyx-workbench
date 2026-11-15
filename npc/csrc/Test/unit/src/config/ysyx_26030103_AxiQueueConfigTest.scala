package ysyx_26030103.config

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.infra.{
  ysyx_26030103_AXI5Arbiter,
  ysyx_26030103_AXI5Xbar
}
import _root_.ysyx_26030103.mmu.ysyx_26030103_PTWPMPFilter

/** PTW PMP fault 队列与 AXI 响应队列深度的配置契约。
  *
  * 这四个旋钮都只影响内部缓冲深度，不改变对外 ABI，因此必须在
  * elaboration 期把范围钉死：越界值绝不能被静默截断成“看起来能用”
  * 的硬件。测试同时验证 case class 的 require 与叶模块自身的 require，
  * 避免配置层和 RTL 层的合法区间漂移。
  */
class ysyx_26030103_AxiQueueConfigTest extends AnyFlatSpec with Matchers {
  "MMUProfile" should "默认把 PTW PMP fault 队列深度设为 8" in {
    ysyx_26030103_MMUProfile().ptwPmpFaultQueueDepth should be(8)
  }

  it should "接受 1..16 的 PTW PMP fault 队列深度" in {
    ysyx_26030103_MMUProfile(ptwPmpFaultQueueDepth = 1)
      .ptwPmpFaultQueueDepth should be(1)
    ysyx_26030103_MMUProfile(ptwPmpFaultQueueDepth = 16)
      .ptwPmpFaultQueueDepth should be(16)
  }

  it should "拒绝 0/17 的 PTW PMP fault 队列深度" in {
    for (depth <- Seq(0, 17)) {
      withClue(s"ptwPmpFaultQueueDepth=$depth: ") {
        val error = intercept[IllegalArgumentException] {
          ysyx_26030103_MMUProfile(ptwPmpFaultQueueDepth = depth)
        }
        error.getMessage should include("ptwPmpFaultQueueDepth")
        error.getMessage should include("1..16")
      }
    }
  }

  "NPCConfig" should "暴露 AXI 响应队列深度的默认值" in {
    val config = ysyx_26030103_NPCConfig()
    config.AxiArbCpuRespQueueDepth should be(16)
    config.AxiArbDmaRespQueueDepth should be(256)
    config.AxiXbarRespQueueDepth should be(4)
  }

  it should "接受 CPU=2/16、DMA=16/256、Xbar=2/16 的边界组合" in {
    val minConfig = ysyx_26030103_NPCConfig(
      AxiArbCpuRespQueueDepth = 2,
      AxiArbDmaRespQueueDepth = 16,
      AxiXbarRespQueueDepth = 2
    )
    minConfig.AxiArbCpuRespQueueDepth should be(2)
    minConfig.AxiArbDmaRespQueueDepth should be(16)
    minConfig.AxiXbarRespQueueDepth should be(2)

    val maxConfig = ysyx_26030103_NPCConfig(
      AxiArbCpuRespQueueDepth = 16,
      AxiArbDmaRespQueueDepth = 256,
      AxiXbarRespQueueDepth = 16
    )
    maxConfig.AxiArbCpuRespQueueDepth should be(16)
    maxConfig.AxiArbDmaRespQueueDepth should be(256)
    maxConfig.AxiXbarRespQueueDepth should be(16)
  }

  it should "拒绝越界的 CPU 响应队列深度并指出配置名" in {
    for (depth <- Seq(1, 17)) {
      withClue(s"AxiArbCpuRespQueueDepth=$depth: ") {
        val error = intercept[IllegalArgumentException] {
          ysyx_26030103_NPCConfig(AxiArbCpuRespQueueDepth = depth)
        }
        error.getMessage should include("AXI_ARB_CPU_RESP_QUEUE_DEPTH")
        error.getMessage should include("2..16")
      }
    }
  }

  it should "拒绝越界的 DMA 响应队列深度并指出配置名" in {
    for (depth <- Seq(15, 257)) {
      withClue(s"AxiArbDmaRespQueueDepth=$depth: ") {
        val error = intercept[IllegalArgumentException] {
          ysyx_26030103_NPCConfig(AxiArbDmaRespQueueDepth = depth)
        }
        error.getMessage should include("AXI_ARB_DMA_RESP_QUEUE_DEPTH")
        error.getMessage should include("16..256")
      }
    }
  }

  it should "拒绝越界的 Xbar 响应队列深度并指出配置名" in {
    for (depth <- Seq(1, 17)) {
      withClue(s"AxiXbarRespQueueDepth=$depth: ") {
        val error = intercept[IllegalArgumentException] {
          ysyx_26030103_NPCConfig(AxiXbarRespQueueDepth = depth)
        }
        error.getMessage should include("AXI_XBAR_RESP_QUEUE_DEPTH")
        error.getMessage should include("2..16")
      }
    }
  }

  "PTWPMPFilter" should "在 faultQueueDepth=1/16 边界上 elaboration 成功" in {
    for (depth <- Seq(1, 16)) {
      withClue(s"faultQueueDepth=$depth: ") {
        val chirrtl = ChiselStage.emitCHIRRTL(
          new ysyx_26030103_PTWPMPFilter(
            AddressWidth = 32,
            entries = 1,
            faultQueueDepth = depth
          )
        )
        chirrtl should include("module")
      }
    }
  }

  it should "在 faultQueueDepth 越界时由 RTL 自身拒绝" in {
    val error = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(
        new ysyx_26030103_PTWPMPFilter(
          AddressWidth = 32,
          entries = 1,
          faultQueueDepth = 17
        )
      )
    }
    error.getMessage should include("faultQueueDepth")
  }

  "AXI5Arbiter" should "在 CPU=2/DMA=16 的队列深度边界上 elaboration 成功" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new ysyx_26030103_AXI5Arbiter(
        AddressWidth = 32,
        DataWidth = 32,
        CpuRespQueueDepth = 2,
        DmaRespQueueDepth = 16
      )
    )
    chirrtl should include("module")
  }

  it should "在 CPU=16/DMA=256 的队列深度边界上 elaboration 成功" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new ysyx_26030103_AXI5Arbiter(
        AddressWidth = 32,
        DataWidth = 32,
        CpuRespQueueDepth = 16,
        DmaRespQueueDepth = 256
      )
    )
    chirrtl should include("module")
  }

  "AXI5Xbar" should "在 RespQueueDepth=2/16 边界上 elaboration 成功" in {
    for (depth <- Seq(2, 16)) {
      withClue(s"RespQueueDepth=$depth: ") {
        val chirrtl = ChiselStage.emitCHIRRTL(
          new ysyx_26030103_AXI5Xbar(
            AddressWidth = 32,
            DataWidth = 32,
            RespQueueDepth = depth
          )
        )
        chirrtl should include("module")
      }
    }
  }
}
