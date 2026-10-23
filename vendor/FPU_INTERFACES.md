# Berkeley HardFloat 与 Fudian FPU 接口调研

> 本文档只服务于第三方 FPU 源码入库（vendor）和接口调研，不包含 NPC/NEMU 的功能实现。
> 调研对象为 `vendor/berkeley-hardfloat/` 与 `vendor/fudian/` 中实际入库的源码。

## 0. 结论摘要

| 项目 | 结论 |
| --- | --- |
| 目录 | `vendor/berkeley-hardfloat/`、`vendor/fudian/`，均为普通目录，不是 git submodule |
| HardFloat | 接口统一使用 RecFN（重编码浮点）；参数为 `expWidth`/`sigWidth`；加、乘、FMA、比较、转换均为组合逻辑，除法/开方为多周期握手模块 |
| Fudian | 接口直接使用 IEEE-754 位模式；参数为 `expWidth`/`precision`；除 `FDIV` 外均为组合逻辑，`s1/s2/s3` 只是逻辑分级，不是寄存器级 |
| 推荐 | 正确性/可配置性优先选 HardFloat；性能/面积优先且愿意在外部插流水寄存器选 Fudian。当前 NPC 使用 Chisel 7.7.0，两套上游分别按 Chisel 3.5.6/3.6.0 开发，接入前都必须先做编译验证；短期最稳妥的方式是生成 Verilog 后例化 |
| 许可证 | HardFloat 为 BSD-3-Clause（仓库自带 `LICENSE`）；Fudian 仓库根目录**没有** LICENSE 文件，仅 `FDIV.scala` 文件头声明 Mulan PSL v2，使用/再分发前需向上游确认 |

---

## 1. 来源、版本与目录

### 1.1 实际来源

| 项目 | 上游地址 | 实际 clone 地址 | 分支 | commit | commit 日期 |
| --- | --- | --- | --- | --- | --- |
| Berkeley HardFloat | `https://github.com/ucb-bar/berkeley-hardfloat` | `https://gh-proxy.com/https://github.com/ucb-bar/berkeley-hardfloat` | `master` | `c1105e6ac6a0dd90fc80893efc4830ab609005d3` | 2026-08-18 |
| Fudian | `https://github.com/OpenXiangShan/Fudian` | `https://gh-proxy.com/https://github.com/OpenXiangShan/Fudian` | `pipeline`（上游默认分支） | `e1bd4695ca7beb36a5ce7357e9527ad9e95b9ec1` | 2024-01-17 |

说明：

- 当前网络直连 `github.com` 的 `git ls-remote` 超时（exit 124），因此使用 `gh-proxy.com` 镜像完成 clone；clone 的是上游默认分支的 HEAD。
- HardFloat 的 `build.sbt` 中版本号为 `1.5-SNAPSHOT`。
- Fudian 没有 release tag；入库的是 `pipeline` 分支 HEAD。
- 两者均使用 `git clone --depth 1` 获取，commit 为 clone 当时的上游 HEAD。

### 1.2 vendoring 处理

为满足“普通目录、`git add .` 可跟踪本地修改”的要求，做了以下**结构性**调整，未改动任何上游源码：

1. 删除 `vendor/berkeley-hardfloat/.git`、`vendor/fudian/.git`。
2. 删除两个仓库的 `.gitmodules`。
3. 删除空的测试用 submodule 目录：
   - `vendor/berkeley-hardfloat/berkeley-softfloat-3/`
   - `vendor/berkeley-hardfloat/berkeley-testfloat-3/`
   - `vendor/fudian/berkeley-softfloat-3/`
   - `vendor/fudian/berkeley-testfloat-3/`
4. 保留 `LICENSE`、`README.md`、`Makefile`、`build.sbt`/`build.sc`、全部 RTL 源码、测试源码和文档。
5. 没有保留任何构建产物；仓库根 `.gitignore` 增加了 `vendor/` 白名单和 vendor 内 `target/`、`build/`、`out/`、`.bsp/`、`obj_dir/` 的忽略规则。

注意：上述两个 `berkeley-softfloat-3`/`berkeley-testfloat-3` 只用于上游的 testfloat 交叉验证，不参与 FPU RTL 编译。若以后要跑上游单元测试，需要手动把对应仓库 clone 回原目录（它们不应作为 submodule 入库）。

### 1.3 目录结构

```text
vendor/
├── FPU_INTERFACES.md              # 本文档
├── berkeley-hardfloat/
│   ├── LICENSE
│   ├── README.md
│   ├── build.sbt / build.sc
│   ├── doc/
│   └── hardfloat/
│       ├── src/main/scala/        # RTL 源码（20 个 .scala）
│       └── tests/                 # 上游 testfloat 测试
└── fudian/
    ├── README.md
    ├── build.sc
    ├── Makefile
    └── src/
        ├── main/scala/fudian/     # RTL 源码（15 个 .scala）
        └── test/                  # 上游 testfloat 测试
```

---

## 2. 两套 FPU 的公共约定

### 2.1 位宽与精度

两套库都使用“指数位宽 + 有效数位宽”的参数化方式，但命名不同：

| 含义 | HardFloat | Fudian |
| --- | --- | --- |
| 指数位宽 | `expWidth` | `expWidth` |
| 有效数位宽（**含隐藏位**） | `sigWidth` | `precision` |
| IEEE 单精度 | `expWidth=8, sigWidth=24` | `expWidth=8, precision=24` |
| IEEE 双精度 | `expWidth=11, sigWidth=53` | `expWidth=11, precision=53` |
| FLEN | `expWidth + sigWidth` | `expWidth + precision` |

必须注意：`sigWidth`/`precision` **包含隐藏位**，不是 IEEE 存储格式中的小数位宽（f32 为 23，f64 为 52）。

- HardFloat 顶层 RecFN 端口宽度为 `expWidth + sigWidth + 1`（1 位符号 + 指数 + 有效数）。
- Fudian 顶层端口宽度为 `expWidth + precision`（标准 IEEE 位模式，符号 + 指数 + 小数）。

### 2.2 舍入模式

两套库都使用 3 位舍入模式，低 5 个编码与 RISC-V `frm` 一致：

| 编码 | 含义 | HardFloat 常量 | Fudian 常量 |
| --- | --- | --- | --- |
| `3'b000` | 就近舍入，偶数优先（RNE） | `round_near_even` | `RNE` |
| `3'b001` | 向零舍入（RTZ） | `round_minMag` | `RTZ` |
| `3'b010` | 向负无穷舍入（RDN） | `round_min` | `RDN` |
| `3'b011` | 向正无穷舍入（RUP） | `round_max` | `RUP` |
| `3'b100` | 就近舍入，远离零优先（RMM） | `round_near_maxMag` | `RMM` |
| `3'b110` | 舍入到奇数（仅 HardFloat） | `round_odd` | 不支持 |

