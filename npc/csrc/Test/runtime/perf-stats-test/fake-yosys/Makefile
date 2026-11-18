SHELL := /bin/bash

.PHONY: syn sta

# Minimal yosys-sta stand-in used to prove that simultaneous top-level builds
# cannot consume each other's synth_stat.txt.  The area identifies the RTL
# fixture that was supplied by each build.
syn:
	@set -eu; \
	result_dir="$(O)/$(DESIGN)-$(CLK_FREQ_MHZ)MHz-$(PDK)"; \
	mkdir -p "$$result_dir"; \
	if test "$(FAKE_YOSYS_FAIL)" = 1; then \
	  echo 'intentional fake synthesis failure' >&2; \
	  exit 42; \
	fi; \
	case "$(RTL_FILES)" in \
	  *isolation-a*) area=111.1 ;; \
	  *isolation-b*) area=222.2 ;; \
	  *missing-stat*) area=333.3 ;; \
	  *synth-failure*) area=444.4 ;; \
	  *) echo "unexpected RTL_FILES: $(RTL_FILES)" >&2; exit 1 ;; \
	esac; \
	sleep 1; \
	if test "$(FAKE_YOSYS_SKIP_STAT)" != 1; then \
	  printf 'Chip area test result is %s\n' "$$area" > "$$result_dir/synth_stat.txt"; \
	fi

sta: syn
	@:
