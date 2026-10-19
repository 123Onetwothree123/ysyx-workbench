#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/time.h>
#include "NDL.h"

static int evtdev = -1;
static int fbdev = -1;
static int sbctldev = -1;
static int sbdev = -1;
static int screen_w = 0, screen_h = 0;
static int canvas_w = 0, canvas_h = 0;
static int canvas_x = 0, canvas_y = 0;

uint32_t NDL_GetTicks() {
  struct timeval tv = {0};
  gettimeofday(&tv, NULL);
  return (uint32_t)(tv.tv_sec * 1000 + tv.tv_usec / 1000);
}

int NDL_PollEvent(char *buf, int len) {
  if (evtdev < 0) {
    return 0;
  }
  int n = read(evtdev, buf, len);
  return n > 0 ? 1 : 0;
}

void NDL_OpenCanvas(int *w, int *h) {
  if (*w == 0 || *h == 0) {
    *w = screen_w;
    *h = screen_h;
  }
  if (*w > screen_w) *w = screen_w;
  if (*h > screen_h) *h = screen_h;

  canvas_w = *w;
  canvas_h = *h;
  canvas_x = (screen_w - canvas_w) / 2;
  canvas_y = (screen_h - canvas_h) / 2;
}

void NDL_DrawRect(uint32_t *pixels, int x, int y, int w, int h) {
  if (fbdev < 0 || w <= 0 || h <= 0) {
    return;
  }
  for (int i = 0; i < h; i++) {
    off_t offset = ((off_t)(canvas_y + y + i) * screen_w + canvas_x + x) * 4;
    if (lseek(fbdev, offset, SEEK_SET) < 0) {
      return;
    }
    write(fbdev, pixels + (size_t)i * w, (size_t)w * 4);
  }
}

void NDL_OpenAudio(int freq, int channels, int samples) {
  if (sbctldev < 0) {
    sbctldev = open("/dev/sbctl", 0, 0);
  }
  if (sbdev < 0) {
    sbdev = open("/dev/sb", 0, 0);
  }
  if (sbctldev < 0) {
    return;
  }

  int ctrl[3] = {freq, channels, samples};
  write(sbctldev, ctrl, sizeof(ctrl));
}

void NDL_CloseAudio() {
  if (sbctldev >= 0) {
    close(sbctldev);
    sbctldev = -1;
  }
  if (sbdev >= 0) {
    close(sbdev);
    sbdev = -1;
  }
}

int NDL_PlayAudio(void *buf, int len) {
  if (sbdev < 0) {
    return 0;
  }
  int n = write(sbdev, buf, len);
  return n > 0 ? n : 0;
}

int NDL_QueryAudio() {
  if (sbctldev < 0) {
    return 0;
  }
  int free_bytes = 0;
  int n = read(sbctldev, &free_bytes, sizeof(free_bytes));
  return n == (int)sizeof(free_bytes) ? free_bytes : 0;
}

int NDL_Init(uint32_t flags) {
  (void)flags;

  evtdev = open("/dev/events", 0, 0);
  fbdev = open("/dev/fb", 0, 0);

  int disp = open("/proc/dispinfo", 0, 0);
  if (disp >= 0) {
    char buf[64] = {0};
    int n = read(disp, buf, sizeof(buf) - 1);
    if (n > 0) {
      sscanf(buf, "WIDTH : %d\nHEIGHT: %d", &screen_w, &screen_h);
    }
    close(disp);
  }

  return 0;
}

void NDL_Quit() {
  NDL_CloseAudio();
  if (evtdev >= 0) close(evtdev);
  if (fbdev >= 0) close(fbdev);
}