RISC-V 的 `frm` 只使用 000~100；`101`/`111` 为保留值。HardFloat 额外支持 `110`（用于整数舍入等特殊场景），Fudian 只实现 000~100，传入 5~7 时行为未定义（其 `MuxLookup` 默认不增位，等价于截断），接入时不要传非法值。

### 2.3 异常标志

两套库的算术异常标志都是 5 位，位序一致：

```text
bit[4] = invalid operation (NV)
bit[3] = divide by zero     (DZ)
bit[2] = overflow           (OF)
bit[1] = underflow          (UF)
bit[0] = inexact            (NX)
```

对应 Chisel 拼接顺序为 `{invalid, infinite/divByZero, overflow, underflow, inexact}`。

差异：

- HardFloat 的 `exceptionFlags` 中 bit[3] 名为 `infiniteExc`，在除法模块中表示除零；加/乘/FMA 模块该位恒为 0。
- HardFloat 的 `RecFNToIN` 只输出 3 位：`{invalid, overflow, inexact}`，没有 DZ/UF。接入 RISC-V 时通常把 `invalid | overflow` 合并为 NV，把 `inexact` 作为 NX。
- Fudian 所有顶层模块统一输出 5 位 `{NV, DZ, OF, UF, NX}`；非除法模块的 DZ 恒为 0。

### 2.4 Tininess（下溢判定时机）

- HardFloat：`detectTininess` 是**运行时输入**，`0 = tininess before rounding`，`1 = tininess after rounding`。RISC-V 要求 after rounding，接入时应恒接 `1`。`DivSqrtRecFN_small` 要求该输入在运算期间保持不变。
- Fudian：没有 `detectTininess` 端口，内部 `TininessRounder` 固定实现 after-rounding 语义，符合 RISC-V。

---

## 3. Berkeley HardFloat

### 3.1 目录、包名与构建

- 包名：`hardfloat`
- RTL 源码：`vendor/berkeley-hardfloat/hardfloat/src/main/scala/`
- 上游构建：sbt，`build.sbt` 指定 Scala 2.13.10、Chisel 3.5.6
- 顶层模块大多继承 `chisel3.RawModule`（没有隐式时钟/复位）；`DivSqrtRecFN_small`、`DivSqrtRecF64`、`RecFNToIN` 继承 `Module`。

### 3.2 RecFN 重编码格式

HardFloat 的算术端口**不是**标准 IEEE-754 位模式，而是内部 RecFN 格式：

```text
{ sign, exp[expWidth:0], fract[sigWidth-2:0] }
// 1 + (expWidth+1) + (sigWidth-1) = expWidth + sigWidth + 1 位
```

- 指数比 IEEE 多 1 位，用于高效处理 subnormal；尾数线上只有 `sigWidth-1` 位小数（隐藏位由指数/有效数编码隐含）。
- 不能在 RecFN 端口上直接接 IEEE 位模式；必须使用库提供的转换函数：
  - `recFNFromFN(expWidth, sigWidth, ieeeBits)`：IEEE 位模式 → RecFN
  - `fNFromRecFN(expWidth, sigWidth, recFN)`：RecFN → IEEE 位模式
  - `rawFloatFromFN` / `rawFloatFromRecFN`：IEEE/RecFN → 内部 `RawFloat`（仅内部使用）
- `RawFloat` 是内部 Bundle，含 `isNaN`、`isInf`、`isZero`、`sign`、`sExp`、`sig` 等字段，不是顶层接口。

例：f32 的 RecFN 宽度为 `8 + 24 + 1 = 33` 位；f64 为 `11 + 53 + 1 = 65` 位。

### 3.3 公开参数

| 参数 | 适用模块 | 含义 | 建议 |
| --- | --- | --- | --- |
| `expWidth` | 所有模块 | 指数位宽，f32=8，f64=11 | 由 FLEN 派生，不建议单独暴露 |
| `sigWidth` | 所有模块 | 有效数位宽（含隐藏位），f32=24，f64=53 | 由 FLEN 派生，不建议单独暴露 |
| `intWidth` | `INToRecFN`、`RecFNToIN` | 整数位宽，通常 32/64 | 与 XLEN/指令目标宽度对应，可由 Kconfig 暴露 |
| `options` | `DivSqrtRecFN_small`、`RoundAnyRawFNToRecFN` 等 | 位掩码 | 只建议暴露 `twoBitsPerCycle`（16），其余保持 0 |
| `inExpWidth`/`inSigWidth`/`outExpWidth`/`outSigWidth` | `RecFNToRecFN` | 输入/输出精度 | 由转换指令决定，不建议用户配置 |

`options` 的公开位定义（`consts`）：

| 值 | 名称 | 含义 |
| --- | --- | --- |
| `1` | `flRoundOpt_sigMSBitAlwaysZero` | 输入有效数最高位恒 0（内部优化） |
| `2` | `flRoundOpt_subnormsAlwaysExact` | subnormal 结果总是精确（内部优化） |
| `4` | `flRoundOpt_neverUnderflows` | 不会下溢（内部优化） |
| `8` | `flRoundOpt_neverOverflows` | 不会上溢（内部优化） |
| `16` | `divSqrtOpt_twoBitsPerCycle` | 除法/开方每周期处理 2 位，吞吐更高、面积更大 |

对普通 RISC-V FPU 接入，`DivSqrtRecFN_small` 使用 `options=0` 或 `options=16` 即可，不要随意组合其它位。

### 3.4 顶层模块与端口

#### 3.4.1 算术与比较（组合逻辑）

| 模块 | 参数 | 输入 | 输出 | 说明 |
| --- | --- | --- | --- | --- |
| `AddRecFN` | `expWidth, sigWidth` | `subOp`, `a`, `b`, `roundingMode`, `detectTininess` | `out`, `exceptionFlags` | `subOp=1` 时做 `a-b`，否则 `a+b`；RecFN 输入/输出 |
| `MulRecFN` | `expWidth, sigWidth` | `a`, `b`, `roundingMode`, `detectTininess` | `out`, `exceptionFlags` | 乘法；RecFN |
| `MulAddRecFN` | `expWidth, sigWidth` | `op[1:0]`, `a`, `b`, `c`, `roundingMode`, `detectTininess` | `out`, `exceptionFlags` | 融合乘加；RecFN |
| `CompareRecFN` | `expWidth, sigWidth` | `a`, `b`, `signaling` | `lt`, `eq`, `gt`, `exceptionFlags` | 比较；`signaling=1` 时任意 NaN 都置 invalid，`0` 时只有 sNaN 置 invalid |

