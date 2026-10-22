V_FILE_GEN   = build/ysyxSoCTop.sv
V_FILE_FINAL = build/ysyxSoCFull.v
SCALA_FILES = $(shell find src/ -name "*.scala")
CHIPLINK ?= n
# CPU 侧 AXI4 端口宽度：32 或 64。未显式设置时跟随 CPU_XLEN/XLEN，
# 都没有则保持旧的 32 位行为。SoC 内部互联与外设始终是 32 位。
SOC_XLEN ?= $(or $(CPU_XLEN),$(XLEN),32)
ifeq ($(filter $(SOC_XLEN),32 64),)
$(error SOC_XLEN 必须是 32 或 64，当前值 '$(SOC_XLEN)')
endif
APB_CPU_FREQ_MHZ ?= 450
APB_DEVICE_FREQ_MHZ ?= 100
APB_DELAY_SCALE_FACTOR ?= 64
AXI_CPU_FREQ_MHZ ?= 450
AXI_DEVICE_FREQ_MHZ ?= 100
AXI_DELAY_SCALE_FACTOR ?= 64
export CHIPLINK SOC_XLEN APB_CPU_FREQ_MHZ APB_DEVICE_FREQ_MHZ APB_DELAY_SCALE_FACTOR
export AXI_CPU_FREQ_MHZ AXI_DEVICE_FREQ_MHZ AXI_DELAY_SCALE_FACTOR
ELAB_CONFIG = build/.elaboration-config
ELAB_CONFIG_TEXT = CHIPLINK=$(CHIPLINK) SOC_XLEN=$(SOC_XLEN) APB_CPU_FREQ_MHZ=$(APB_CPU_FREQ_MHZ) APB_DEVICE_FREQ_MHZ=$(APB_DEVICE_FREQ_MHZ) APB_DELAY_SCALE_FACTOR=$(APB_DELAY_SCALE_FACTOR) AXI_CPU_FREQ_MHZ=$(AXI_CPU_FREQ_MHZ) AXI_DEVICE_FREQ_MHZ=$(AXI_DEVICE_FREQ_MHZ) AXI_DELAY_SCALE_FACTOR=$(AXI_DELAY_SCALE_FACTOR)

# Firtool version
FIRTOOL_VERSION = 1.105.0
FIRTOOL_PATCH_DIR = $(shell pwd)/patch/firtool

$(ELAB_CONFIG): FORCE
	@mkdir -p $(@D)
	@if [ ! -f $@ ] || [ "$$(cat $@)" != "$(ELAB_CONFIG_TEXT)" ]; then \
	  printf '%s\n' '$(ELAB_CONFIG_TEXT)' > $@; \
	fi

$(V_FILE_FINAL): $(SCALA_FILES) Makefile $(ELAB_CONFIG)
# Replace firtool with a newer version
# TODO: This can be removed after chisel publishes a new version
	@./patch/update-firtool.sh $(FIRTOOL_VERSION) $(FIRTOOL_PATCH_DIR)
	CHISEL_FIRTOOL_PATH=$(FIRTOOL_PATCH_DIR)/firtool-$(FIRTOOL_VERSION)/bin \
	mill -i runMain ysyx.Elaborate --target-dir $(@D)
	mv $(V_FILE_GEN) $@
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

verilog: $(V_FILE_FINAL)

# 显式选择 CPU 边界 XLEN；生成物仍叫 ysyxSoCFull.v，便于 npc/Makefile
# 复用同一个路径。elaboration stamp 会因 SOC_XLEN 变化触发重建。
verilog-32:
	$(MAKE) SOC_XLEN=32 verilog

verilog-64:
	$(MAKE) SOC_XLEN=64 verilog

clean:
	-rm -rf build/

dev-init:
	git submodule update --init --recursive
	cd rocket-chip && git apply ../patch/rocket-chip.patch

FORCE:

.PHONY: verilog verilog-32 verilog-64 clean dev-init FORCE
