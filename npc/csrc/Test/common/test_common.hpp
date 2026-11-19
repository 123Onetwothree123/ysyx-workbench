#pragma once

#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>

inline void check(bool condition, const std::string &message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

template <class DUT> void tick(DUT &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

template <class DUT> void reset(DUT &dut) {
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
}

template <class DUT, class Predicate>
void wait_until(DUT &dut, Predicate predicate, const std::string &message,
                int limit = 100) {
  for (int cycle = 0; cycle < limit; ++cycle) {
    dut.eval();
    if (predicate()) {
      return;
    }
    tick(dut);
  }
  throw std::runtime_error(message);
}

template <class Body> int run_test(const char *name, Body body) {
  try {
    body();
    std::cout << "[PASS] " << name << std::endl;
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "[FAIL] " << name << ": " << error.what() << std::endl;
    return 1;
  }
}
