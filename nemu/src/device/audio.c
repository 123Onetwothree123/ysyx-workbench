/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <common.h>
#include <device/map.h>
#include <SDL2/SDL.h>

enum
{
  reg_freq,
  reg_channels,
  reg_samples,
  reg_sbuf_size,
  reg_init,
  reg_count,
  nr_reg
};

// 把硬件的设备状态设置成上电的时候的初始值的这里先开始跑的
static void AudioReset(void);
// 把客户程序写进声卡寄存器里的参数真正交给宿主机的这个SDL，然后让这块虚拟声卡开始准备工作，说白了就是启动
// 声卡的，理论上来说是开头启动的时候会工作一次
static void AudioDoInit(void);
// 抄native的
static void audio_play(void *userdata, uint8_t *stream, int len);
static void audio_sbuf_io_handler(uint32_t offset, int len, bool is_write);
static uint32_t rpos = 0;  // 当前应该从sbuf的哪个位置开始读，也就是声卡的这个类似于指针，所以是读指针，SDL回调读数据时更新
static uint32_t count = 0; // 就是看目前sbuf里面还有多少字节是已经写好了但是还没有播放的
static bool AudioOpened = false;

static uint8_t *sbuf = NULL;
static uint32_t *audio_base = NULL;

static void audio_io_handler(uint32_t offset, int len, bool is_write)
{
  // 这块控制寄存器区里的每个寄存器都是4字节
  assert(len == 4);
  assert(offset % sizeof(uint32_t) == 0);
  // 因为offset是字节偏移，所以要除4，才能知道是哪个寄存器，刚刚疯狂断才知道这offset不是第几个寄存器，而是离控制的寄存器的区域起点
  // 距离又多少个字节，然后每个寄存器都是4个字节，所以要除法
  uint32_t reg = offset / sizeof(uint32_t);
  assert(reg < nr_reg); // 搞了半天才知道nr是number的意思，是寄存器数量，最开始还他妈的以为是序号
  if (is_write)
  {
    switch (reg)
    {
    case reg_init:
    {
      if (audio_base[reg_init] != 0)
      {
        AudioDoInit();
        // 用完以后清零，别下次触发了
        audio_base[reg_init] = 0;
      }
      break;
    }
    default:
      break;
    }
  }
  else
  {
    switch (reg)
    {
    case reg_sbuf_size:
    {
      audio_base[reg_sbuf_size] = CONFIG_SB_SIZE;
      break;
    }
    case reg_count:
    {
      audio_base[reg_count] = count;
    }
    default:
      break;
    }
  }
}

void init_audio()
{
  uint32_t space_size = sizeof(uint32_t) * nr_reg;
  audio_base = (uint32_t *)new_space(space_size);
#ifdef CONFIG_HAS_PORT_IO
  add_pio_map("audio", CONFIG_AUDIO_CTL_PORT, audio_base, space_size, audio_io_handler);
#else
  add_mmio_map("audio", CONFIG_AUDIO_CTL_MMIO, audio_base, space_size, audio_io_handler);
#endif

  sbuf = (uint8_t *)new_space(CONFIG_SB_SIZE);
  // add_mmio_map("audio-sbuf", CONFIG_SB_ADDR, sbuf, CONFIG_SB_SIZE, NULL);
  add_mmio_map("audio-sbuf", CONFIG_SB_ADDR, sbuf, CONFIG_SB_SIZE, audio_sbuf_io_handler);
  // 自己写的
  AudioReset();
}

static void AudioReset(void)
{
  audio_base[reg_freq] = 0;
  audio_base[reg_channels] = 0;
  audio_base[reg_samples] = 0;
  audio_base[reg_sbuf_size] = CONFIG_SB_SIZE; // 这是音频缓冲区STREAM_BUF
  audio_base[reg_init] = 0;
  audio_base[reg_count] = 0;
  rpos = 0; // 以防万一，手动再赋值一次0，不过声明的时候就是0了，估计实际上也不太需要了
  count = 0;
  if (sbuf != NULL)
  {
    memset(sbuf, 0, CONFIG_SB_SIZE);
  }
}
static void AudioDoInit(void)
{
  SDL_AudioSpec s = {};
  s.freq = audio_base[reg_freq];
  s.format = AUDIO_S16SYS;
  s.channels = audio_base[reg_channels];
  s.samples = audio_base[reg_samples];
  s.callback = audio_play;
  s.userdata = NULL;
  // 如果之前开过音频，就先关掉
  if (AudioOpened)
  {
    SDL_CloseAudio();
    AudioOpened = false;
  }
  // 先复位，把设备的播放的状态都清空
  count = 0;
  rpos = 0;
  audio_base[reg_count] = 0;
  audio_base[reg_sbuf_size] = CONFIG_SB_SIZE;
  memset(sbuf, 0, CONFIG_SB_SIZE);
  // 抄native的，直接初始化启动
  int ret = SDL_InitSubSystem(SDL_INIT_AUDIO);
  if (ret == 0)
  {
    SDL_OpenAudio(&s, NULL);
    SDL_PauseAudio(0);
    AudioOpened = true;
  }
}
static void audio_play(void *userdata, uint8_t *stream, int len)
{
  int nread = len;
  if (count < len)
  {
    nread = count;
  }
  if (nread > 0)
  {
    size_t first = CONFIG_SB_SIZE - rpos;
    if (first > (size_t)nread)
    {
      first = nread;
    }
    // 标记一下，标记一下，实在是记不住，sbuf+rpos指的是从缓冲区sbuf的第rpos个字节开始
    memcpy(stream, sbuf + rpos, first);
    // 如果第一段不够，就是要回卷了，因为这里sbuf没有加上rpos，就是因为要从头开始
    // 真的是一点都记不住，就是nread是这次要读的数量，然后first是从rpos到数组末尾最多可以读多少，所以读的比从起点到目标更
    // 多所以就只能回卷
    if ((size_t)nread > first)
    {
      memcpy(stream + first, sbuf, nread - first);
    }
    // 数学思路：就是假设目前是从rpos开始读取，然后这次读了nread，所以位置就是rpos+nread，因为要考虑到
    // 缓冲区绕回开头的问题，所以就是取模
    rpos = (rpos + nread) % CONFIG_SB_SIZE;
  }
  count -= nread;
  if (len > nread)
  {
    memset(stream + nread, 0, len - nread);
  }
}
static void audio_sbuf_io_handler(uint32_t offset, int len, bool is_write)
{
  if (is_write)
  {
    count += len;                    // 因为这次向STREAM_BUF中写入了len个字节，所以已用大小增加len
    assert(count <= CONFIG_SB_SIZE); // 因为已用大小不能超过目前整个流缓冲区的容量
    audio_base[reg_count] = count;
  }
}