`MulAddRecFN.op` 的位义（按源码 `signProd = signA ^ signB ^ op(1)`、`doSubMags = signProd ^ signC ^ op(0)` 推导）：

| `op` | 运算 |
| --- | --- |
| `2'b00` | `a*b + c`（fmadd） |
| `2'b01` | `a*b - c`（fmsub） |
| `2'b10` | `-(a*b) + c`（fnmsub） |
| `2'b11` | `-(a*b) - c`（fnmadd） |

上游测试只覆盖了 `op=0` 的 fmadd 路径；使用其它编码时建议自行用 testfloat/参考模型验证。

端口宽度：

- `a`/`b`/`c`/`out`：`expWidth + sigWidth + 1` 位
- `exceptionFlags`：5 位
- `roundingMode`：3 位
- `detectTininess`：`AddRecFN` 是 `Bool`，其余多为 `UInt(1.W)`（Chisel 生成 Verilog 后都是一个 1 位端口）

#### 3.4.2 除法/开方（多周期）

**`DivSqrtRecFN_small(expWidth, sigWidth, options)`**

| 方向 | 端口 | 说明 |
| --- | --- | --- |
| 输入 | `inReady` | 可以接受新运算 |
| 输入 | `inValid` | 新运算有效；`inValid && inReady` 时启动 |
| 输入 | `sqrtOp` | 0=除法 `a/b`，1=开方 `sqrt(a)`（注意：`DivSqrtRecFN_small` 的开方参数是 `a`；固定 F64 版本 `DivSqrtRecF64` 的开方参数是 `b`） |
| 输入 | `a`, `b` | RecFN，`expWidth+sigWidth+1` 位 |
| 输入 | `roundingMode` | 3 位 |
| 输入 | `detectTininess` | 运算期间必须保持稳定，RISC-V 接 1 |
| 输出 | `outValid_div` | 除法结果有效 |
| 输出 | `outValid_sqrt` | 开方结果有效 |
| 输出 | `out` | RecFN 结果 |
| 输出 | `exceptionFlags` | 5 位异常标志 |

特点：

- 同一时刻只处理一个运算；上一个运算的最后一拍可以和下一个运算的第一拍重叠。
- 默认约 `sigWidth` 个周期完成一次运算（f32 约 24 拍，f64 约 53 拍）；`options=16` 时每周期 2 位，周期数约减半。
- `a`/`b`/`roundingMode` 只在握手拍采样；`detectTininess` 必须全程稳定。

**`DivSqrtRecF64`（固定双精度）**

- 仅支持 f64，端口 `a`/`b`/`out` 固定 65 位（RecFN）。
- 除法为 `a/b`；**开方时参数是 `b`**（与参数化版本 `DivSqrtRecFN_small` 使用 `a` 不同，接入时务必注意）。
- 握手端口为 `inReady_div`、`inReady_sqrt`、`inValid`、`sqrtOp`、`outValid_div`、`outValid_sqrt`。
- 内部使用 54 位乘法器（`Mul54`）实现多周期迭代，是自包含模块；支持最多 4 个运算流水重叠，结果按启动顺序返回。
- 上游文档给出的最大延迟：除法 19 拍、开方 27 拍；连续同类运算的最小启动间隔约 6 拍（除法）/10 拍（开方）。
- 只建议在只需要 f64、且不想用参数化版本时使用。

#### 3.4.3 转换

| 模块 | 参数 | 输入 | 输出 | 说明 |
| --- | --- | --- | --- | --- |
| `INToRecFN` | `intWidth, expWidth, sigWidth` | `signedIn`, `in[intWidth]`, `roundingMode`, `detectTininess` | `out`（RecFN）, `exceptionFlags[4:0]` | 整数 → 浮点 |
| `RecFNToIN` | `expWidth, sigWidth, intWidth` | `in`（RecFN）, `roundingMode`, `signedOut` | `out[intWidth]`, `intExceptionFlags[2:0]` | 浮点 → 整数 |
| `RecFNToRecFN` | `inExpWidth, inSigWidth, outExpWidth, outSigWidth` | `in`, `roundingMode`, `detectTininess` | `out`, `exceptionFlags[4:0]` | 不同精度浮点互转（f32↔f64、f16↔f32 等） |
| `RoundAnyRawFNToRecFN` | 4 个位宽 + `options` | 内部 `RawFloat`、`invalidExc`、`infiniteExc` 等 | `out`, `exceptionFlags` | 通用舍入原语，适合自定义流水线 |
| `RoundRawFNToRecFN` | `expWidth, sigWidth, options` | 内部 `RawFloat` | `out`, `exceptionFlags` | `RoundAnyRawFNToRecFN` 的常用封装 |

`RecFNToIN.intExceptionFlags` 位序为 `{invalid, overflow, inexact}`（MSB→LSB）。当 invalid 或 overflow 时，`out` 输出饱和值（有符号为 INT_MAX/INT_MIN，无符号为 UINT_MAX/0），接入 RISC-V 时通常把 `invalid|overflow` 作为 NV，把 `inexact` 作为 NX。

#### 3.4.4 辅助对象

| 对象 | 作用 |
| --- | --- |
| `recFNFromFN(expWidth, sigWidth, in)` | IEEE 位模式 → RecFN |
| `fNFromRecFN(expWidth, sigWidth, in)` | RecFN → IEEE 位模式 |
| `rawFloatFromFN` / `rawFloatFromRecFN` | 转内部 `RawFloat` |
| `classifyRecFN(expWidth, sigWidth, in)` | 输出 10 位 RISC-V `fclass` 结果 |
| `isSigNaNRawFloat` | 判断内部 `RawFloat` 是否为 sNaN |
| `resizeRawFloat` | 内部 `RawFloat` 位宽调整 |

`classifyRecFN` 的 10 位顺序与 RISC-V `fclass` 完全一致：

```text
bit9 qNaN, bit8 sNaN, bit7 +inf, bit6 +normal, bit5 +subnormal,
bit4 +zero, bit3 -zero, bit2 -subnormal, bit1 -normal, bit0 -inf
```

