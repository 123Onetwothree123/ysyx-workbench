NAME = minirvEMU
SRCS = main.cpp Decoder.cpp ImmGen.cpp minirvEMU.cpp ProgramLoader.cpp
HEX2BIN_NAME = hex2bin
HEX2BIN_SRC = hex2bin.cpp
BUILD_DIR = build
OBJS = $(addprefix $(BUILD_DIR)/,$(SRCS:.cpp=.o))
DEPS = $(addprefix $(BUILD_DIR)/,$(SRCS:.cpp=.d))
HEX2BIN_OBJ = $(BUILD_DIR)/$(HEX2BIN_SRC:.cpp=.o)
HEX2BIN_DEP = $(BUILD_DIR)/$(HEX2BIN_SRC:.cpp=.d)

CXX = g++
CXXFLAGS = -Wall -Wextra -g -O2 -MMD -MP

.PHONY: all clean run hex2bin

all: $(BUILD_DIR)/$(NAME)

hex2bin: $(BUILD_DIR)/$(HEX2BIN_NAME)

$(BUILD_DIR)/$(NAME): $(OBJS)
	$(CXX) $(CXXFLAGS) -o $@ $^

$(BUILD_DIR)/$(HEX2BIN_NAME): $(HEX2BIN_OBJ)
	$(CXX) $(CXXFLAGS) -o $@ $^

$(BUILD_DIR)/%.o: %.cpp | $(BUILD_DIR)
	$(CXX) $(CXXFLAGS) -c -o $@ $<

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

run: $(BUILD_DIR)/$(NAME)
	./$(BUILD_DIR)/$(NAME)

clean:
	rm -rf $(BUILD_DIR)

-include $(DEPS)
-include $(HEX2BIN_DEP)
