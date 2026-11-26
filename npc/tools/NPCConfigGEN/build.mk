NPC_CONFIG_GEN_BUILD_MK := $(lastword $(MAKEFILE_LIST))
NPC_CONFIG_GEN_JOBS ?= $(shell nproc 2>/dev/null || echo 4)
NPC_CONFIG_GEN_CMAKE_FLAGS ?= -G Ninja -DCMAKE_BUILD_TYPE=Release
# 配置生成器是 host 工具，不参与 RTL 仿真；LLVM 构建下仍用 g++/libstdc++，
# 避免 CMake 自动 std 模块与项目的 libc++ std.cppm 冲突。
NPC_CONFIG_GEN_CXX ?= g++

$(NPC_CONFIG_GEN_BIN): $(NPC_CONFIG_GEN_SOURCES) $(NPC_CONFIG_GEN_MODULE) $(NPC_CONFIG_GEN_CMAKE) $(NPC_CONFIG_GEN_BUILD_MK)
	@cmake -S "$(NPC_CONFIG_GEN_DIR)" -B "$(NPC_CONFIG_GEN_BUILD_DIR)" \
		$(NPC_CONFIG_GEN_CMAKE_FLAGS) -DCMAKE_CXX_COMPILER="$(NPC_CONFIG_GEN_CXX)"
	@cmake --build "$(NPC_CONFIG_GEN_BUILD_DIR)" --target npc_config_gen -j $(NPC_CONFIG_GEN_JOBS)
	@cp -f "$(NPC_CONFIG_GEN_BUILD_DIR)/npc_config_gen" "$@"