另外 `MulAddRecFN` 内部还有 `MulAddRecFNToRaw_preMul`/`MulAddRecFNToRaw_postMul`，用于把乘加拆成“乘法 + 对齐 + 舍入”的自定义流水线；它们不是稳定公开接口，除非需要自己插入流水寄存器，否则不建议直接使用。

### 3.5 时序特性

- `AddRecFN`、`MulRecFN`、`MulAddRecFN`、`CompareRecFN`、`INToRecFN`、`RecFNToIN`、`RecFNToRecFN` 内部**没有寄存器**，是纯组合逻辑。
- 当前 commit **没有** `MulAddRecFNPipe` 之类的流水线 FMA；若需要高频率，必须在模块外部自行插入流水寄存器（或使用 `MulAddRecFNToRaw_preMul`/`postMul` 自己搭流水）。
- `DivSqrtRecFN_small`、`DivSqrtRecF64` 是内部带状态机的多周期模块，需要时钟/复位。

### 3.6 从 Chisel 调用

```scala
import chisel3._
import hardfloat._

class FAddF32 extends Module {
  val io = IO(new Bundle {
    val a    = Input(UInt(32.W))   // IEEE-754 f32
    val b    = Input(UInt(32.W))
    val sub  = Input(Bool())
    val rm   = Input(UInt(3.W))
    val y    = Output(UInt(32.W))
    val ff   = Output(UInt(5.W))
  })

  val aRec = recFNFromFN(8, 24, io.a)
  val bRec = recFNFromFN(8, 24, io.b)

  val fadd = Module(new AddRecFN(8, 24))
  fadd.io.subOp        := io.sub
  fadd.io.a            := aRec
  fadd.io.b            := bRec
  fadd.io.roundingMode := io.rm
  fadd.io.detectTininess := 1.U   // RISC-V: tininess after rounding

  io.y  := fNFromRecFN(8, 24, fadd.io.out)
  io.ff := fadd.io.exceptionFlags
}
```

FMA：

```scala
val fma = Module(new MulAddRecFN(8, 24))
fma.io.op             := 0.U   // a*b+c
fma.io.a              := recFNFromFN(8, 24, a)
fma.io.b              := recFNFromFN(8, 24, b)
fma.io.c              := recFNFromFN(8, 24, c)
fma.io.roundingMode   := rm
fma.io.detectTininess := 1.U
```

除法/开方握手：

```scala
val ds = Module(new DivSqrtRecFN_small(8, 24, 0))
ds.io.inValid      := req.valid
ds.io.sqrtOp       := isSqrt
ds.io.a            := aRec
ds.io.b            := bRec
ds.io.roundingMode := rm
ds.io.detectTininess := 1.U
req.ready  := ds.io.inReady
resp.valid := ds.io.outValid_div || ds.io.outValid_sqrt
resp.bits  := ds.io.out
resp.fflags := ds.io.exceptionFlags
```

### 3.7 从 Verilog 调用

HardFloat 没有预生成的 Verilog，需要在 Chisel 侧 elaborate。例如：

```scala
import chisel3.stage.ChiselStage

object GenHardFloat extends App {
  (new ChiselStage).emitVerilog(
    new hardfloat.MulAddRecFN(8, 24),
    Array("--target-dir", "generated/hardfloat")
  )
}
```

Chisel 3.5 生成的是 Verilog；若在 NPC 的 Chisel 7.7 下生成，应使用对应的 `ChiselStage.emitSystemVerilog`/`emitVerilog` API。生成后的端口名按 `io_<字段>` 展开，例如：

```verilog
MulAddRecFN_e8_s24 u_fma (
    .io_op             (op),
    .io_a              (a_rec),
    .io_b              (b_rec),
    .io_c              (c_rec),
    .io_roundingMode   (rm),
    .io_detectTininess (1'b1),
    .io_out            (y_rec),
    .io_exceptionFlags (fflags)
);
```

注意：

- `MulAddRecFN` 的 `desiredName` 是 `MulAddRecFN_e8_s24` 这种带参数后缀的名字；`DivSqrtRecFN_small` 的 `desiredName` 是 `DivSqrtRecFM_small_e8_s24`（上游拼写为 `RecFM`，不是笔误，例化时以实际生成文件为准）。
- `AddRecFN`/`MulRecFN`/`CompareRecFN` 等没有自定义 `desiredName`，顶层名通常就是类名。
- 组合模块（`RawModule`）没有时钟/复位端口；`DivSqrtRecFN_small`、`RecFNToIN` 等继承 `Module` 的模块可能生成 `clock`/`reset` 端口，以实际生成结果为准。

---

## 4. Fudian

### 4.1 目录、包名与构建

- 包名：`fudian`
- RTL 源码：`vendor/fudian/src/main/scala/fudian/`
- 上游构建：mill，`build.sc` 指定 Scala 2.13.10、Chisel 3.6.0、chiseltest 0.6.2
- 所有顶层模块都继承 `chisel3.Module`；除 `FDIV` 外没有内部寄存器。

### 4.2 IEEE 位模式与内部类型

Fudian 的顶层端口直接使用标准 IEEE-754 位模式：

```text
{ sign, exp[expWidth-1:0], sig[precision-2:0] }   // 共 expWidth + precision 位
```

- `FloatPoint(expWidth, precision)` 是公开的 Bundle，字段为 `sign`、`exp`、`sig`；`sigWidth = precision - 1`。
- `FloatPoint.fromUInt(x, expWidth, precision)` 把 IEEE 位模式拆成 `FloatPoint`。
- `FloatPoint.defaultNaNUInt(expWidth, precision)` 生成默认 NaN。
- `RawFloat(expWidth, precision)` 是内部归一化表示，`sig` 宽度为 `precision`（显式包含隐藏位）；`RawFloat.fromFP`/`fromUInt` 用于转换。它不是顶层端口类型，不建议在 SoC 边界使用。

### 4.3 公开参数

| 参数 | 适用模块 | 含义 | 建议 |
| --- | --- | --- | --- |
| `expWidth` | 所有模块 | 指数位宽，f32=8，f64=11 | 由 FLEN 派生 |
| `precision` | 所有模块 | 有效数位宽（含隐藏位），f32=24，f64=53 | 由 FLEN 派生 |
| `inExpWidth`/`inPrecision`/`outExpWidth`/`outPrecision` | `FPToFP` | 输入/输出格式 | 由转换指令决定 |
| `signaling` | `FCMP` | 1=任意 NaN 都置 invalid；0=仅 sNaN | 运行时信号，不是编译参数 |
| `op[1:0]` | `FPToInt` | `op(0)=signed`，`op(1)=long` | 运行时信号 |
| `sign`/`long` | `IntToFP` | `sign=1` 有符号，`long=1` 64 位 | 运行时信号 |
| `isSqrt`/`kill` | `FDIV` | 开方选择/中止 | 运行时信号 |

