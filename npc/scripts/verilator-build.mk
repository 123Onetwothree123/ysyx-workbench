# Keep user C++ language/module flags away from Verilator's runtime, PCH,
# and generated model.  In particular, GNU Make's `private` modifier stops
# target-specific flags from propagating from a user object to its PCH
# prerequisite.

ifndef VERILATOR_GENERATED_MK
$(error VERILATOR_GENERATED_MK is not set)
endif

include $(VERILATOR_GENERATED_MK)

$(VK_USER_OBJS): private CPPFLAGS += $(NPC_USER_CXXFLAGS)
