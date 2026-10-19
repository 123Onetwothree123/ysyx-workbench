#include <NDL.h>
#include <sdl-video.h>
#include <assert.h>
#include <string.h>
#include <stdlib.h>

static void clip_rect(int *x, int *y, int *w, int *h, int max_w, int max_h) {
  if (*x < 0) { *w += *x; *x = 0; }
  if (*y < 0) { *h += *y; *y = 0; }
  if (*x + *w > max_w) *w = max_w - *x;
  if (*y + *h > max_h) *h = max_h - *y;
  if (*w < 0) *w = 0;
  if (*h < 0) *h = 0;
}

void SDL_BlitSurface(SDL_Surface *src, SDL_Rect *srcrect, SDL_Surface *dst, SDL_Rect *dstrect) {
  assert(dst && src);
  assert(dst->format->BitsPerPixel == src->format->BitsPerPixel);

  int sx = 0, sy = 0, sw = src->w, sh = src->h;
  if (srcrect != NULL) {
    sx = srcrect->x;
    sy = srcrect->y;
    sw = srcrect->w;
    sh = srcrect->h;
  }
  int dx = 0, dy = 0;
  if (dstrect != NULL) {
    dx = dstrect->x;
    dy = dstrect->y;
  }

  if (sx < 0) { dx -= sx; sw += sx; sx = 0; }
  if (sy < 0) { dy -= sy; sh += sy; sy = 0; }
  if (sx + sw > src->w) sw = src->w - sx;
  if (sy + sh > src->h) sh = src->h - sy;
  if (dx < 0) { sx -= dx; sw += dx; dx = 0; }
  if (dy < 0) { sy -= dy; sh += dy; dy = 0; }
  if (dx + sw > dst->w) sw = dst->w - dx;
  if (dy + sh > dst->h) sh = dst->h - dy;
  if (sw <= 0 || sh <= 0) return;

  int bytes = src->format->BytesPerPixel;
  if (src == dst && dy > sy) {
    for (int i = sh - 1; i >= 0; i--) {
      memmove(dst->pixels + (dy + i) * dst->pitch + dx * bytes,
              src->pixels + (sy + i) * src->pitch + sx * bytes,
              (size_t)sw * bytes);
    }
  } else {
    for (int i = 0; i < sh; i++) {
      memmove(dst->pixels + (dy + i) * dst->pitch + dx * bytes,
              src->pixels + (sy + i) * src->pitch + sx * bytes,
              (size_t)sw * bytes);
    }
  }
}

void SDL_FillRect(SDL_Surface *dst, SDL_Rect *dstrect, uint32_t color) {
  assert(dst != NULL);
  int x = 0, y = 0, w = dst->w, h = dst->h;
  if (dstrect != NULL) {
    x = dstrect->x;
    y = dstrect->y;
    w = dstrect->w;
    h = dstrect->h;
  }
  clip_rect(&x, &y, &w, &h, dst->w, dst->h);
  if (w <= 0 || h <= 0) return;

  int bpp = dst->format->BitsPerPixel;
  for (int i = 0; i < h; i++) {
    if (bpp == 8) {
      memset(dst->pixels + (y + i) * dst->pitch + x, (int)(color & 0xff), w);
    } else {
      uint32_t *row = (uint32_t *)(dst->pixels + (y + i) * dst->pitch) + x;
      for (int j = 0; j < w; j++) row[j] = color;
    }
  }
}

void SDL_UpdateRect(SDL_Surface *s, int x, int y, int w, int h) {
  assert(s != NULL);
  if (w == 0 || h == 0) {
    x = 0;
    y = 0;
    w = s->w;
    h = s->h;
  }
  clip_rect(&x, &y, &w, &h, s->w, s->h);
  if (w <= 0 || h <= 0) return;

  if (s->format->BitsPerPixel == 8) {
    SDL_Palette *pal = s->format->palette;
    assert(pal != NULL);
    uint32_t *buf = malloc((size_t)w * h * sizeof(uint32_t));
    assert(buf != NULL);
    for (int i = 0; i < h; i++) {
      uint8_t *row = s->pixels + (y + i) * s->pitch + x;
      for (int j = 0; j < w; j++) {
        SDL_Color c = pal->colors[row[j]];
        buf[i * w + j] = ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) | c.b;
      }
    }
    NDL_DrawRect(buf, x, y, w, h);
    free(buf);
  } else {
    if (s->pitch == s->w * 4) {
      NDL_DrawRect((uint32_t *)s->pixels + y * s->w + x, x, y, w, h);
    } else {
      uint32_t *buf = malloc((size_t)w * h * sizeof(uint32_t));
      assert(buf != NULL);
      for (int i = 0; i < h; i++) {
        memcpy(buf + i * w, s->pixels + (y + i) * s->pitch + x * 4, (size_t)w * 4);
      }
      NDL_DrawRect(buf, x, y, w, h);
      free(buf);
    }
  }
}