### 4.4 顶层模块与端口

#### 4.4.1 算术、比较（组合逻辑）

| 模块 | 参数 | 输入 | 输出 | 说明 |
| --- | --- | --- | --- | --- |
| `FADD` | `expWidth, precision` | `a`, `b`, `rm` | `result`, `fflags` | 浮点加法；减法需在外部翻转 `b` 的符号位 |
| `FMUL` | `expWidth, precision` | `a`, `b`, `rm` | `result`, `fflags`, `to_fadd` | 浮点乘法；`to_fadd` 是给级联 FMA 用的中间结果 |
| `FCMA` | `expWidth, precision` | `a`, `b`, `c`, `rm` | `result`, `fflags` | 融合乘加 `a*b+c`；不支持融合取负，符号需外部处理 |
| `FCMP` | `expWidth, precision` | `a`, `b`, `signaling` | `eq`, `le`, `lt`, `fflags` | 比较；NaN 时 eq/le/lt 全 0 |

端口说明：

- `a`/`b`/`c`/`result` 宽度均为 `expWidth + precision`。
- `fflags` 5 位 `{NV, DZ, OF, UF, NX}`。
- `FMUL.to_fadd` 是 `FMULToFADD` Bundle，包含 `fp_prod`、`inter_flags`、`rm`；普通使用可忽略该输出。
- `FADD`/`FMUL`/`FCMA` 的 `s1/s2/s3` 子模块只是逻辑分级，**没有寄存器**，整个模块是组合逻辑。

#### 4.4.2 转换（组合逻辑）

| 模块 | 参数 | 输入 | 输出 | 说明 |
| --- | --- | --- | --- | --- |
| `FPToInt` | `expWidth, precision` | `a`, `rm`, `op[1:0]` | `result[63:0]`, `fflags` | 浮点 → 整数；`op=0` 无符号 32 位，`op=1` 有符号 32 位，`op=2` 无符号 64 位，`op=3` 有符号 64 位 |
| `IntToFP` | `expWidth, precision` | `int[63:0]`, `sign`, `long`, `rm` | `result`, `fflags` | 整数 → 浮点；`sign=1` 有符号，`long=1` 64 位 |
| `FPToFP` | `inExpWidth, inPrecision, outExpWidth, outPrecision` | `in`, `rm` | `result`, `fflags` | 浮点精度转换；只支持“同时降精度”或“同时升精度” |

细节：

- `FPToInt.result` 固定 64 位；32 位模式下高 32 位为 0。非法/溢出时输出饱和值（有符号 INT_MAX/INT_MIN，无符号 UINT_MAX/0），并置 NV；输出 `fflags` 的 OF 位为 0（溢出信息被合并进 NV），截断还会计 NX。
- `IntToFP` 的 32 位模式需要调用方准备好 64 位输入：`sign=1, long=0` 时内部取低 32 位并符号扩展；`sign=0, long=0` 时直接使用完整的 64 位输入，因此高 32 位必须清零。
- `FPToFP` 内部 `require(down_cvt || up_cvt)`，不能用于同精度转换，也不支持“指数变宽但有效数变窄”这类混合方向。f32→f64 用 `(8,24,11,53)`，f64→f32 用 `(11,53,8,24)`。
- 这三个模块都是组合逻辑；`IntToFP` 正常情况下只产生 NX 标志。

#### 4.4.3 除法/开方 `FDIV`（多周期）

```scala
class FDIVIO(val expWidth: Int, val precision: Int) extends Bundle {
  val a, b = Input(UInt((expWidth + precision).W))
  val rm = Input(UInt(3.W))
  val result = Output(UInt((expWidth + precision).W))
  val fflags = Output(UInt(5.W))
  val specialIO = new FDIVSpecialIO
}
```

`FDIVSpecialIO`：

| 方向 | 端口 | 说明 |
| --- | --- | --- |
| 输入 | `in_valid` | 请求有效 |
| 输出 | `in_ready` | 空闲时可接收，只有 `s_idle` 状态为 1 |
| 输入 | `out_ready` | 下游可接收结果 |
| 输出 | `out_valid` | 结果有效 |
| 输入 | `isSqrt` | 0=除法 `a/b`，1=开方 `sqrt(a)` |
| 输入 | `kill` | 中止当前运算并回到 idle |

特点：

- 除法使用 SRT radix-16，开方使用 SRT radix-4；每轮迭代产生 4 位商（除法）或 2 位根（开方）。
- 延迟与 `precision` 相关且是变长的，正常数大致为：
  - f32 除法约 9 拍，f64 除法约 16 拍；
  - f32 开方约 15 拍，f64 开方约 29 拍；
  - subnormal 输入可能多 1 拍；特殊值会跳过迭代。
  上述数字由状态机/迭代次数估算，精确值以 RTL 仿真为准。
- `b` 在开方时被忽略；`a` 是开方参数。
- 同一时刻只处理一个运算，没有流水化；吞吐率约为“延迟 + 握手”周期。
- `FDIV` 是 Fudian 中唯一带内部状态/寄存器的顶层模块。

#### 4.4.4 内部工具

`src/main/scala/fudian/utils/` 下还有 `CLZ`、`CSA`、`LZA`、`Multiplier`、`ShiftRightJam`，以及 `RoundingUnit`、`TininessRounder`。这些是内部实现组件，不是稳定公开接口；除非要自己搭 FPU 流水线，否则不要直接依赖。

### 4.5 时序特性

| 模块 | 内部寄存器 | 延迟 |
| --- | --- | --- |
| `FADD` | 无 | 组合逻辑，0 拍 |
| `FMUL` | 无 | 组合逻辑，0 拍 |
| `FCMA` | 无 | 组合逻辑，0 拍 |
| `FCMP` | 无 | 组合逻辑，0 拍 |
| `FPToInt` | 无 | 组合逻辑，0 拍 |
| `IntToFP` | 无 | 组合逻辑，0 拍 |
| `FPToFP` | 无 | 组合逻辑，0 拍 |
| `FDIV` | 有状态机/寄存器 | 多周期，变长 |

重要：不要被 `s1`/`s2`/`s3` 的命名误导，它们在当前 commit 中不是流水寄存器级。若要提高频率，需要在 Fudian 模块外部插入流水寄存器（XiangShan 也是这样做的）。插入后需要同步处理 `fflags` 和 `FDIV` 的握手。

