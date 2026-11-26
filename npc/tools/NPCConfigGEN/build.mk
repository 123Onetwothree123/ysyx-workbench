NPC_CONFIG_GEN_BUILD_MK := $(lastword $(MAKEFILE_LIST))
NPC_CONFIG_GEN_JOBS ?= $(shell nproc 2>/dev/null || echo 4)
# 配置生成器是 host 工具，使用 NPC 选择的编译器。Clang 的 C++23 标准库
# 模块目前只有 libc++ 可用，clang + libstdc++ 编不过 std 模块，因此这里
# 对 Clang 固定 libc++；它不影响仿真器自身的标准库选择。
NPC_CONFIG_GEN_CXX ?= $(CXX)
NPC_CONFIG_GEN_STDLIB_FLAGS = $(if $(filter clang% icpx%,$(notdir $(NPC_CONFIG_GEN_CXX))),-stdlib=libc++,)
NPC_CONFIG_GEN_CMAKE_FLAGS ?= -G Ninja -DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_CXX_FLAGS="$(NPC_CONFIG_GEN_STDLIB_FLAGS)"

# 编译器或标准库变化时 CMake 拒绝复用旧 cache，先清掉再配置。
NPC_CONFIG_GEN_COMPILER_STAMP := $(NPC_CONFIG_GEN_BUILD_DIR)/.compiler
NPC_CONFIG_GEN_CONFIG := $(wildcard .config)

$(NPC_CONFIG_GEN_BIN): $(NPC_CONFIG_GEN_SOURCES) $(NPC_CONFIG_GEN_MODULE) $(NPC_CONFIG_GEN_CMAKE) $(NPC_CONFIG_GEN_BUILD_MK) $(NPC_CONFIG_GEN_CONFIG)
	@mkdir -p "$(NPC_CONFIG_GEN_BUILD_DIR)"
	@printf '%s\n' "$(NPC_CONFIG_GEN_CXX)" "$(NPC_CONFIG_GEN_STDLIB_FLAGS)" > "$(NPC_CONFIG_GEN_COMPILER_STAMP).tmp"
	@if test -f "$(NPC_CONFIG_GEN_COMPILER_STAMP)" && cmp -s "$(NPC_CONFIG_GEN_COMPILER_STAMP).tmp" "$(NPC_CONFIG_GEN_COMPILER_STAMP)"; then \
		rm -f "$(NPC_CONFIG_GEN_COMPILER_STAMP).tmp"; \
	else \
		rm -f "$(NPC_CONFIG_GEN_BUILD_DIR)/CMakeCache.txt"; \
		mv -f "$(NPC_CONFIG_GEN_COMPILER_STAMP).tmp" "$(NPC_CONFIG_GEN_COMPILER_STAMP)"; \
	fi
	@cmake -S "$(NPC_CONFIG_GEN_DIR)" -B "$(NPC_CONFIG_GEN_BUILD_DIR)" \
		$(NPC_CONFIG_GEN_CMAKE_FLAGS) -DCMAKE_CXX_COMPILER="$(NPC_CONFIG_GEN_CXX)"
	@cmake --build "$(NPC_CONFIG_GEN_BUILD_DIR)" --target npc_config_gen -j $(NPC_CONFIG_GEN_JOBS)
	@cp -f "$(NPC_CONFIG_GEN_BUILD_DIR)/npc_config_gen" "$@"
