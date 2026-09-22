#include <am.h>
#include <errno.h>
#include <klib.h>

#include "stdio_impl.h"

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#define ATEXIT_MAX_HANDLERS 32u

typedef struct
{
  union
  {
    void (*plain)(void);
    void (*with_argument)(void *);
  } function;
  void *argument;
  void *dso;
  int takes_argument;
  int active;
} ExitHandler;

static ExitHandler exit_handlers[ATEXIT_MAX_HANDLERS];
static size_t exit_handler_count;
static int exit_in_progress;
static int init_arrays_ran;
static int fini_arrays_ran;

typedef void (*ArrayFunction)(void);
extern ArrayFunction __preinit_array_start[];
extern ArrayFunction __preinit_array_end[];
extern ArrayFunction __init_array_start[];
extern ArrayFunction __init_array_end[];
extern ArrayFunction __fini_array_start[];
extern ArrayFunction __fini_array_end[];

void __klib_init_array(void)
{
  if (init_arrays_ran)
  {
    return;
  }
  init_arrays_ran = 1;
  for (ArrayFunction *function = __preinit_array_start;
       function != __preinit_array_end; function++)
  {
    (*function)();
  }
  for (ArrayFunction *function = __init_array_start;
       function != __init_array_end; function++)
  {
    (*function)();
  }
}

static void run_fini_array(void)
{
  if (!init_arrays_ran || fini_arrays_ran)
  {
    return;
  }
  fini_arrays_ran = 1;
  for (ArrayFunction *function = __fini_array_end;
       function != __fini_array_start;)
  {
    (*--function)();
  }
}

static void run_exit_handler(ExitHandler *handler)
{
  if (!handler->active)
  {
    return;
  }
  handler->active = 0;
  if (handler->takes_argument)
  {
    handler->function.with_argument(handler->argument);
  }
  else
  {
    handler->function.plain();
  }
}

int atexit(void (*function)(void))
{
  if (function == NULL)
  {
    errno = EINVAL;
    return -1;
  }
  if (exit_handler_count == ATEXIT_MAX_HANDLERS)
  {
    errno = ENOMEM;
    return -1;
  }
  exit_handlers[exit_handler_count++] = (ExitHandler){
      .function.plain = function,
      .active = 1,
  };
  return 0;
}

int __cxa_atexit(void (*function)(void *), void *argument, void *dso)
{
  if (function == NULL || exit_handler_count == ATEXIT_MAX_HANDLERS)
  {
    errno = function == NULL ? EINVAL : ENOMEM;
    return -1;
  }
  exit_handlers[exit_handler_count++] = (ExitHandler){
      .function.with_argument = function,
      .argument = argument,
      .dso = dso,
      .takes_argument = 1,
      .active = 1,
  };
  return 0;
}

void __cxa_finalize(void *dso)
{
  for (size_t i = exit_handler_count; i != 0; i--)
  {
    ExitHandler *handler = &exit_handlers[i - 1];
    if (handler->active && handler->takes_argument &&
        (dso == NULL || handler->dso == dso))
    {
      run_exit_handler(handler);
    }
  }
}

void exit(int status)
{
  if (!exit_in_progress)
  {
    exit_in_progress = 1;
    while (exit_handler_count != 0)
    {
      run_exit_handler(&exit_handlers[--exit_handler_count]);
    }
    run_fini_array();
    (void)fflush(NULL);
    __kfile_close_all();
  }
  halt(status);
}

void _Exit(int status)
{
  halt(status);
}

void abort(void)
{
  halt(134);
}

#endif
