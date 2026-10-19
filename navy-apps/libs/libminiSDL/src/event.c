#include <NDL.h>
#include <SDL.h>
#include <string.h>

#define keyname(k) #k,

static const char *keyname[] = {
  "NONE",
  _KEYS(keyname)
};

static uint8_t keystate[256];

int SDL_PushEvent(SDL_Event *ev) {
  (void)ev;
  return 0;
}

int SDL_PollEvent(SDL_Event *ev) {
  SDL_AudioCallbackHelper();
  char buf[64];
  if (!NDL_PollEvent(buf, sizeof(buf))) {
    return 0;
  }

  if (buf[0] != 'k' || (buf[1] != 'd' && buf[1] != 'u') || buf[2] != ' ') {
    return 0;
  }

  char *name = buf + 3;
  char *nl = strchr(name, '\n');
  if (nl != NULL) *nl = '\0';

  int key = 0;
  for (size_t i = 1; i < sizeof(keyname) / sizeof(keyname[0]); i++) {
    if (strcmp(keyname[i], name) == 0) {
      key = (int)i;
      break;
    }
  }
  if (key == 0) {
    return 0;
  }

  if (ev != NULL) {
    ev->type = (buf[1] == 'd') ? SDL_KEYDOWN : SDL_KEYUP;
    ev->key.keysym.sym = (uint8_t)key;
  }
  keystate[key] = (buf[1] == 'd') ? 1 : 0;
  return 1;
}

int SDL_WaitEvent(SDL_Event *event) {
  while (!SDL_PollEvent(event)) {
  }
  return 1;
}

int SDL_PeepEvents(SDL_Event *ev, int numevents, int action, uint32_t mask) {
  (void)ev;
  (void)numevents;
  (void)action;
  (void)mask;
  return 0;
}

uint8_t* SDL_GetKeyState(int *numkeys) {
  if (numkeys != NULL) {
    *numkeys = (int)(sizeof(keystate) / sizeof(keystate[0]));
  }
  return keystate;
}
