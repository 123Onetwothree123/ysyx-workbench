#include <stdint.h>

namespace {

volatile uint32_t words[4] = {0x13579bdfu, 0u, 0u, 0u};

} // namespace

extern "C" int main() {
  words[1] = words[0] ^ 0x2468ace0u;
  words[2] = words[1] + 0x10203u;
  words[3] = words[2] - words[0];
  return words[1] == 0x373f373fu && words[2] == 0x37403942u &&
                 words[3] == 0x23e89d63u
             ? 0
             : 1;
}
