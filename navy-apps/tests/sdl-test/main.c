#include <SDL.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main() {
  SDL_Init(0);

  SDL_Surface *screen = SDL_SetVideoMode(320, 200, 8, SDL_HWSURFACE);
  assert(screen != NULL);

  SDL_Color pal[256];
  for (int i = 0; i < 256; i++) {
    pal[i].r = (uint8_t)i;
    pal[i].g = (uint8_t)(255 - i);
    pal[i].b = (uint8_t)(i / 2);
    pal[i].a = 0;
  }
  SDL_SetPalette(screen, SDL_PHYSPAL | SDL_LOGPAL, pal, 0, 256);

  SDL_FillRect(screen, NULL, 5);

  SDL_Surface *tmp = SDL_CreateRGBSurface(SDL_SWSURFACE, 64, 64, 8, 0, 0, 0, 0);
  assert(tmp != NULL);
  SDL_SetPalette(tmp, SDL_LOGPAL, pal, 0, 256);
  SDL_FillRect(tmp, NULL, 9);

  SDL_Rect dst = { .x = 10, .y = 10, .w = 64, .h = 64 };
  SDL_BlitSurface(tmp, NULL, screen, &dst);
  SDL_UpdateRect(screen, 0, 0, 0, 0);

  SDL_FreeSurface(tmp);
  SDL_Quit();

  printf("sdl-test: PASS\n");
  return 0;
}
