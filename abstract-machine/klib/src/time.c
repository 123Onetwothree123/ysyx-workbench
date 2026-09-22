#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <time.h>
#include <errno.h>
#include <limits.h>
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

static int leap_year(int64_t year)
{
  return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

static int month_length(int64_t year, int month)
{
  static const unsigned char lengths[12] = {
      31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  return lengths[month - 1] + (month == 2 && leap_year(year));
}

/* Howard Hinnant's civil-calendar transform, with 1970-01-01 as day zero. */
static int64_t days_from_civil(int64_t year, unsigned month, unsigned day)
{
  year -= month <= 2;
  const int64_t era = (year >= 0 ? year : year - 399) / 400;
  const unsigned year_of_era = (unsigned)(year - era * 400);
  const unsigned day_of_year =
      (153u * (month + (month > 2 ? (unsigned)-3 : 9u)) + 2u) / 5u +
      day - 1u;
  const unsigned day_of_era = year_of_era * 365u + year_of_era / 4u -
                              year_of_era / 100u + day_of_year;
  return era * 146097 + (int64_t)day_of_era - 719468;
}

static int civil_from_days(
    int64_t days,
    int64_t *year,
    unsigned *month,
    unsigned *day)
{
  days += 719468;
  const int64_t era = (days >= 0 ? days : days - 146096) / 146097;
  const unsigned day_of_era = (unsigned)(days - era * 146097);
  const unsigned year_of_era =
      (day_of_era - day_of_era / 1460u + day_of_era / 36524u -
       day_of_era / 146096u) /
      365u;
  int64_t y = (int64_t)year_of_era + era * 400;
  const unsigned day_of_year =
      day_of_era - (365u * year_of_era + year_of_era / 4u -
                    year_of_era / 100u);
  const unsigned month_prime = (5u * day_of_year + 2u) / 153u;
  const unsigned d = day_of_year - (153u * month_prime + 2u) / 5u + 1u;
  const unsigned m = month_prime + (month_prime < 10 ? 3u : (unsigned)-9);
  y += m <= 2;
  *year = y;
  *month = m;
  *day = d;
  return 1;
}

static int read_realtime(time_t *result)
{
#if defined(__ISA_NATIVE__)
  struct timespec now;
  if (clock_gettime(CLOCK_REALTIME, &now) != 0)
  {
    return 0;
  }
  *result = now.tv_sec;
  return 1;
#else
  AM_TIMER_CONFIG_T config = io_read(AM_TIMER_CONFIG);
  if (!config.present || !config.has_rtc)
  {
    return 0;
  }
  AM_TIMER_RTC_T rtc = io_read(AM_TIMER_RTC);
  if (rtc.year < 1970 || rtc.year > 9999 || rtc.month < 1 ||
      rtc.month > 12 || rtc.day < 1 ||
      rtc.day > month_length(rtc.year, rtc.month) || rtc.hour < 0 ||
      rtc.hour > 23 || rtc.minute < 0 || rtc.minute > 59 ||
      rtc.second < 0 || rtc.second > 59)
  {
    return 0;
  }
  int64_t seconds = days_from_civil(
                        rtc.year, (unsigned)rtc.month, (unsigned)rtc.day) *
                    INT64_C(86400) +
                    (int64_t)rtc.hour * 3600 + (int64_t)rtc.minute * 60 +
                    rtc.second;
  *result = (time_t)seconds;
  return (int64_t)*result == seconds;
#endif
}

static double unsigned_to_double(uint64_t magnitude, int negative)
{
  union
  {
    uint64_t bits;
    double value;
  } result = {0};
  if (magnitude == 0)
  {
    return result.value;
  }

  unsigned top = 63;
  while (((magnitude >> top) & 1u) == 0)
  {
    top--;
  }
  uint64_t significand;
  if (top <= 52)
  {
    significand = magnitude << (52 - top);
  }
  else
  {
    unsigned shift = top - 52;
    significand = magnitude >> shift;
    uint64_t remainder = magnitude & (((uint64_t)1 << shift) - 1u);
    uint64_t halfway = (uint64_t)1 << (shift - 1);
    if (remainder > halfway ||
        (remainder == halfway && (significand & 1u) != 0))
    {
      significand++;
      if (significand == ((uint64_t)1 << 53))
      {
        significand >>= 1;
        top++;
      }
    }
  }
  result.bits = ((uint64_t)(negative != 0) << 63) |
                ((uint64_t)(top + 1023) << 52) |
                (significand & ((((uint64_t)1) << 52) - 1u));
  return result.value;
}

clock_t clock(void)
{
  return (clock_t)(io_read(AM_TIMER_UPTIME).us / 1000);
}
time_t time(time_t *t)
{
  time_t value;
  if (!read_realtime(&value))
  {
    if (t != NULL) *t = (time_t)-1;
    return (time_t)-1;
  }
  if (t != NULL) *t = value;
  return value;
}

struct tm *gmtime_r(const time_t *timer, struct tm *result)
{
  if (timer == NULL || result == NULL)
  {
    errno = EINVAL;
    return NULL;
  }
  int64_t seconds = (int64_t)*timer;
  int64_t days = seconds / 86400;
  int64_t remainder = seconds % 86400;
  if (remainder < 0)
  {
    remainder += 86400;
    days--;
  }

  int64_t year;
  unsigned month;
  unsigned day;
  civil_from_days(days, &year, &month, &day);
  if (year < (int64_t)INT_MIN + 1900 || year > (int64_t)INT_MAX + 1900)
  {
    errno = EOVERFLOW;
    return NULL;
  }

  result->tm_hour = (int)(remainder / 3600);
  remainder %= 3600;
  result->tm_min = (int)(remainder / 60);
  result->tm_sec = (int)(remainder % 60);
  result->tm_mday = (int)day;
  result->tm_mon = (int)month - 1;
  result->tm_year = (int)(year - 1900);
  int weekday = (int)((days + 4) % 7);
  if (weekday < 0) weekday += 7;
  result->tm_wday = weekday;
  result->tm_yday = (int)(days - days_from_civil(year, 1, 1));
  result->tm_isdst = 0;
  return result;
}

struct tm *gmtime(const time_t *timer)
{
  static struct tm result;
  return gmtime_r(timer, &result);
}

struct tm *localtime_r(const time_t *timer, struct tm *result)
{
  /* Freestanding KLIB has no timezone database; its local zone is UTC. */
  return gmtime_r(timer, result);
}

struct tm *localtime(const time_t *timer)
{
  static struct tm result;
  return localtime_r(timer, &result);
}

time_t mktime(struct tm *value)
{
  if (value == NULL)
  {
    errno = EINVAL;
    return (time_t)-1;
  }
  int64_t year = (int64_t)value->tm_year + 1900;
  int64_t month = value->tm_mon;
  int64_t year_delta = month / 12;
  month %= 12;
  if (month < 0)
  {
    month += 12;
    year_delta--;
  }
  year += year_delta;

  int64_t days = days_from_civil(year, (unsigned)month + 1u, 1) +
                 (int64_t)value->tm_mday - 1;
  int64_t seconds = days * INT64_C(86400) +
                    (int64_t)value->tm_hour * 3600 +
                    (int64_t)value->tm_min * 60 + value->tm_sec;
  time_t result = (time_t)seconds;
  if ((int64_t)result != seconds || gmtime_r(&result, value) == NULL)
  {
    errno = EOVERFLOW;
    return (time_t)-1;
  }
  return result;
}

char *asctime_r(const struct tm *value, char *buffer)
{
  static const char *const weekdays[] = {
      "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  static const char *const months[] = {
      "Jan", "Feb", "Mar", "Apr", "May", "Jun",
      "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  if (value == NULL || buffer == NULL || value->tm_wday < 0 ||
      value->tm_wday > 6 || value->tm_mon < 0 || value->tm_mon > 11)
  {
    errno = EINVAL;
    return NULL;
  }
  int64_t year = (int64_t)value->tm_year + 1900;
  int length = snprintf(
      buffer, 26, "%.3s %.3s %2d %02d:%02d:%02d %04lld\n",
      weekdays[value->tm_wday], months[value->tm_mon], value->tm_mday,
      value->tm_hour, value->tm_min, value->tm_sec, (long long)year);
  if (length != 25)
  {
    errno = EOVERFLOW;
    return NULL;
  }
  return buffer;
}

char *asctime(const struct tm *value)
{
  static char buffer[26];
  return asctime_r(value, buffer);
}

char *ctime_r(const time_t *timer, char *buffer)
{
  struct tm value;
  return localtime_r(timer, &value) == NULL ? NULL : asctime_r(&value, buffer);
}

char *ctime(const time_t *timer)
{
  static char buffer[26];
  return ctime_r(timer, buffer);
}

typedef struct
{
  char *buffer;
  size_t capacity;
  size_t length;
  int failed;
} TimeText;

static void time_text_append(TimeText *output, const char *text, size_t length)
{
  if (output->failed) return;
  if (length >= output->capacity || output->length > output->capacity - length - 1)
  {
    output->failed = 1;
    return;
  }
  memcpy(output->buffer + output->length, text, length);
  output->length += length;
}

static void time_text_string(TimeText *output, const char *text)
{
  time_text_append(output, text, strlen(text));
}

static void time_text_number(
    TimeText *output,
    int64_t value,
    int width,
    int space_pad)
{
  char number[32];
  int length;
  if (width == 2)
  {
    length = snprintf(number, sizeof(number),
                      space_pad ? "%2lld" : "%02lld",
                      (long long)value);
  }
  else if (width == 3)
  {
    length = snprintf(number, sizeof(number), "%03lld", (long long)value);
  }
  else if (width == 4)
  {
    length = snprintf(number, sizeof(number), "%04lld", (long long)value);
  }
  else
  {
    length = snprintf(number, sizeof(number), "%lld", (long long)value);
  }
  if (length < 0 || (size_t)length >= sizeof(number))
  {
    output->failed = 1;
    return;
  }
  time_text_append(output, number, (size_t)length);
}

static int iso_week_fields(
    const struct tm *value,
    int *iso_year,
    int *iso_week)
{
  int64_t year = (int64_t)value->tm_year + 1900;
  if (value->tm_mon < 0 || value->tm_mon > 11 || value->tm_mday < 1 ||
      value->tm_mday > month_length(year, value->tm_mon + 1))
  {
    return 0;
  }
  int64_t days = days_from_civil(
      year, (unsigned)value->tm_mon + 1u, (unsigned)value->tm_mday);
  int weekday = (int)((days + 4) % 7);
  if (weekday < 0) weekday += 7;
  int iso_weekday = weekday == 0 ? 7 : weekday;
  int64_t thursday = days + (4 - iso_weekday);
  int64_t thursday_year;
  unsigned ignored_month;
  unsigned ignored_day;
  civil_from_days(
      thursday, &thursday_year, &ignored_month, &ignored_day);
  if (thursday_year < INT_MIN || thursday_year > INT_MAX)
  {
    return 0;
  }
  int64_t january_four = days_from_civil(thursday_year, 1, 4);
  int january_four_weekday = (int)((january_four + 4) % 7);
  if (january_four_weekday < 0) january_four_weekday += 7;
  int january_four_iso = january_four_weekday == 0 ? 7
                                                   : january_four_weekday;
  int64_t first_monday = january_four - (january_four_iso - 1);
  *iso_year = (int)thursday_year;
  *iso_week = (int)((days - first_monday) / 7 + 1);
  return 1;
}

size_t strftime(
    char *s,
    size_t maxsize,
    const char *format,
    const struct tm *value)
{
  static const char *const short_weekdays[] = {
      "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  static const char *const long_weekdays[] = {
      "Sunday", "Monday", "Tuesday", "Wednesday",
      "Thursday", "Friday", "Saturday"};
  static const char *const short_months[] = {
      "Jan", "Feb", "Mar", "Apr", "May", "Jun",
      "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  static const char *const long_months[] = {
      "January", "February", "March", "April", "May", "June",
      "July", "August", "September", "October", "November", "December"};

#if !defined(__ISA_NATIVE__)
  if ((s == NULL && maxsize != 0) || format == NULL || value == NULL)
  {
    errno = EINVAL;
    return 0;
  }
#endif
  if (value->tm_wday < 0 || value->tm_wday > 6 || value->tm_mon < 0 ||
      value->tm_mon > 11)
  {
    errno = EINVAL;
    return 0;
  }
  if (maxsize == 0) return 0;

  TimeText output = {.buffer = s, .capacity = maxsize};
  while (*format != '\0' && !output.failed)
  {
    if (*format != '%')
    {
      time_text_append(&output, format++, 1);
      continue;
    }
    format++;
    if (*format == 'E' || *format == 'O') format++;
    char conversion = *format;
    if (conversion == '\0')
    {
      output.failed = 1;
      break;
    }
    format++;

    int64_t year = (int64_t)value->tm_year + 1900;
    int hour12 = value->tm_hour % 12;
    if (hour12 <= 0) hour12 += 12;
    switch (conversion)
    {
    case '%': time_text_string(&output, "%"); break;
    case 'a': time_text_string(&output, short_weekdays[value->tm_wday]); break;
    case 'A': time_text_string(&output, long_weekdays[value->tm_wday]); break;
    case 'b':
    case 'h': time_text_string(&output, short_months[value->tm_mon]); break;
    case 'B': time_text_string(&output, long_months[value->tm_mon]); break;
    case 'c':
      time_text_string(&output, short_weekdays[value->tm_wday]);
      time_text_string(&output, " ");
      time_text_string(&output, short_months[value->tm_mon]);
      time_text_string(&output, " ");
      time_text_number(&output, value->tm_mday, 2, 1);
      time_text_string(&output, " ");
      time_text_number(&output, value->tm_hour, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_min, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_sec, 2, 0);
      time_text_string(&output, " ");
      time_text_number(&output, year, 4, 0);
      break;
    case 'C': time_text_number(&output, year / 100, 2, 0); break;
    case 'd': time_text_number(&output, value->tm_mday, 2, 0); break;
    case 'e': time_text_number(&output, value->tm_mday, 2, 1); break;
    case 'D':
    case 'x':
      time_text_number(&output, value->tm_mon + 1, 2, 0);
      time_text_string(&output, "/");
      time_text_number(&output, value->tm_mday, 2, 0);
      time_text_string(&output, "/");
      time_text_number(&output, year % 100, 2, 0);
      break;
    case 'F':
      time_text_number(&output, year, 4, 0);
      time_text_string(&output, "-");
      time_text_number(&output, value->tm_mon + 1, 2, 0);
      time_text_string(&output, "-");
      time_text_number(&output, value->tm_mday, 2, 0);
      break;
    case 'g':
    case 'G':
    case 'V':
    {
      int iso_year;
      int iso_week;
      if (!iso_week_fields(value, &iso_year, &iso_week))
      {
        output.failed = 1;
      }
      else if (conversion == 'V')
      {
        time_text_number(&output, iso_week, 2, 0);
      }
      else if (conversion == 'g')
      {
        time_text_number(&output, iso_year % 100, 2, 0);
      }
      else
      {
        time_text_number(&output, iso_year, 4, 0);
      }
      break;
    }
    case 'H': time_text_number(&output, value->tm_hour, 2, 0); break;
    case 'I': time_text_number(&output, hour12, 2, 0); break;
    case 'j': time_text_number(&output, value->tm_yday + 1, 3, 0); break;
    case 'm': time_text_number(&output, value->tm_mon + 1, 2, 0); break;
    case 'M': time_text_number(&output, value->tm_min, 2, 0); break;
    case 'n': time_text_string(&output, "\n"); break;
    case 'p': time_text_string(&output, value->tm_hour < 12 ? "AM" : "PM"); break;
    case 'r':
      time_text_number(&output, hour12, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_min, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_sec, 2, 0);
      time_text_string(&output, value->tm_hour < 12 ? " AM" : " PM");
      break;
    case 'R':
      time_text_number(&output, value->tm_hour, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_min, 2, 0);
      break;
    case 'S': time_text_number(&output, value->tm_sec, 2, 0); break;
    case 't': time_text_string(&output, "\t"); break;
    case 'T':
    case 'X':
      time_text_number(&output, value->tm_hour, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_min, 2, 0);
      time_text_string(&output, ":");
      time_text_number(&output, value->tm_sec, 2, 0);
      break;
    case 'u': time_text_number(&output, value->tm_wday == 0 ? 7 : value->tm_wday, 0, 0); break;
    case 'U': time_text_number(&output, (value->tm_yday + 7 - value->tm_wday) / 7, 2, 0); break;
    case 'w': time_text_number(&output, value->tm_wday, 0, 0); break;
    case 'W':
      time_text_number(
          &output,
          (value->tm_yday + 7 - ((value->tm_wday + 6) % 7)) / 7,
          2,
          0);
      break;
    case 'y': time_text_number(&output, year % 100, 2, 0); break;
    case 'Y': time_text_number(&output, year, 4, 0); break;
    case 'z': time_text_string(&output, "+0000"); break;
    case 'Z': time_text_string(&output, "UTC"); break;
    default:
      output.failed = 1;
      break;
    }
  }

  if (output.failed)
  {
    s[0] = '\0';
    return 0;
  }
  s[output.length] = '\0';
  return output.length;
}

double difftime(time_t end, time_t begin)
{
  int negative = end < begin;
  uint64_t unsigned_end = (uint64_t)(uintmax_t)end;
  uint64_t unsigned_begin = (uint64_t)(uintmax_t)begin;
  uint64_t magnitude = negative ? unsigned_begin - unsigned_end
                                : unsigned_end - unsigned_begin;
  return unsigned_to_double(magnitude, negative);
}

int timespec_get(struct timespec *ts, int base)
{
  if (base != TIME_UTC)
  {
    return 0;
  }
#if !defined(__ISA_NATIVE__)
  if (ts == NULL)
  {
    return 0;
  }
#endif
#if defined(__ISA_NATIVE__)
  if (clock_gettime(CLOCK_REALTIME, ts) != 0)
  {
    return 0;
  }
#else
  if (!read_realtime(&ts->tv_sec))
  {
    return 0;
  }
  ts->tv_nsec = 0;
#endif
  return base;
}
#endif
