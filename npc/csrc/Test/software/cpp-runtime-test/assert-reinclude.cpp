#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>

static int evaluation_count;

static void assertion_enabled_before(void)
{
  assert(++evaluation_count == 1);
}

#define NDEBUG
#include <cassert>

static void assertion_disabled(void)
{
  assert(++evaluation_count == 2);
}

#undef NDEBUG
#include <cassert>

static void assertion_enabled_after(void)
{
  assert(++evaluation_count == 2);
}

extern "C" int cpp_assert_reinclude_probe(void)
{
  evaluation_count = 0;
  assertion_enabled_before();
  if (evaluation_count != 1) return 0;
  assertion_disabled();
  if (evaluation_count != 1) return 0;
  assertion_enabled_after();
  return evaluation_count == 2;
}
