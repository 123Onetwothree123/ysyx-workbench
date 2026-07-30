#!/bin/bash
# Build std.pcm from libc++ std.cppm
STD_SRC="/usr/share/libc++/v1/std.cppm"
mkdir -p build
clang++ -std=c++23 -stdlib=libc++ -x c++-module --precompile "$STD_SRC" -o build/std.pcm 2>&1 && echo "std.pcm built" || echo "FAILED"
