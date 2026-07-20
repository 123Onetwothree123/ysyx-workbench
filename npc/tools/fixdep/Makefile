NAME = fixdep
BUILD_DIR = $(CURDIR)/build
OBJ_DIR = $(BUILD_DIR)/obj-$(NAME)
BINARY = $(BUILD_DIR)/$(NAME)

CXX = g++
CFLAGS = -O2 -MMD -Wall -Werror -Wno-sign-compare $(CXXFLAGS)

OBJS = $(OBJ_DIR)/fixdep.o

$(OBJ_DIR)/%.o: %.cpp
	@echo + CXX $<
	@mkdir -p $(dir $@)
	@$(CXX) $(CFLAGS) -c -o $@ $<

$(BINARY): $(OBJS)
	@echo + LD $@
	@$(CXX) -o $@ $(OBJS)

.PHONY: clean
clean:
	rm -rf $(BUILD_DIR)
