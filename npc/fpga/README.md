# FPGA 顶层与板级约束

这个目录只服务独立 FPGA 构建，不参与 ysyxSoC Verilator 仿真。

当前是占位目录：`Kconfig.fpga` 的默认路径指向这里的
`pins.xdc`、`timing.xdc`（Vivado）和 `pins.qsf`、`timing.sdc`
（Quartus）。真正上板前需要按具体板卡补齐：

- 引脚约束：时钟、复位、UART、DDR、SD、SPI Flash、LED/GPIO
- 时序约束：输入时钟、MMCM/PLL 输出、跨时钟域、I/O delay
- 顶层：时钟/复位、NPC 核、AXI 互联、DDR/BRAM、UART、CLINT/PLIC
- 启动：boot ROM、UART/SD 加载、bitstream/Flash 分区

FPGA 顶层只实例化 NPC 核和必要的 AXI 适配，不实例化 ysyxSoC 的
xbar、APB 桥、PSRAM 控制器或 NVBoard。
