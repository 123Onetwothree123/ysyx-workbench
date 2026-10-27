COLOR_RED := $(shell echo "\033[1;31m")
COLOR_END := $(shell echo "\033[0m")

ifeq ($(wildcard .config),)
$(warning $(COLOR_RED)Warning: .config does not exist!$(COLOR_END))
$(warning $(COLOR_RED)To build the project, first run 'make menuconfig'.$(COLOR_END))
endif

Q            := @
KCONFIG_PATH := $(NPC_HOME)/tools/kconfig
FIXDEP_PATH  := $(NPC_HOME)/tools/fixdep
Kconfig      := $(NPC_HOME)/Kconfig
CONFIG_CONTRACT := $(NPC_HOME)/tools/build/kconfig_contract
rm-distclean += include/generated include/config csrc/generated \
	tools/build/npc_config_gen tools/build/kconfig_contract \
	tools/build/linux_boot_test tools/build/pack_uboot_image \
	tools/build/audit_rootfs_elf tools/build/build_dtb \
	tools/build/pack_linux_image \
	.config .config.old tools/build/merge_config
silent := -s

CONF          := $(KCONFIG_PATH)/../build/c_conf
MCONF         := $(KCONFIG_PATH)/../build/c_mconf
KCONFIG_BUILD := $(KCONFIG_PATH)/build
FIXDEP        := $(FIXDEP_PATH)/build/fixdep
PROFILE       ?=
MERGE_CONFIG_SRC := $(NPC_HOME)/tools/merge_config.cpp
MERGE_CONFIG := $(NPC_HOME)/tools/build/merge_config
MERGE_CONFIG_CXXFLAGS ?= -std=c++23 -O2 -Wall -Wextra

$(MERGE_CONFIG): $(MERGE_CONFIG_SRC) $(NPC_HOME)/tools/linux_image_common.hpp
	$(Q)mkdir -p $(@D)
	$(Q)$(CXX) $(MERGE_CONFIG_CXXFLAGS) -o $@ $<

merge-config-tool: $(MERGE_CONFIG)
.PHONY: merge-config-tool

KCONFIG_SOURCES := $(wildcard $(KCONFIG_PATH)/*.cpp $(KCONFIG_PATH)/*.ixx $(KCONFIG_PATH)/*.h $(KCONFIG_PATH)/*.hpp $(KCONFIG_PATH)/*.c $(KCONFIG_PATH)/*.y $(KCONFIG_PATH)/*.l $(KCONFIG_PATH)/lxdialog/*.cpp $(KCONFIG_PATH)/lxdialog/*.hpp)

$(MCONF): $(KCONFIG_BUILD)/CMakeCache.txt $(KCONFIG_SOURCES)
	$(Q)cmake --build $(KCONFIG_BUILD) -j $$(nproc)

$(CONF): $(MCONF)

# kconfig 使用 C++20 模块，CMake 必须使用 Ninja 生成器；默认的
# Unix Makefiles 不支持模块扫描，会在配置阶段直接报错。
$(KCONFIG_BUILD)/CMakeCache.txt:
	$(Q)mkdir -p $(KCONFIG_BUILD)
	$(Q)cd $(KCONFIG_BUILD) && cmake -G Ninja ..

$(FIXDEP):
	$(Q)$(MAKE) $(silent) -C $(FIXDEP_PATH)

menuconfig: $(MCONF) $(CONF) $(FIXDEP) $(CONFIG_CONTRACT)
	$(Q)TERM=xterm LC_ALL=C.utf8 $(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

savedefconfig: $(CONF)
	$(Q)$< $(silent) --$@=configs/defconfig $(Kconfig)

%defconfig: $(CONF) $(FIXDEP) $(CONFIG_CONTRACT)
	$(Q)$(CONFIG_CONTRACT) --config configs/$@
	$(Q)$< $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)$< $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

# 组合配置片段：profile 位于 configs/profiles/，其片段位于
# configs/fragments/。用法：
#   make profile-defconfig PROFILE=rv64_linux
#   make rv64_linux_profile
profile-defconfig: $(CONF) $(FIXDEP) $(CONFIG_CONTRACT) $(MERGE_CONFIG)
	$(Q)if [ -z "$(strip $(PROFILE))" ]; then \
		echo "错误：PROFILE 为空。" >&2; \
		echo "用法：make profile-defconfig PROFILE=rv64_linux" >&2; \
		exit 2; \
	fi
	$(Q)$(MERGE_CONFIG) "$(PROFILE)"

%_profile: $(CONF) $(FIXDEP) $(CONFIG_CONTRACT) $(MERGE_CONFIG)
	$(Q)$(MERGE_CONFIG) "$*"

.PHONY: menuconfig savedefconfig defconfig profile-defconfig

distclean: clean
	-@rm -rf $(rm-distclean)

.PHONY: distclean

define call_fixdep
	@$(FIXDEP) $(1) $(2) unused > $(1).tmp
	@mv $(1).tmp $(1)
endef
