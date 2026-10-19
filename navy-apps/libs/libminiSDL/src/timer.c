#include <NDL.h>
#include <sdl-timer.h>
#include <sdl-audio.h>
#include <sched.h>
#include <stddef.h>

int sched_yield(void);

SDL_TimerID SDL_AddTimer(uint32_t interval, SDL_NewTimerCallback callback, void *param) {
  (void)interval;
  (void)callback;
  (void)param;
  return NULL;
}

int SDL_RemoveTimer(SDL_TimerID id) {
  (void)id;
  return 1;
}

uint32_t SDL_GetTicks() {
  SDL_AudioCallbackHelper();
  return NDL_GetTicks();
}

void SDL_Delay(uint32_t ms) {
  static uint32_t last_yield = 0;
  uint32_t start = NDL_GetTicks();

  while (NDL_GetTicks() - start < ms) {
    SDL_AudioCallbackHelper();
    // 每 10ms 让出一次 CPU，避免用户进程长期独占调度器。
    uint32_t now = NDL_GetTicks();
    if (now - last_yield >= 10) {
      last_yield = now;
      sched_yield();
    }
  }
}
