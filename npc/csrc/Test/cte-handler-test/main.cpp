#include <am.h>
#include <klib.h>

namespace {

volatile int observed_event = Event::EVENT_NULL;
int failures = 0;

Context *handle_event(Event event, Context *context) {
  observed_event = event.event;
  return context;
}

void check(const char *name, bool condition) {
  printf("[%s] %s\n", name, condition ? "PASS" : "FAIL");
  if (!condition) {
    ++failures;
  }
}

} // namespace

int main() {
  check("cte-init", cte_init(handle_event));

  iset(false);
  check("interrupt-disable", !ienabled());
  iset(true);
  check("interrupt-enable", ienabled());
  iset(false);

  observed_event = Event::EVENT_NULL;
  yield();
  check("yield-handler", observed_event == Event::EVENT_YIELD);

  printf("CTE HANDLER TEST %s\n", failures == 0 ? "PASS" : "FAIL");
  return failures == 0 ? 0 : 1;
}
