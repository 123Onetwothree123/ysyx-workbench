NAME = NVBoardLEDChaser
SRCS = ../common/NVBoardLEDChaser.cpp main.cpp
AM_TEST_CFLAGS += -I$(CURDIR)/../common
AM_TEST_CXXFLAGS += -std=c++26 -D_GLIBCXX_ASSERTIONS

include $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../common/am-test.mk)