### 4.6 从 Chisel 调用

```scala
import chisel3._
import fudian._

class FmaF32 extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val c  = Input(UInt(32.W))
    val rm = Input(UInt(3.W))
    val y  = Output(UInt(32.W))
    val ff = Output(UInt(5.W))
  })

  val fma = Module(new FCMA(8, 24))
  fma.io.a  := io.a
  fma.io.b  := io.b
  fma.io.c  := io.c
  fma.io.rm := io.rm
  io.y  := fma.io.result
  io.ff := fma.io.fflags
}
```

`FDIV` 握手：

```scala
val fdiv = Module(new FDIV(8, 24))
fdiv.io.a := a
fdiv.io.b := b
fdiv.io.rm := rm
fdiv.io.specialIO.isSqrt   := isSqrt
fdiv.io.specialIO.in_valid := req.valid
fdiv.io.specialIO.out_ready := resp.ready
fdiv.io.specialIO.kill     := false.B

req.ready  := fdiv.io.specialIO.in_ready
resp.valid := fdiv.io.specialIO.out_valid
resp.bits  := fdiv.io.result
resp.fflags := fdiv.io.fflags
```

### 4.7 从 Verilog 调用

Fudian 自带 `Generator`，可直接生成单个模块的 Verilog：

```bash
# 在 vendor/fudian 下，需先准备 mill
mill -i fudian.tests.runMain fudian.Generator --fu FADD --ftype 32 -td build
# 生成 build/FADD.v
```

`--ftype` 支持 `32`、`64`、`32_64`、`64_32`；`--fu` 可取 `FADD`、`FMUL`、`FCMA`、`FCMP`、`FDIV`、`FPToInt`、`IntToFP`、`FPToFP`。

生成的端口名同样按 `io_<字段>` 展开，例如：

```verilog
FADD u_fadd (
    .clock (clock),          // Module 的隐式端口；组合模块可能被优化掉
    .reset (reset),
    .io_a       (a),
    .io_b       (b),
    .io_rm      (rm),
    .io_result  (y),
    .io_fflags  (fflags)
);

FDIV u_fdiv (
    .clock (clock),
    .reset (reset),
    .io_a (a),
    .io_b (b),
    .io_rm (rm),
    .io_result (y),
    .io_fflags (fflags),
    .io_specialIO_in_valid  (req_valid),
    .io_specialIO_in_ready  (req_ready),
    .io_specialIO_out_valid (resp_valid),
    .io_specialIO_out_ready (resp_ready),
    .io_specialIO_isSqrt    (is_sqrt),
    .io_specialIO_kill      (kill)
);
```

注意：Fudian 顶层都继承 `Module`，组合模块在生成 Verilog 时是否保留 `clock`/`reset` 取决于 Chisel 版本和优化选项，以实际生成文件为准；`FDIV` 必须接时钟/复位。

---

## 5. 两套 FPU 的差异对比

| 维度 | Berkeley HardFloat | Fudian |
| --- | --- | --- |
| 端口数据格式 | RecFN（重编码，指数多 1 位） | 标准 IEEE-754 位模式 |
| 精度参数 | `expWidth`, `sigWidth`（含隐藏位） | `expWidth`, `precision`（含隐藏位） |
| FLEN | `expWidth + sigWidth` | `expWidth + precision` |
| 加减法 | `AddRecFN` 带 `subOp`，直接支持减法 | `FADD` 只做加法，减法需翻转 `b` 符号 |
| FMA | `MulAddRecFN` 用 2 位 `op` 支持 fmadd/fmsub/fnmsub/fnmadd | `FCMA` 只做 `a*b+c`，其它形式需外部处理符号 |
| 比较 | `lt/eq/gt` + `signaling` | `eq/le/lt` + `signaling` |
| 浮点转换 | 独立的 `INToRecFN`、`RecFNToIN`、`RecFNToRecFN` | 独立的 `FPToInt`、`IntToFP`、`FPToFP` |
| 除法/开方 | 参数化 `DivSqrtRecFN_small`，1 位/周期或 2 位/周期 | `FDIV`，SRT16/SRT4，固定按精度推导迭代次数 |
| 除法的握手 | `inValid/inReady` + `outValid_div/outValid_sqrt` | `in_valid/in_ready` + `out_valid/out_ready` + `kill` |
| 内部流水 | 无（除 DivSqrt 状态机） | 无（除 FDIV 状态机）；`s1/s2/s3` 只是逻辑分级 |
| Tininess | 运行时 `detectTininess` 输入 | 固定 after-rounding |
| 异常标志 | 5 位，除法 bit3 为除零；`RecFNToIN` 为 3 位 | 统一 5 位 `{NV,DZ,OF,UF,NX}` |
| Chisel 版本 | 3.5.6 | 3.6.0 |
| 构建 | sbt | mill |
| 许可证 | BSD-3-Clause | 根目录无 LICENSE；仅 FDIV 头声明 Mulan PSL v2 |

---

## 6. Kconfig 暴露建议

> 以下是建议的配置项设计，本次任务**没有**修改 `npc/Kconfig` 或任何 NPC 功能代码。
> 当前 NPC 已有 `RISCV_F` / `RISCV_D` 占位项（启用会在 elaboration 阶段报错），建议把这两个现有项作为总开关，在其下增加 FPU 子选项，而不是另起一套互不相干的开关。

### 6.1 建议暴露给 Kconfig 的项

| 建议配置项 | 类型/取值 | 作用 | 映射 |
| --- | --- | --- | --- |
| `RISCV_F` / `RISCV_D` | 现有 bool 占位项 | 是否实现 F / D 扩展 | `RISCV_D` 依赖 `RISCV_F`；`D=y` 时 FLEN=64，仅 `F=y` 时 FLEN=32 |
| `RISCV_FPU_IMPL` | choice: `HARDFLOAT` / `FUDIAN` | 选择实现 | 只影响 RTL 选型，不影响软件 ABI |
| `RISCV_FPU_FMA` | bool，默认 y | 是否提供融合乘加 | HardFloat `MulAddRecFN`；Fudian `FCMA`；关闭时用 Mul+Add 组合 |
| `RISCV_FPU_DIVSQRT` | bool，默认 y | 是否提供除法/开方 | HardFloat `DivSqrtRecFN_small`；Fudian `FDIV` |
| `RISCV_FPU_DIVSQRT_2BIT` | bool，默认 n，依赖 `RISCV_FPU_IMPL=HARDFLOAT` 且 `RISCV_FPU_DIVSQRT=y` | 除法/开方每周期 2 位 | HardFloat `DivSqrtRecFN_small` 的 `options=16` |
| `RISCV_FPU_TININESS_AFTER` | bool，默认 y，仅 HardFloat | 下溢判定时机 | `detectTininess=1`；RISC-V 必须为 after rounding，建议固定为 y，不向用户暴露关闭选项 |

