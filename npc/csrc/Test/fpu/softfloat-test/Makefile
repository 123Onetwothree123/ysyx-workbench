NAME = softfloat-test
SRCS = main.cpp
include $(AM_HOME)/Makefile

SOFTFLOAT_ABI_SYMBOLS = \
  __addsf3 __subsf3 __mulsf3 __divsf3 \
  __adddf3 __subdf3 __muldf3 __divdf3 \
  __negsf2 __negdf2 __extendsfdf2 __truncdfsf2 \
  __fixsfsi __fixunssfsi __fixsfdi __fixunssfdi \
  __fixdfsi __fixunsdfsi __fixdfdi __fixunsdfdi \
  __floatsisf __floatunsisf __floatdisf __floatundisf \
  __floatsidf __floatunsidf __floatdidf __floatundidf \
  __cmpsf2 __eqsf2 __nesf2 __lesf2 __ltsf2 __gesf2 __gtsf2 __unordsf2 \
  __cmpdf2 __eqdf2 __nedf2 __ledf2 __ltdf2 __gedf2 __gtdf2 __unorddf2
SOFTFLOAT_KLIB_ARCHIVE = $(AM_HOME)/klib/build/klib-$(ARCH).a

.PHONY: audit-softfloat
audit-softfloat: image
	@set -eu; \
	  for symbol in $(SOFTFLOAT_ABI_SYMBOLS); do \
	    $(CROSS_COMPILE)nm -g --defined-only $(SOFTFLOAT_KLIB_ARCHIVE) | \
	      grep -Eq "[[:space:]]$${symbol}$$" || { \
	        echo "missing soft-float ABI symbol: $${symbol}"; exit 1; \
	      }; \
	  done
	@$(READELF) -h $(IMAGE).elf | \
	  grep -Eq 'Flags:.*0x0([^0-9a-f]|$$)' || { \
	    echo 'ELF is not using the RISC-V soft-float ABI'; exit 1; \
	  }
	@arch_attr=`$(READELF) -A $(IMAGE).elf | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)"/\1/p'`; \
	  echo "RISC-V attributes: $${arch_attr}"; \
	  echo "$${arch_attr}" | grep -Eq '(^|_)f[0-9]' && { \
	    echo 'unexpected F extension in ELF attributes'; exit 1; \
	  } || true; \
	  echo "$${arch_attr}" | grep -Eq '(^|_)d[0-9]' && { \
	    echo 'unexpected D extension in ELF attributes'; exit 1; \
	  } || true
	@bad_mnemonics=`$(OBJDUMP) -d $(SOFTFLOAT_KLIB_ARCHIVE) | \
	  awk '/^[[:space:]]*[0-9a-f]+:/ { print $$3 }' | \
	  grep -E '^(c\.f|fl[wdq]$$|fs[wdq]$$|f(add|sub|mul|div|sqrt|min|max|sgnj|madd|msub|nmadd|nmsub|cvt|mv|class|eq|lt|le)\.|frcsr$$|fscsr$$|frrm$$|fsrm$$|frflags$$|fsflags$$)' || true`; \
	  test -z "$${bad_mnemonics}" || { \
	    echo 'unexpected floating-point instruction(s):'; \
	    echo "$${bad_mnemonics}"; exit 1; \
	  }
	@echo 'SOFTFLOAT ELF AUDIT PASS'
