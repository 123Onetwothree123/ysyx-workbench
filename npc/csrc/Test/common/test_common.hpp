#pragma once

#include <cstddef>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>

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

template <class DUT> class TestDriver {
public:
  explicit TestDriver(DUT &dut) : dut(dut) {}

  void Reset(int cycles = 2) {
    dut.reset = 1;
    for (int i = 0; i < cycles; ++i) {
      Tick();
    }
    dut.reset = 0;
    dut.eval();
  }

  void Tick(int cycles = 1) {
    for (int i = 0; i < cycles; ++i) {
      tick(dut);
      ++cycles_;
    }
  }

  template <class Predicate>
  void WaitUntil(Predicate predicate, std::string_view message, int limit = 1000) {
    for (int i = 0; i < limit; ++i) {
      dut.eval();
      if (predicate()) {
        return;
      }
      Tick();
    }
    throw std::runtime_error(std::string(message));
  }

  void Expect(bool condition, std::string_view message) const {
    check(condition, std::string(message));
  }

  [[nodiscard]] std::size_t Cycles() const { return cycles_; }
  [[nodiscard]] DUT &Get() { return dut; }

private:
  DUT &dut;
  std::size_t cycles_{0};
};

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