补充建议：

- 如果不想改现有 `RISCV_F`/`RISCV_D` 的语义，也可以新增一个 `NPC_FPU` 作为实现层总开关，但必须保证它和 `RISCV_F`/`RISCV_D` 的一致性，避免出现“ISA 说支持 F 但 RTL 没接 FPU”的配置。
- `RISCV_D` 打开时，`RISCV_F` 必须同时打开；FLEN 由 `RISCV_D ? 64 : 32` 派生，不要把 FLEN 做成与 ISA 扩展无关的自由选项。
- 浮点↔整数转换的位宽应跟随 `XLEN`（或指令目标宽度），通常不需要单独暴露；若确实要支持 RV32 核执行 64 位转换，再增加 `RISCV_FPU_INT_WIDTH`。
- `RISCV_FPU_IMPL` 更适合作为“实验/对比”选项；正式配置建议固定一种实现，避免两套 RTL 同时进入综合。
- 舍入模式、异常标志、`signaling`、`FPToInt.op`、`IntToFP.sign/long`、`FDIV.isSqrt` 都是**运行时信号**，应由指令译码/CSR 提供，不应做成 Kconfig。

### 6.2 不建议暴露的项

| 项 | 原因 |
| --- | --- |
| `expWidth`、`sigWidth`/`precision` 各自独立配置 | 必须满足 FLEN = 两者之和；独立暴露容易产生非法组合，应由 `RISCV_F`/`RISCV_D` 派生 |
| HardFloat `options` 的 1/2/4/8 位 | 舍入/异常内部优化，改动会改变接口假设，普通接入保持 0 |
| Fudian SRT radix、迭代次数、`DivIterModule` 位宽 | 内部实现细节，且与精度强耦合 |
| 乘法器架构、流水级数、LZA/CLZ 实现 | 微架构细节，不属于软件可见配置 |
| `RawFloat`、RecFN 编码、`to_fadd` 级联接口 | 内部表示/内部连接，不应成为 SoC 边界 |
| `detectTininess` 的 before-rounding 选项 | RISC-V 要求 after-rounding；暴露错误选项只会产生不合规配置 |
| 测试用 `berkeley-softfloat-3`/`berkeley-testfloat-3` | 仅用于验证，不应作为 RTL 依赖或 submodule |

### 6.3 推荐默认配置

```text
RISCV_F=y
RISCV_D=n
RISCV_FPU_IMPL=HARDFLOAT
RISCV_FPU_FMA=y
RISCV_FPU_DIVSQRT=y
RISCV_FPU_DIVSQRT_2BIT=n
RISCV_FPU_TININESS_AFTER=y
```

说明：当前 NPC 的 `XLEN` 默认是 32，且 `RISCV_F`/`RISCV_D` 仍是未实现的占位项，所以第一版建议先做 RV32F（FLEN=32、转换位宽 32）；若同时实现 RV64D，则 `RISCV_D=y`、FLEN=64，转换位宽跟随 XLEN。

---

## 7. 推荐接入方式

### 7.1 选型建议

1. **优先 HardFloat 做第一版**
   - 优点：RecFN 内部表示成熟，模块划分清晰；加/乘/FMA/比较/转换都是组合逻辑，容易插入现有流水线；`DivSqrtRecFN_small` 同时支持除法和开方，且精度可参数化；`classifyRecFN` 直接给出 RISC-V `fclass`。
   - 代价：边界需要 `recFNFromFN`/`fNFromRecFN` 转换；FMA 和除法需要自己加流水/握手适配；上游 Chisel 3.5.6，需在 NPC 的 Chisel 7.7 下验证。

2. **需要更高频率/更小面积时评估 Fudian**
   - 优点：端口直接是 IEEE 位模式，和 RISC-V 指令语义贴近；`FADD`/`FMUL`/`FCMA`/`FPToInt`/`IntToFP`/`FPToFP` 都是组合逻辑，便于在外部按需插流水；`FDIV` 自带 valid/ready 握手。
   - 代价：没有内部流水寄存器，高频需要自己切；`FCMA` 只支持 `a*b+c`；`FPToFP` 只支持同方向精度转换；许可证需要确认。

3. **不建议**在同一数据通路里同时例化两套 FPU 做“运行时二选一”。如果确实要做对比，建议用 Kconfig 在编译期二选一，或只生成其中一套的 Verilog。

### 7.2 包装层建议

无论选哪套，建议在 NPC 侧加一个统一的 `fpu_wrapper`，把差异隔离在 wrapper 内。建议端口：

```text
// 请求
input  logic         req_valid;
output logic         req_ready;
input  logic [4:0]   req_op;       // 译码后的 FPU 操作
input  logic [2:0]   req_rm;       // fcsr.frm
input  logic [FLEN-1:0] req_src1;
input  logic [FLEN-1:0] req_src2;
input  logic [FLEN-1:0] req_src3;  // FMA 用

// 响应
output logic         resp_valid;
input  logic         resp_ready;
output logic [FLEN-1:0] resp_result;
output logic [4:0]   resp_fflags;  // {NV,DZ,OF,UF,NX}
```

包装层职责：

- HardFloat 侧：在边界做 IEEE↔RecFN 转换；`rm` 直接接 `roundingMode`；`detectTininess` 恒接 1；组合模块按 NPC 流水级插入寄存器；`DivSqrtRecFN_small` 的 `outValid_div/outValid_sqrt` 合并为 `resp_valid`。
- Fudian 侧：`FADD` 的减法在 wrapper 翻转 `src2` 符号；`FCMA` 的 fnmadd/fnmsub 在 wrapper 处理符号；`FDIV` 的 `specialIO` 映射到统一的 `req_valid/req_ready/resp_valid/resp_ready`；组合模块外部插流水。
- 异常：HardFloat `RecFNToIN` 的 `invalid|overflow` 合并为 NV；两套的 5 位 fflags 直接送 CSR。
- `fclass`：HardFloat 用 `classifyRecFN`；Fudian 可用 `FCMP` + 符号/指数分类组合，或复用 HardFloat 的 `classifyRecFN`（只依赖 IEEE 位模式时也可在 wrapper 内实现）。

### 7.3 接入步骤（建议）

