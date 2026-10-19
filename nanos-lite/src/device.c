#include <common.h>

#if defined(MULTIPROGRAM) && !defined(TIME_SHARING)
# define MULTIPROGRAM_YIELD() yield()
#else
# define MULTIPROGRAM_YIELD()
#endif

#define NAME(key) \
  [AM_KEY_##key] = #key,

static const char *keyname[256] __attribute__((used)) = {
  [AM_KEY_NONE] = "NONE",
  AM_KEYS(NAME)
};

static int screen_w = 0, screen_h = 0;

size_t serial_write(const void *buf, size_t offset, size_t len) {
  (void)offset;
  const char *p = buf;
  for (size_t i = 0; i < len; i++) {
    putch(p[i]);
  }
  return len;
}

size_t events_read(void *buf, size_t offset, size_t len) {
  (void)offset;

  AM_INPUT_KEYBRD_T ev = {0};
  ioe_read(AM_INPUT_KEYBRD, &ev);
  if (ev.keycode == AM_KEY_NONE) {
    return 0;
  }
  if (ev.keycode < 0 || ev.keycode >= 256 || keyname[ev.keycode] == NULL) {
    return 0;
  }

  int n = snprintf(buf, len, "%s %s\n",
                   ev.keydown ? "kd" : "ku", keyname[ev.keycode]);
  return n < 0 ? 0 : (size_t)n;
}

size_t dispinfo_read(void *buf, size_t offset, size_t len) {
  (void)offset;
  int n = snprintf(buf, len, "WIDTH : %d\nHEIGHT: %d\n", screen_w, screen_h);
  return n < 0 ? 0 : (size_t)n;
}

size_t fb_write(const void *buf, size_t offset, size_t len) {
  if (screen_w <= 0 || screen_h <= 0 || len == 0) {
    return 0;
  }

  size_t pixels = len / 4;
  int x = (offset / 4) % screen_w;
  int y = (offset / 4) / screen_w;
  if (pixels > (size_t)screen_w || y >= screen_h) {
    return 0;
  }

  AM_GPU_FBDRAW_T ctl = {
    .x = x,
    .y = y,
    .pixels = (void *)buf,
    .w = (int)pixels,
    .h = 1,
    .sync = true,
  };
  ioe_write(AM_GPU_FBDRAW, &ctl);
  return len;
}

size_t sbctl_read(void *buf, size_t offset, size_t len) {
  (void)offset;
  if (len < sizeof(int)) {
    return 0;
  }

  AM_AUDIO_CONFIG_T cfg;
  AM_AUDIO_STATUS_T status;
  ioe_read(AM_AUDIO_CONFIG, &cfg);
  ioe_read(AM_AUDIO_STATUS, &status);

  int free_bytes = cfg.bufsize - status.count;
  if (free_bytes < 0) {
    free_bytes = 0;
  }
  *(int *)buf = free_bytes;
  return sizeof(int);
}

size_t sbctl_write(const void *buf, size_t offset, size_t len) {
  (void)offset;
  if (len < 3 * sizeof(int)) {
    return 0;
  }

  const int *p = buf;
  AM_AUDIO_CTRL_T ctrl = {
    .freq = p[0],
    .channels = p[1],
    .samples = p[2],
  };
  ioe_write(AM_AUDIO_CTRL, &ctrl);
  return len;
}

size_t sb_write(const void *buf, size_t offset, size_t len) {
  (void)offset;
  AM_AUDIO_PLAY_T play = {
    .buf = {
      .start = (void *)buf,
      .end = (void *)((const char *)buf + len),
    },
  };
  ioe_write(AM_AUDIO_PLAY, &play);
  return len;
}

void init_device() {
  Log("Initializing devices...");
  ioe_init();

  AM_GPU_CONFIG_T gpu = {0};
  ioe_read(AM_GPU_CONFIG, &gpu);
  screen_w = gpu.width;
  screen_h = gpu.height;
  Log("screen: %d x %d", screen_w, screen_h);
}
