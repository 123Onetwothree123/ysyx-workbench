#include <am.h>
#include <klib.h>

static volatile int observed_event = EVENT_NULL;
static int failures = 0;

static Context *handle_event(Event event, Context *context) {
  observed_event = event.event;
  return context;
}

static void check(const char *name, bool condition) {
  printf("[%s] %s\n", name, condition ? "PASS" : "FAIL");
  if (!condition) {
    ++failures;
  }
}

int main(void) {
  check("cte-init", cte_init(handle_event));

  iset(false);
  check("interrupt-disable", !ienabled());
  iset(true);
  check("interrupt-enable", ienabled());
  iset(false);

  observed_event = EVENT_NULL;
  yield();
  check("yield-handler", observed_event == EVENT_YIELD);

  printf("CTE HANDLER TEST %s\n", failures == 0 ? "PASS" : "FAIL");
  return failures == 0 ? 0 : 1;
}
