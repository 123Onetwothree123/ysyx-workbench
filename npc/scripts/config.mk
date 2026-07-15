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
rm-distclean += include/generated include/config .config .config.old
silent := -s

CONF          := $(KCONFIG_PATH)/../build/c_conf
MCONF         := $(KCONFIG_PATH)/../build/c_mconf
KCONFIG_BUILD := $(KCONFIG_PATH)/build
FIXDEP        := $(FIXDEP_PATH)/build/fixdep

$(CONF) $(MCONF): $(KCONFIG_BUILD)/CMakeCache.txt
	$(Q)cmake --build $(KCONFIG_BUILD) $(silent)

$(KCONFIG_BUILD)/CMakeCache.txt:
	$(Q)mkdir -p $(KCONFIG_BUILD) && cd $(KCONFIG_BUILD) && cmake .. $(silent)

$(FIXDEP):
	$(Q)$(MAKE) $(silent) -C $(FIXDEP_PATH)

menuconfig: $(MCONF) $(CONF) $(FIXDEP)
	$(Q)$(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)

savedefconfig: $(CONF)
	$(Q)$< $(silent) --$@=configs/defconfig $(Kconfig)

%defconfig: $(CONF) $(FIXDEP)
	$(Q)$< $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)$< $(silent) --syncconfig $(Kconfig)

.PHONY: menuconfig savedefconfig defconfig

distclean: clean
	-@rm -rf $(rm-distclean)

.PHONY: distclean

define call_fixdep
	@$(FIXDEP) $(1) $(2) unused > $(1).tmp
	@mv $(1).tmp $(1)
endef
