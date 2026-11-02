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
	tools/build/c_conf tools/build/c_mconf tools/kconfig/build tools/kconfig/gcm.cache \
	.config .config.old tools/build/merge_config
silent := -s

CONF          := $(KCONFIG_PATH)/../build/c_conf
MCONF         := $(KCONFIG_PATH)/../build/c_mconf
KCONFIG_BUILD := $(KCONFIG_PATH)/build
# Kconfig 工具自己的编译器/标准库，由 Kconfig 的“Kconfig 工具”菜单选择；
# 与 NPC 仿真器的 CONFIG_CC/CONFIG_STDLIB 相互独立。没有 .config 时回退到
# GCC + libstdc++，保证第一次 make menuconfig 可以启动。
KCONFIG_CXX    ?= $(or $(call remove_quote,$(CONFIG_KCONFIG_CC)),g++)
KCONFIG_STDLIB ?= $(or $(call remove_quote,$(CONFIG_KCONFIG_STDLIB)),libstdc++)
KCONFIG_BUILD_TYPE ?= $(or $(call remove_quote,$(CONFIG_KCONFIG_BUILD_TYPE)),Release)
KCONFIG_CXXFLAGS ?= $(call remove_quote,$(CONFIG_KCONFIG_CXXFLAGS))
KCONFIG_LDFLAGS  ?= $(call remove_quote,$(CONFIG_KCONFIG_LDFLAGS))
KCONFIG_TOOL_STAMP := $(KCONFIG_BUILD)/.tool-compiler
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

# 编译器/标准库选择变化时删除 CMakeCache，强制用新参数重新 configure。
$(KCONFIG_TOOL_STAMP): $(if $(wildcard .config),.config,)
	$(Q)mkdir -p $(KCONFIG_BUILD)
	$(Q)printf '%s\n' \
		"$(KCONFIG_CXX)" \
		"$(KCONFIG_STDLIB)" \
		"$(KCONFIG_BUILD_TYPE)" \
		"$(KCONFIG_CXXFLAGS)" \
		"$(KCONFIG_LDFLAGS)" > $@.tmp
	$(Q)if test -f $@ && cmp -s $@.tmp $@; then \
		rm -f $@.tmp; \
	else \
		rm -f $(KCONFIG_BUILD)/CMakeCache.txt; \
		mv -f $@.tmp $@; \
	fi

$(KCONFIG_BUILD)/CMakeCache.txt: $(KCONFIG_TOOL_STAMP)
	$(Q)mkdir -p $(KCONFIG_BUILD)
	$(Q)cd $(KCONFIG_BUILD) && cmake -G Ninja \
		-DCMAKE_CXX_COMPILER="$(KCONFIG_CXX)" \
		-DKCONFIG_STDLIB="$(KCONFIG_STDLIB)" \
		-DCMAKE_BUILD_TYPE="$(KCONFIG_BUILD_TYPE)" \
		-DCMAKE_CXX_FLAGS="$(KCONFIG_CXXFLAGS)" \
		-DCMAKE_EXE_LINKER_FLAGS="$(KCONFIG_LDFLAGS)" ..

menuconfig: $(MCONF) $(CONF) $(CONFIG_CONTRACT)
	$(Q)TERM=xterm LC_ALL=C.utf8 $(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)
	$(Q)$(CONFIG_CONTRACT) --config .config

# 只清理 menuconfig 的构建产物（CMake 目录、c_conf/c_mconf、模块缓存），
# 保留 .config 和 include/generated，避免误删用户当前配置。
.PHONY: menuconfig-clean
menuconfig-clean:
	@echo "清理 menuconfig 构建工具..."
	-@rm -rf $(KCONFIG_BUILD) $(CONF) $(MCONF) $(KCONFIG_PATH)/gcm.cache

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
