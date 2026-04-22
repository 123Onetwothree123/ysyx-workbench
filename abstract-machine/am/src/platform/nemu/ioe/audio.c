#include <am.h>
#include <nemu.h>
#include <klib.h>
#include <stdint.h>

#define AUDIO_FREQ_ADDR (AUDIO_ADDR + 0x00)
#define AUDIO_CHANNELS_ADDR (AUDIO_ADDR + 0x04)
#define AUDIO_SAMPLES_ADDR (AUDIO_ADDR + 0x08)
#define AUDIO_SBUF_SIZE_ADDR (AUDIO_ADDR + 0x0c)
#define AUDIO_INIT_ADDR (AUDIO_ADDR + 0x10)
#define AUDIO_COUNT_ADDR (AUDIO_ADDR + 0x14)

// 自己写的，模仿了native
static void audio_write(uint8_t *buf, int len);

// 自己写的了
static uint32_t wpos = 0; // 这是写指针，表示下一次应该把数据写道流缓冲区的哪个位置

void __am_audio_init()
{
  /*
  int fds[2];
  int ret
assert(ret==0);
  rfd = fds[0];
  wfd = fds[1];
  */
  // 好像因为在NEMU中实现了，就不需要实现逻辑了，因为这是native用来造宿主机管道缓冲区的办法
  // NEMU平台根本不该用pipe，因为已经有AUDIO_SBUF_ADDR充当硬件设备的缓冲区了
}

void __am_audio_config(AM_AUDIO_CONFIG_T *cfg)
{
  // cfg->present = false;
  cfg->present = true;
  cfg->bufsize = inl(AUDIO_SBUF_SIZE_ADDR);
}

void __am_audio_ctrl(AM_AUDIO_CTRL_T *ctrl)
{
  wpos = 0;
  outl(AUDIO_FREQ_ADDR, ctrl->freq);
  outl(AUDIO_CHANNELS_ADDR, ctrl->channels);
  outl(AUDIO_SAMPLES_ADDR, ctrl->samples);
  outl(AUDIO_INIT_ADDR, 1);
}

void __am_audio_status(AM_AUDIO_STATUS_T *stat)
{
  // stat->count = 0;
  stat->count = inl(AUDIO_COUNT_ADDR);
}

void __am_audio_play(AM_AUDIO_PLAY_T *ctl)
{
  // 直接抄native
  int len = ctl->buf.end - ctl->buf.start;
  audio_write(ctl->buf.start, len);
}

static void audio_write(uint8_t *buf, int len)
{
  uint32_t bufsize = inl(AUDIO_SBUF_SIZE_ADDR);
  volatile uint8_t *sbuf = (volatile uint8_t *)(uintptr_t)AUDIO_SBUF_ADDR;
  int nwrite = 0; // 仿照native的，看现在从源缓冲区中写了多少字节
  while (nwrite < len)
  {
    int need = len - nwrite;
    int count = inl(AUDIO_COUNT_ADDR);
    int free = bufsize - count;

    // 如果剩下的这部分空间不够这次要写的全部数据的话，就一直等
    if (free < need)
    {
      continue;
    }
    // 这一轮准备写入的数据量
    int n = need;
    // 最开始以为可以wpos然后取到末尾点然后直接记录距离长度
    // 然后发现行不通，所以还是直接用总长度减掉目前的位置，就
    // 是距离末尾的长度
    uint32_t first = bufsize - wpos;
    if (first > (uint32_t)n)
    {
      first = n;
    }
    for (uint32_t i = 0; i < first; i++)
    {
      sbuf[wpos + i] = buf[nwrite + i];
    }
    // 和前面一样，第一部分没跑到终点了数字还没跑完，就是到回到开头部分了，然后继续跑
    for (int i = 0; i < n - (int)first; i++)
    {
      sbuf[i] = buf[nwrite + first + i];
    }
    wpos = (wpos + n) % bufsize;
    // 再次申明要写，之前就是漏写了，就是因为这里是nwrite是记录：看现在从源缓冲区中写了多少字节
    // 所以现在还要再加上这次写出了多少字节
    nwrite += n;
  }
}