1. **编译验证**：在 NPC 的 mill/Chisel 7.7 工程中新增一个 vendor source 目录或独立 mill 模块，先只编译 `vendor/berkeley-hardfloat/hardfloat/src/main/scala` 和 `vendor/fudian/src/main/scala`，确认没有 Chisel API 兼容性问题。
2. **生成 Verilog（备选）**：如果源码级兼容成本高，先用上游 Chisel 3.5/3.6 生成固定参数的 Verilog，把生成文件放到 `npc/rtl/vendor/` 或综合脚本的 filelist 中；这样可以把 Chisel 版本差异隔离在生成阶段。
3. **实现 wrapper**：只实现 §7.2 的统一接口，不修改 vendor 源码；所有精度/实现差异在 wrapper 或 Kconfig 中处理。
4. **验证**：
   - 用 RISC-V `riscv-tests` 的 `rv32uf`/`rv64ud` 或自建指令级测试覆盖 add/sub/mul/fma/div/sqrt/cvt/compare/classify；
   - 用 testfloat 对拍（需要恢复上游 `berkeley-testfloat-3`/`berkeley-softfloat-3` 测试依赖）；
   - 重点覆盖 NaN 传播、sNaN、±0、subnormal、overflow/underflow、非法舍入模式、除零、开方负数。
5. **综合检查**：两套库默认都没有流水寄存器，先看关键路径；HardFloat 的 `DivSqrtRecFN_small` 和 Fudian 的 `FDIV` 都是多周期，注意时钟域/复位和握手稳定性。

### 7.4 版本兼容性提示

- NPC 当前 `npc/build.mill` 使用 Chisel 7.7.0；HardFloat 上游按 Chisel 3.5.6，Fudian 按 3.6.0。
- 两套源码都使用 `chisel3._`/`chisel3.util._` 的常见 API，理论上大部分可在新版本编译，但 `ChiselStage`、`ValidIO`、`Enum`、`UIntToOH` 等 API 在不同版本间有差异，必须以实际编译结果为准。
- 如果直接在 NPC 中 source-level 引入，建议为 vendor 代码单独建一个 mill 子模块，避免和 NPC 的 scalafmt/编译选项互相影响。
- 如果生成 Verilog 接入，建议在生成脚本中固定 `expWidth/sigWidth/options`，并把生成文件纳入版本管理或 CI 产物，不要依赖运行时随机生成。

---

## 8. 风险与注意事项

1. **HardFloat 的 RecFN 不是 IEEE 位模式**：这是最容易接错的地方。所有边界必须转换；不要把 `fNFromRecFN` 的结果再当 RecFN 用。
2. **`sigWidth`/`precision` 含隐藏位**：f32 是 24，不是 23；写参数时不要照搬 IEEE 存储位宽。
3. **HardFloat FMA 的 `op` 编码**：上游测试只覆盖 `op=0`，fnmadd/fnmsub 建议自行对拍。
4. **Fudian 没有内部流水**：`s1/s2/s3` 不是寄存器级；如果直接接到高频 NPC 流水线，组合路径可能很长。
5. **Fudian `FPToFP` 只支持同方向转换**：f32↔f64 可以，但不能做同精度或混合方向转换。
6. **Fudian 的 `FDIV` 是变长延迟**：`in_ready` 只在 idle 有效，`out_ready` 在 post 状态采样；不要假设固定 latency，也不要在 `out_valid` 前反复改变输入。
7. **HardFloat 的 `detectTininess` 必须稳定**：除法运算期间不能变化；RISC-V 固定 after-rounding。
8. **异常标志的语义差异**：HardFloat `RecFNToIN` 是 3 位，Fudian `FPToInt` 是 5 位；接入 CSR 前要统一映射，尤其 `overflow` 与 `invalid` 的合并。
9. **许可证**：HardFloat 的 BSD-3-Clause 文本已保留；Fudian 根目录没有 LICENSE 文件，只有 `FDIV.scala` 头部的 Mulan PSL v2 声明。若要将 Fudian 用于对外分发或商业项目，建议先向 OpenXiangShan 确认授权，并从官方地址补齐 Mulan PSL v2 文本；不要凭本文档自行推断整个仓库的许可证。
10. **测试依赖**：为了保持 vendor 目录干净，已移除空的 `berkeley-softfloat-3`/`berkeley-testfloat-3` 子模块目录；跑上游 testfloat 前需要手动恢复，详见 §1.2。

---

## 附录 A：公开模块速查

### HardFloat

```text
AddRecFN(expWidth, sigWidth)
MulRecFN(expWidth, sigWidth)
MulAddRecFN(expWidth, sigWidth)
CompareRecFN(expWidth, sigWidth)
DivSqrtRecFN_small(expWidth, sigWidth, options)
DivSqrtRecF64()                         // 固定 f64
INToRecFN(intWidth, expWidth, sigWidth)
RecFNToIN(expWidth, sigWidth, intWidth)
RecFNToRecFN(inExpWidth, inSigWidth, outExpWidth, outSigWidth)
RoundAnyRawFNToRecFN(inExpWidth, inSigWidth, outExpWidth, outSigWidth, options)
RoundRawFNToRecFN(expWidth, sigWidth, options)
```

辅助对象：`recFNFromFN`、`fNFromRecFN`、`rawFloatFromFN`、`rawFloatFromRecFN`、`classifyRecFN`、`isSigNaNRawFloat`、`resizeRawFloat`。

### Fudian

```text
FADD(expWidth, precision)
FMUL(expWidth, precision)
FCMA(expWidth, precision)
FCMP(expWidth, precision)
FDIV(expWidth, precision)
FPToInt(expWidth, precision)
IntToFP(expWidth, precision)
FPToFP(inExpWidth, inPrecision, outExpWidth, outPrecision)
```

辅助类型：`FloatPoint`、`RawFloat`、`FPDecodeBundle`、`FMULToFADD`、`FDIVIO`、`FDIVSpecialIO`；舍入常量 `RNE/RTZ/RDN/RUP/RMM`。

## 附录 B：复现 clone 的命令

```bash
# 直连 GitHub 超时时使用镜像
git clone --depth 1 \
  https://gh-proxy.com/https://github.com/ucb-bar/berkeley-hardfloat \
  vendor/berkeley-hardfloat

git clone --depth 1 \
  https://gh-proxy.com/https://github.com/OpenXiangShan/Fudian \
  vendor/fudian
```

入库前删除 `.git`、`.gitmodules` 和空的测试子模块目录；不要执行 `git submodule`，否则会重新引入 gitlink。