// APIs below are already implemented.

static inline int maskToShift(uint32_t mask) {
  switch (mask) {
    case 0x000000ff: return 0;
    case 0x0000ff00: return 8;
    case 0x00ff0000: return 16;
    case 0xff000000: return 24;
    case 0x00000000: return 24; // hack
    default: assert(0);
  }
}

SDL_Surface* SDL_CreateRGBSurface(uint32_t flags, int width, int height, int depth,
    uint32_t Rmask, uint32_t Gmask, uint32_t Bmask, uint32_t Amask) {
  assert(depth == 8 || depth == 32);
  SDL_Surface *s = malloc(sizeof(SDL_Surface));
  assert(s);
  s->flags = flags;
  s->format = malloc(sizeof(SDL_PixelFormat));
  assert(s->format);
  if (depth == 8) {
    s->format->palette = malloc(sizeof(SDL_Palette));
    assert(s->format->palette);
    s->format->palette->colors = malloc(sizeof(SDL_Color) * 256);
    assert(s->format->palette->colors);
    memset(s->format->palette->colors, 0, sizeof(SDL_Color) * 256);
    s->format->palette->ncolors = 256;
  } else {
    s->format->palette = NULL;
    s->format->Rmask = Rmask; s->format->Rshift = maskToShift(Rmask); s->format->Rloss = 0;
    s->format->Gmask = Gmask; s->format->Gshift = maskToShift(Gmask); s->format->Gloss = 0;
    s->format->Bmask = Bmask; s->format->Bshift = maskToShift(Bmask); s->format->Bloss = 0;
    s->format->Amask = Amask; s->format->Ashift = maskToShift(Amask); s->format->Aloss = 0;
  }

  s->format->BitsPerPixel = depth;
  s->format->BytesPerPixel = depth / 8;

  s->w = width;
  s->h = height;
  s->pitch = width * depth / 8;
  assert(s->pitch == width * s->format->BytesPerPixel);

  if (!(flags & SDL_PREALLOC)) {
    s->pixels = malloc(s->pitch * height);
    assert(s->pixels);
  }

  return s;
}

SDL_Surface* SDL_CreateRGBSurfaceFrom(void *pixels, int width, int height, int depth,
    int pitch, uint32_t Rmask, uint32_t Gmask, uint32_t Bmask, uint32_t Amask) {
  SDL_Surface *s = SDL_CreateRGBSurface(SDL_PREALLOC, width, height, depth,
      Rmask, Gmask, Bmask, Amask);
  assert(pitch == s->pitch);
  s->pixels = pixels;
  return s;
}

void SDL_FreeSurface(SDL_Surface *s) {
  if (s != NULL) {
    if (s->format != NULL) {
      if (s->format->palette != NULL) {
        if (s->format->palette->colors != NULL) free(s->format->palette->colors);
        free(s->format->palette);
      }
      free(s->format);
    }
    if (s->pixels != NULL && !(s->flags & SDL_PREALLOC)) free(s->pixels);
    free(s);
  }
}

SDL_Surface* SDL_SetVideoMode(int width, int height, int bpp, uint32_t flags) {
  if (flags & SDL_HWSURFACE) NDL_OpenCanvas(&width, &height);
  return SDL_CreateRGBSurface(flags, width, height, bpp,
      DEFAULT_RMASK, DEFAULT_GMASK, DEFAULT_BMASK, DEFAULT_AMASK);
}

