# NEMU 浮点参考实现使用的 Berkeley SoftFloat

本目录是从上游 Berkeley SoftFloat Release 3e 裁剪出的浮点软实现，
仅供 NEMU 的 RISC-V F/D 扩展使用。

- 上游：https://github.com/ucb-bar/berkeley-softfloat-3
- 版本：Release 3e（`master`，commit `32eb3295a3c814c1551f94ebc29eed1dec3cb3b1`）
- 许可：见 `COPYING.txt`（BSD 3-Clause）
- 特殊化：`source/RISCV/specialize.h`，采用 RISC-V 规定的
  canonical NaN、NaN 传播和整数转换饱和值。

## 为什么不用宿主 double

`float`/`double` 的舍入、NaN 传播和异常标志由宿主 C 实现决定，
无法保证与 RISC-V 架构逐位一致。本目录的 `f32_*`/`f64_*` 函数
使用整数运算完成舍入和异常检测，NEMU 只通过这些函数执行浮点运算。

## 裁剪范围

只保留 F/D（binary32/binary64）所需文件：

- 算术：add/sub/mul/div/sqrt/mulAdd（FMA）
- 比较：eq/lt/le 及 quiet/signaling 版本
- 转换：f32↔f64、i32/i64/ui32/ui64↔f32/f64
- RISC-V 专用：`softfloat_raiseFlags`、`s_propagateNaNF32UI`、
  `s_propagateNaNF64UI`

未包含 binary16/bfloat16、80/128 位格式、rem 和 roundToInt，这些
不属于 NEMU 的 RV32/RV64 F/D 指令集。

`platform.h` 根据宿主端序选择布局，并打开 `SOFTFLOAT_FAST_INT64`。
`src/isa/riscv32/fpu.c` 负责把 `fcsr.frm/fflags` 映射到
`softfloat_roundingMode/softfloat_exceptionFlags`。
