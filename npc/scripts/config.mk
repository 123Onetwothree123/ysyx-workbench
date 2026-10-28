COLOR_RED := $(shell echo "\033[1;31m")
COLOR_END := $(shell echo "\033[0m")

ifeq ($(wildcard .config),)
$(warning $(COLOR_RED)Warning: .config does not exist!$(COLOR_END))
$(warning $(COLOR_RED)To build the project, first run 'make menuconfig'.$(COLOR_END))
endif

Q            := @
KCONFIG_PATH := $(NPC_HOME)/tools/kconfig
Kconfig      := $(NPC_HOME)/Kconfig
CONFIG_CONTRACT := $(NPC_HOME)/tools/build/kconfig_contract
rm-distclean += include/generated include/config csrc/generated \
	tools/build/npc_config_gen tools/build/kconfig_contract \
	tools/build/linux_boot_test tools/build/pack_uboot_image \
	tools/build/audit_rootfs_elf tools/build/build_dtb \
	tools/build/pack_linux_image tools/build/NPCConfigGEN tools/build/BuildTools tools/build/KconfigContract tools/build/LinuxBootTest \
	.config .config.old tools/build/merge_config
silent := -s

CONF          := $(KCONFIG_PATH)/../build/c_conf
MCONF         := $(KCONFIG_PATH)/../build/c_mconf
KCONFIG_BUILD := $(KCONFIG_PATH)/build
PROFILE       ?=
MERGE_CONFIG := $(NPC_HOME)/tools/build/merge_config

$(MERGE_CONFIG): $(NPC_BUILD_TOOLS_STAMP)
	$(Q)mkdir -p $(@D)
	$(Q)cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/merge_config" "$@"

merge-config-tool: $(MERGE_CONFIG)
.PHONY: merge-config-tool

KCONFIG_SOURCES := $(wildcard $(KCONFIG_PATH)/*.cpp $(KCONFIG_PATH)/*.ixx $(KCONFIG_PATH)/*.h $(KCONFIG_PATH)/*.hpp $(KCONFIG_PATH)/*.c $(KCONFIG_PATH)/*.y $(KCONFIG_PATH)/*.l $(KCONFIG_PATH)/lxdialog/*.cpp $(KCONFIG_PATH)/lxdialog/*.hpp)

$(MCONF): $(KCONFIG_BUILD)/CMakeCache.txt $(KCONFIG_SOURCES)
	$(Q)cmake --build $(KCONFIG_BUILD) -j $$(nproc)

$(CONF): $(MCONF)

$(KCONFIG_BUILD)/CMakeCache.txt:
	$(Q)mkdir -p $(KCONFIG_BUILD)
	$(Q)cd $(KCONFIG_BUILD) && cmake -G Ninja ..

menuconfig: $(MCONF) $(CONF) $(CONFIG_CONTRACT)
	$(Q)TERM=xterm LC_ALL=C.utf8 $(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

savedefconfig: $(CONF)
	$(Q)$< $(silent) --$@=configs/defconfig $(Kconfig)

%defconfig: $(CONF) $(CONFIG_CONTRACT)
	$(Q)$(CONFIG_CONTRACT) --config configs/$@
	$(Q)$< $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)$< $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

profile-defconfig: $(CONF) $(CONFIG_CONTRACT) $(MERGE_CONFIG)
	$(Q)if [ -z "$(strip $(PROFILE))" ]; then \
		echo "错误：PROFILE 为空。" >&2; \
		echo "用法：make profile-defconfig PROFILE=rv64_linux" >&2; \
		exit 2; \
	fi
	$(Q)$(MERGE_CONFIG) "$(PROFILE)"

%_profile: $(CONF) $(CONFIG_CONTRACT) $(MERGE_CONFIG)
	$(Q)$(MERGE_CONFIG) "$*"

.PHONY: menuconfig savedefconfig defconfig profile-defconfig

distclean: clean
	-@rm -rf $(rm-distclean)

.PHONY: distclean