void SDL_SoftStretch(SDL_Surface *src, SDL_Rect *srcrect, SDL_Surface *dst, SDL_Rect *dstrect) {
  assert(src && dst);
  assert(dst->format->BitsPerPixel == src->format->BitsPerPixel);

  int sx = 0, sy = 0, sw = src->w, sh = src->h;
  if (srcrect != NULL) {
    sx = srcrect->x;
    sy = srcrect->y;
    sw = srcrect->w;
    sh = srcrect->h;
  }
  int dx = 0, dy = 0, dw = dst->w, dh = dst->h;
  if (dstrect != NULL) {
    dx = dstrect->x;
    dy = dstrect->y;
    dw = dstrect->w;
    dh = dstrect->h;
  }

  if (sx < 0) { sw += sx; sx = 0; }
  if (sy < 0) { sh += sy; sy = 0; }
  if (sx + sw > src->w) sw = src->w - sx;
  if (sy + sh > src->h) sh = src->h - sy;
  if (dx < 0) { dw += dx; dx = 0; }
  if (dy < 0) { dh += dy; dy = 0; }
  if (dx + dw > dst->w) dw = dst->w - dx;
  if (dy + dh > dst->h) dh = dst->h - dy;
  if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return;

  int bytes = src->format->BytesPerPixel;
  for (int y = 0; y < dh; y++) {
    int syy = sy + y * sh / dh;
    for (int x = 0; x < dw; x++) {
      int sxx = sx + x * sw / dw;
      memcpy(dst->pixels + (dy + y) * dst->pitch + (dx + x) * bytes,
             src->pixels + syy * src->pitch + sxx * bytes,
             bytes);
    }
  }
}

void SDL_SetPalette(SDL_Surface *s, int flags, SDL_Color *colors, int firstcolor, int ncolors) {
  assert(s);
  assert(s->format);
  assert(s->format->palette);
  assert(firstcolor == 0);

  s->format->palette->ncolors = ncolors;
  memcpy(s->format->palette->colors, colors, sizeof(SDL_Color) * ncolors);

  if(s->flags & SDL_HWSURFACE) {
    assert(ncolors == 256);
    for (int i = 0; i < ncolors; i ++) {
      uint8_t r = colors[i].r;
      uint8_t g = colors[i].g;
      uint8_t b = colors[i].b;
    }
    SDL_UpdateRect(s, 0, 0, 0, 0);
  }
}

static void ConvertPixelsARGB_ABGR(void *dst, void *src, int len) {
  int i;
  uint8_t (*pdst)[4] = dst;
  uint8_t (*psrc)[4] = src;
  union {
    uint8_t val8[4];
    uint32_t val32;
  } tmp;
  int first = len & ~0xf;
  for (i = 0; i < first; i += 16) {
#define macro(i) \
    tmp.val32 = *((uint32_t *)psrc[i]); \
    *((uint32_t *)pdst[i]) = tmp.val32; \
    pdst[i][0] = tmp.val8[2]; \
    pdst[i][2] = tmp.val8[0];

    macro(i + 0); macro(i + 1); macro(i + 2); macro(i + 3);
    macro(i + 4); macro(i + 5); macro(i + 6); macro(i + 7);
    macro(i + 8); macro(i + 9); macro(i +10); macro(i +11);
    macro(i +12); macro(i +13); macro(i +14); macro(i +15);
  }

  for (; i < len; i ++) {
    macro(i);
  }
}

SDL_Surface *SDL_ConvertSurface(SDL_Surface *src, SDL_PixelFormat *fmt, uint32_t flags) {
  assert(src->format->BitsPerPixel == 32);
  assert(src->w * src->format->BytesPerPixel == src->pitch);
  assert(src->format->BitsPerPixel == fmt->BitsPerPixel);

  SDL_Surface* ret = SDL_CreateRGBSurface(flags, src->w, src->h, fmt->BitsPerPixel,
    fmt->Rmask, fmt->Gmask, fmt->Bmask, fmt->Amask);

  assert(fmt->Gmask == src->format->Gmask);
  assert(fmt->Amask == 0 || src->format->Amask == 0 || (fmt->Amask == src->format->Amask));
  ConvertPixelsARGB_ABGR(ret->pixels, src->pixels, src->w * src->h);

  return ret;
}

uint32_t SDL_MapRGBA(SDL_PixelFormat *fmt, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
  assert(fmt->BytesPerPixel == 4);
  uint32_t p = (r << fmt->Rshift) | (g << fmt->Gshift) | (b << fmt->Bshift);
  if (fmt->Amask) p |= (a << fmt->Ashift);
  return p;
}

int SDL_LockSurface(SDL_Surface *s) {
  return 0;
}

void SDL_UnlockSurface(SDL_Surface *s) {
}
