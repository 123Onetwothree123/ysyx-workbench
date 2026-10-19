#include <NDL.h>
#include <sdl-timer.h>
#include <sdl-audio.h>
#include <sched.h>
#include <stddef.h>

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
  uint32_t start = NDL_GetTicks();
  while (NDL_GetTicks() - start < ms) {
    SDL_AudioCallbackHelper();
    // 时钟中断会抢占当前进程，这里不再主动 yield。
  }
}
