#include <NDL.h>
#include <SDL.h>
#include <stdlib.h>
#include <string.h>

static SDL_AudioSpec audio_spec;
static uint8_t *audio_buf = NULL;
static int audio_opened = 0;
static int audio_paused = 1;
static int in_callback = 0;

static int audio_bytes_per_sample(void) {
  return audio_spec.format == AUDIO_U8 ? 1 : 2;
}

int SDL_OpenAudio(SDL_AudioSpec *desired, SDL_AudioSpec *obtained) {
  if (desired == NULL || audio_opened) {
    return -1;
  }

  audio_spec = *desired;
  if (obtained != NULL) {
    *obtained = *desired;
  }

  NDL_OpenAudio(audio_spec.freq, audio_spec.channels, audio_spec.samples);
  audio_buf = malloc((size_t)audio_spec.samples * audio_spec.channels *
                     audio_bytes_per_sample());
  if (audio_buf == NULL) {
    NDL_CloseAudio();
    return -1;
  }

  audio_opened = 1;
  audio_paused = 1;
  return 0;
}

void SDL_CloseAudio() {
  if (!audio_opened) {
    return;
  }
  NDL_CloseAudio();
  free(audio_buf);
  audio_buf = NULL;
  audio_opened = 0;
  audio_paused = 1;
}

void SDL_PauseAudio(int pause_on) {
  audio_paused = pause_on;
  if (!pause_on) {
    SDL_AudioCallbackHelper();
  }
}

void SDL_AudioCallbackHelper(void) {
  if (!audio_opened || audio_paused || audio_spec.callback == NULL ||
      in_callback) {
    return;
  }

  int len = (int)((size_t)audio_spec.samples * audio_spec.channels *
                  audio_bytes_per_sample());
  if (len <= 0 || NDL_QueryAudio() < len) {
    return;
  }

  in_callback = 1;
  audio_spec.callback(audio_spec.userdata, audio_buf, len);
  NDL_PlayAudio(audio_buf, len);
  in_callback = 0;
}

void SDL_MixAudio(uint8_t *dst, uint8_t *src, uint32_t len, int volume) {
  if (volume <= 0) {
    return;
  }
  if (volume > SDL_MIX_MAXVOLUME) {
    volume = SDL_MIX_MAXVOLUME;
  }

  int16_t *d = (int16_t *)dst;
  int16_t *s = (int16_t *)src;
  uint32_t samples = len / sizeof(int16_t);
  for (uint32_t i = 0; i < samples; i++) {
    int32_t v = d[i] + (int32_t)s[i] * volume / SDL_MIX_MAXVOLUME;
    if (v > 32767) v = 32767;
    if (v < -32768) v = -32768;
    d[i] = (int16_t)v;
  }
}

SDL_AudioSpec *SDL_LoadWAV(const char *file, SDL_AudioSpec *spec,
                           uint8_t **audio_buf_out, uint32_t *audio_len) {
  (void)file;
  (void)spec;
  (void)audio_buf_out;
  (void)audio_len;
  return NULL;
}

void SDL_FreeWAV(uint8_t *audio_buf_in) {
  (void)audio_buf_in;
}

void SDL_LockAudio() {
}

void SDL_UnlockAudio() {
}
