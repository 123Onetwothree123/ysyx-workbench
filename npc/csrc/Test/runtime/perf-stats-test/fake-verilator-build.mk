.PHONY: all

# A dry-run sink for the production Makefile's recursive Verilator build.
# The regression only needs to observe whether the top-level Verilator command
# is scheduled; no generated C++ exists in its synthetic BUILD_DIR.
all:
	@:
