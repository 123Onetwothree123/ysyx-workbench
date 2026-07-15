# C++23 kconfig — clang++ (libc++) or g++ (libstdc++), both with import std;
NAME = conf
obj := build
CXX ?= g++
CXXFLAGS = -std=gnu++23 -O2 -MMD -I. -Ilxdialog
NCURSES_LIBS = $(pkg-config --libs ncurses 2>/dev/null || echo '-lncurses') -ltinfo
LIBS =

IS_CLANG = $(filter clang%,$(notdir $(CXX)))

# Find std module source for the platform
STD_SRC_CLANG = /usr/share/libc++/v1/std.cppm
STD_SRC_GNU   = $(lastword $(wildcard /usr/include/c++/*/bits/std.cc))

ifneq ($(IS_CLANG),)
CXXFLAGS += -stdlib=libc++
STD_MOD = $(obj)/std.pcm
MODFLAGS = -fmodule-file=std=$(STD_MOD)
STD_OBJ =
else
CXXFLAGS += -fmodules
STD_SRC = $(STD_SRC_GNU)
STD_OBJ = $(obj)/std_module.o
MODFLAGS =
endif

CORE_OBJS = $(addprefix $(obj)/, confdata.o expr.o preprocess.o symbol.o util.o menu.o)
PARSER_OBJS = $(addprefix $(obj)/, lexer.lex.o parser.tab.o)
LXDOBJS = $(addprefix $(obj)/lxd_, checklist.o inputbox.o menubox.o textbox.o util.o yesno.o)

ifeq ($(NAME),conf)
OBJS = $(CORE_OBJS) $(PARSER_OBJS) $(obj)/conf.o $(STD_OBJ)
else ifeq ($(NAME),mconf)
OBJS = $(CORE_OBJS) $(PARSER_OBJS) $(LXDOBJS) $(obj)/mconf.o $(STD_OBJ)
LIBS += $(NCURSES_LIBS)
endif

BINARY = ../build/c_$(NAME)
all: $(BINARY)

# —— Clang ——
ifneq ($(IS_CLANG),)
$(STD_MOD): $(STD_SRC_CLANG)
	@echo + STD $@; mkdir -p $(obj)
	@$(CXX) $(CXXFLAGS) -x c++-module --precompile $< -o $@
$(obj)/%.o: %.cpp $(STD_MOD)
	@echo + CXX $<; mkdir -p $(obj); $(CXX) $(CXXFLAGS) $(MODFLAGS) -c -o $@ $<
$(obj)/lxd_%.o: lxdialog/%.cpp $(STD_MOD)
	@echo + CXX $<; mkdir -p $(obj); $(CXX) $(CXXFLAGS) $(MODFLAGS) -c -o $@ $<
$(obj)/lexer.lex.o: $(obj)/lexer.lex.cpp $(STD_MOD)
	@echo + CXX $<; $(CXX) $(CXXFLAGS) $(MODFLAGS) -c -o $@ $<
$(obj)/parser.tab.o: $(obj)/parser.tab.cpp $(STD_MOD)
	@echo + CXX $<; $(CXX) $(CXXFLAGS) $(MODFLAGS) -c -o $@ $<

# —— GNU ——
else
$(STD_OBJ): $(STD_SRC)
	@echo + STD $@; mkdir -p $(obj)
	@$(CXX) $(CXXFLAGS) -x c++ -c $< -o $@
$(obj)/%.o: %.cpp $(STD_OBJ)
	@echo + CXX $<; mkdir -p $(obj); $(CXX) $(CXXFLAGS) -c -o $@ $<
$(obj)/lxd_%.o: lxdialog/%.cpp $(STD_OBJ)
	@echo + CXX $<; mkdir -p $(obj); $(CXX) $(CXXFLAGS) -c -o $@ $<
$(obj)/lexer.lex.o: $(obj)/lexer.lex.cpp $(STD_OBJ)
	@echo + CXX $<; $(CXX) $(CXXFLAGS) -c -o $@ $<
$(obj)/parser.tab.o: $(obj)/parser.tab.cpp $(STD_OBJ)
	@echo + CXX $<; $(CXX) $(CXXFLAGS) -c -o $@ $<
endif

$(obj)/lexer.lex.c: lexer.l $(obj)/parser.tab.h
	@echo + LEX $@; mkdir -p $(obj); flex -o $@ $<
$(obj)/lexer.lex.cpp: $(obj)/lexer.lex.c
	@echo + PREP $@
	@echo '#define input yyinput' > $(obj)/lexer.lex.tmp
	@sed 's/"lkc\.h"/"lkc.hpp"/g; s/"expr\.h"/"expr.hpp"/g; s/"list\.h"/"list.hpp"/g; s/"lkc_proto\.h"/"lkc_proto.hpp"/g' $< >> $(obj)/lexer.lex.tmp
	@mv $(obj)/lexer.lex.tmp $@
$(obj)/parser.tab.c $(obj)/parser.tab.h: parser.y
	@echo + YACC $@; mkdir -p $(obj)
	@bison -v $< --defines=$(obj)/parser.tab.h -o $(obj)/parser.tab.c
$(obj)/parser.tab.cpp: $(obj)/parser.tab.c
	@echo + PREP $@
	@sed 's/"lkc\.h"/"lkc.hpp"/g; s/"expr\.h"/"expr.hpp"/g; s/"list\.h"/"list.hpp"/g; s/"lkc_proto\.h"/"lkc_proto.hpp"/g' $< > $@

$(BINARY): $(OBJS)
	@echo + LD $@; mkdir -p $(dir $@); $(CXX) -o $@ $(OBJS) $(LIBS)

conf: $(BINARY)
mconf:
	@$(MAKE) -f Makefile.cpp NAME=mconf
clean:
	-rm -rf $(obj) gcm.cache ../build/c_conf ../build/c_mconf

.PHONY: all conf mconf clean
