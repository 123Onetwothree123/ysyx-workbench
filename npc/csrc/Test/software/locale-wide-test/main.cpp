#include <am.h>
#include <errno.h>
#include <klib.h>
#include <limits.h>
#include <locale.h>
#include <stdint.h>
#include <time.h>
#include <wchar.h>
#include <wctype.h>
#include <stdlib.h>

int locale_wide_include_order_probe(void);

static int failures;

#define CHECK(condition)                                                     \
  do {                                                                       \
    if (!(condition)) {                                                      \
      failures++;                                                            \
      printf("locale-wide check failed at line %d\n", __LINE__);           \
    }                                                                        \
  } while (0)

static void test_locale(void)
{
  CHECK(setlocale(LC_ALL, "C") != NULL);
  CHECK(strcmp(setlocale(LC_ALL, NULL), "C") == 0);
  CHECK(strcmp(setlocale(LC_CTYPE, "POSIX"), "C") == 0);
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  CHECK(strcmp(setlocale(LC_ALL, ""), "C") == 0);
  CHECK(setlocale(LC_ALL + 99, "C") == NULL);
  CHECK(setlocale(LC_ALL, "en_US.UTF-8") == NULL);
#endif

  struct lconv *values = localeconv();
  CHECK(values != NULL);
  CHECK(strcmp(values->decimal_point, ".") == 0);
  CHECK(strcmp(values->thousands_sep, "") == 0);
  CHECK(strcmp(values->grouping, "") == 0);
  CHECK(strcmp(values->int_curr_symbol, "") == 0);
  CHECK(strcmp(values->currency_symbol, "") == 0);
  CHECK(strcmp(values->mon_decimal_point, "") == 0);
  CHECK(strcmp(values->mon_thousands_sep, "") == 0);
  CHECK(strcmp(values->mon_grouping, "") == 0);
  CHECK(strcmp(values->positive_sign, "") == 0);
  CHECK(strcmp(values->negative_sign, "") == 0);
  CHECK(values->int_frac_digits == CHAR_MAX);
  CHECK(values->frac_digits == CHAR_MAX);
  CHECK(values->p_cs_precedes == CHAR_MAX);
  CHECK(values->p_sep_by_space == CHAR_MAX);
  CHECK(values->n_cs_precedes == CHAR_MAX);
  CHECK(values->n_sep_by_space == CHAR_MAX);
  CHECK(values->p_sign_posn == CHAR_MAX);
  CHECK(values->n_sign_posn == CHAR_MAX);
  CHECK(values->int_p_cs_precedes == CHAR_MAX);
  CHECK(values->int_p_sep_by_space == CHAR_MAX);
  CHECK(values->int_n_cs_precedes == CHAR_MAX);
  CHECK(values->int_n_sep_by_space == CHAR_MAX);
  CHECK(values->int_p_sign_posn == CHAR_MAX);
  CHECK(values->int_n_sign_posn == CHAR_MAX);

  CHECK(strcoll("abc", "abc") == 0);
  CHECK(strcoll("abc", "abd") < 0);
  CHECK(strcoll("abd", "abc") > 0);

  char transformed[8];
  memset(transformed, 'X', sizeof(transformed));
  CHECK(strxfrm(transformed, "abc", sizeof(transformed)) == 3);
  CHECK(strcmp(transformed, "abc") == 0);
  CHECK(transformed[4] == 'X');
  CHECK(strxfrm(NULL, "abcdef", 0) == 6);
  char short_transform[3] = {'X', 'X', 'X'};
  CHECK(strxfrm(short_transform, "abcd", sizeof(short_transform)) == 4);
  CHECK(memcmp(short_transform, "abc", sizeof(short_transform)) == 0);
}

static void test_wide_strings(void)
{
  const wchar_t sample[] = {L'a', (wchar_t)0x1234, L'b', L'a', L'\0'};
  CHECK(wcslen(sample) == 4);
  CHECK(wcsnlen(sample, 2) == 2);
  CHECK(wcsnlen(sample, 9) == 4);
  CHECK(wcsnlen(sample, 0) == 0);

  wchar_t buffer[16];
  wmemset(buffer, L'Z', 16);
  CHECK(wcscpy(buffer, L"abc") == buffer);
  CHECK(wcscmp(buffer, L"abc") == 0);
  CHECK(buffer[4] == L'Z');
  CHECK(wcscat(buffer, L"de") == buffer);
  CHECK(wcscmp(buffer, L"abcde") == 0);
  CHECK(wcsncat(buffer, L"fghi", 2) == buffer);
  CHECK(wcscmp(buffer, L"abcdefg") == 0);

  const wchar_t unterminated[] = {L'1', L'2', (wchar_t)0x1234};
  wmemset(buffer, L'Z', 16);
  CHECK(wcsncpy(buffer, unterminated, 3) == buffer);
  CHECK(wmemcmp(buffer, unterminated, 3) == 0);
  CHECK(buffer[3] == L'Z');
  wchar_t no_append[4] = L"xy";
  CHECK(wcsncat(no_append, L"ignored", 0) == no_append);
  CHECK(wcscmp(no_append, L"xy") == 0);

  wmemset(buffer, L'Z', 16);
  CHECK(wcsncpy(buffer, L"xy", 5) == buffer);
  CHECK(buffer[0] == L'x' && buffer[1] == L'y');
  CHECK(buffer[2] == L'\0' && buffer[3] == L'\0' &&
        buffer[4] == L'\0' && buffer[5] == L'Z');

  CHECK(wcscmp(L"abc", L"abd") < 0);
  CHECK(wcscmp(L"abd", L"abc") > 0);
  CHECK(wcsncmp(L"abc", L"abd", 2) == 0);
  CHECK(wcsncmp(L"abc", L"abd", 3) < 0);
  CHECK(wcsncmp(L"a", L"z", 0) == 0);

  CHECK(wcschr(sample, (wchar_t)0x1234) == sample + 1);
  CHECK(wcschr(sample, L'\0') == sample + 4);
  CHECK(wcschr(sample, L'x') == NULL);
  CHECK(wcsrchr(sample, L'a') == sample + 3);
  CHECK(wcsrchr(sample, L'\0') == sample + 4);
  CHECK(wcsrchr(sample, L'x') == NULL);
  const wchar_t *wide_search = L"abcabc";
  CHECK(wcsstr(wide_search, L"bca") == wide_search + 1);
  CHECK(wcsstr(wide_search, L"") == wide_search);
  CHECK(wcsstr(L"abc", L"xyz") == NULL);

  CHECK(wcsspn(L"abc123", L"cba") == 3);
  CHECK(wcsspn(L"abc", L"") == 0);
  CHECK(wcscspn(L"abc123", L"31") == 3);
  CHECK(wcscspn(L"abc", L"") == 3);
  const wchar_t *wide_path = L"kernel/path";
  CHECK(wcspbrk(wide_path, L"/: ") == wide_path + 6);
  CHECK(wcspbrk(L"kernel", L"xyz") == NULL);

  wchar_t tokens[] = L",alpha,,beta:gamma,";
  wchar_t *save = NULL;
  wchar_t *token = wcstok(tokens, L",:", &save);
  CHECK(token != NULL && wcscmp(token, L"alpha") == 0);
  token = wcstok(NULL, L",:", &save);
  CHECK(token != NULL && wcscmp(token, L"beta") == 0);
  token = wcstok(NULL, L",:", &save);
  CHECK(token != NULL && wcscmp(token, L"gamma") == 0);
  CHECK(wcstok(NULL, L",:", &save) == NULL);

  const wchar_t memory_source[] = {
      L'A', L'\0', (wchar_t)0x1234, (wchar_t)0x10ffff};
  wchar_t memory_dst[5];
  wmemset(memory_dst, (wchar_t)0x5555, 5);
  CHECK(wmemcpy(memory_dst, memory_source, 4) == memory_dst);
  CHECK(wmemcmp(memory_dst, memory_source, 4) == 0);
  CHECK(memory_dst[4] == (wchar_t)0x5555);
  CHECK(wmemchr(memory_dst, L'\0', 4) == memory_dst + 1);
  CHECK(wmemchr(memory_dst, (wchar_t)0x1234, 4) == memory_dst + 2);
  CHECK(wmemchr(memory_dst, L'Z', 4) == NULL);

  wchar_t overlap[10] = L"abcdef";
  CHECK(wmemmove(overlap + 2, overlap, 6) == overlap + 2);
  CHECK(wcscmp(overlap, L"ababcdef") == 0);
  wcscpy(overlap, L"abcdef");
  CHECK(wmemmove(overlap, overlap + 2, 5) == overlap);
  CHECK(wcscmp(overlap, L"cdef") == 0);
  CHECK(wmemmove(overlap, overlap, 7) == overlap);

  CHECK(wcscoll(L"abc", L"abc") == 0);
  CHECK(wcscoll(L"abc", L"abd") < 0);
  wmemset(buffer, L'Z', 16);
  CHECK(wcsxfrm(buffer, L"abc", 16) == 3);
  CHECK(wcscmp(buffer, L"abc") == 0);
  CHECK(buffer[4] == L'Z');
  CHECK(wcsxfrm(NULL, L"abcdef", 0) == 6);
  wchar_t short_wide_transform[3] = {L'X', L'X', L'X'};
  CHECK(wcsxfrm(short_wide_transform, L"abcd", 3) == 4);
  CHECK(wmemcmp(short_wide_transform, L"abc", 3) == 0);

  wchar_t *number_end = NULL;
  const wchar_t signed_number[] = L" -0x2aZ";
  CHECK(wcstol(signed_number, &number_end, 0) == -42L);
  CHECK(number_end == signed_number + 6 && *number_end == L'Z');
  const wchar_t wide_signed_number[] = L"-5000000000!";
  CHECK(wcstoll(wide_signed_number, &number_end, 10) == -5000000000LL);
  CHECK(*number_end == L'!');
  const wchar_t unsigned_number[] = L"18446744073709551615?";
  CHECK(wcstoull(unsigned_number, &number_end, 10) == UINT64_MAX);
  CHECK(*number_end == L'?');
  CHECK(wcstoul(L"377", NULL, 8) == 255UL);
}

static void test_multibyte(void)
{
  CHECK(MB_CUR_MAX == 1);
  CHECK(btowc('A') == (wint_t)L'A');
  CHECK(btowc(0) == (wint_t)L'\0');
  CHECK(btowc(EOF) == WEOF);
  CHECK(btowc(0x80) == WEOF);
  CHECK(wctob((wint_t)L'A') == 'A');
  CHECK(wctob((wint_t)L'\0') == 0);
  CHECK(wctob(WEOF) == EOF);
  CHECK(wctob((wint_t)0x80) == EOF);

  mbstate_t state = {0};
  CHECK(sizeof(state) >= sizeof(int) + sizeof(wint_t));
  wchar_t wc = L'X';
  CHECK(mbsinit(NULL));
  CHECK(mbsinit(&state));
  CHECK(mbrtowc(&wc, "A", 1, &state) == 1);
  CHECK(wc == L'A' && mbsinit(&state));
  wc = L'X';
  CHECK(mbrtowc(&wc, "", 1, &state) == 0);
  CHECK(wc == L'\0' && mbsinit(&state));
  wc = L'X';
  CHECK(mbrtowc(&wc, "A", 0, &state) == (size_t)-2);
  CHECK(wc == L'X');
  CHECK(mbrtowc(NULL, NULL, 0, &state) == 0);
  CHECK(mbsinit(&state));

  const char invalid_bytes[] = {(char)0x80, '\0'};
  errno = 0;
  wc = L'X';
  CHECK(mbrtowc(&wc, invalid_bytes, sizeof(invalid_bytes), &state) ==
        (size_t)-1);
  CHECK(errno == EILSEQ && wc == L'X' && mbsinit(&state));
  CHECK(mbrlen("A", 1, &state) == 1);
  CHECK(mbrlen("", 1, &state) == 0);
  CHECK(mbrlen("A", 0, &state) == (size_t)-2);

  char byte_buffer[8] = {'X', 'X', 'X', 'X', 'X', 'X', 'X', 'X'};
  CHECK(wcrtomb(byte_buffer, L'A', &state) == 1);
  CHECK(byte_buffer[0] == 'A' && mbsinit(&state));
  CHECK(wcrtomb(byte_buffer, L'\0', &state) == 1);
  CHECK(byte_buffer[0] == '\0');
  CHECK(wcrtomb(NULL, (wchar_t)0x1234, &state) == 1);
  errno = 0;
  byte_buffer[0] = 'X';
  CHECK(wcrtomb(byte_buffer, (wchar_t)0x80, &state) == (size_t)-1);
  CHECK(errno == EILSEQ && byte_buffer[0] == 'X' && mbsinit(&state));

  const char *narrow = "abc";
  const char *narrow_start = narrow;
  state = {};
  CHECK(mbsrtowcs(NULL, &narrow, 0, &state) == 3);
  CHECK(narrow == narrow_start && mbsinit(&state));

  wchar_t wide_buffer[8];
  wmemset(wide_buffer, L'Z', 8);
  narrow = "abc";
  narrow_start = narrow;
  CHECK(mbsrtowcs(wide_buffer, &narrow, 0, &state) == 0);
  CHECK(narrow == narrow_start && wide_buffer[0] == L'Z');
  CHECK(mbsrtowcs(wide_buffer, &narrow, 2, &state) == 2);
  CHECK(wide_buffer[0] == L'a' && wide_buffer[1] == L'b');
  CHECK(narrow != NULL && *narrow == 'c');
  CHECK(mbsrtowcs(wide_buffer + 2, &narrow, 6, &state) == 1);
  CHECK(narrow == NULL && wcscmp(wide_buffer, L"abc") == 0);

  const char invalid_text[] = {'A', (char)0xff, 'B', '\0'};
  narrow = invalid_text;
  narrow_start = narrow;
  errno = 0;
  CHECK(mbsrtowcs(NULL, &narrow, 0, &state) == (size_t)-1);
  CHECK(errno == EILSEQ && narrow == narrow_start);
  narrow = invalid_text;
  wmemset(wide_buffer, L'Z', 8);
  errno = 0;
  CHECK(mbsrtowcs(wide_buffer, &narrow, 8, &state) == (size_t)-1);
  CHECK(errno == EILSEQ && narrow == invalid_text + 1);
  CHECK(wide_buffer[0] == L'A' && wide_buffer[1] == L'Z');

  const wchar_t *wide = L"abc";
  const wchar_t *wide_start = wide;
  CHECK(wcsrtombs(NULL, &wide, 0, &state) == 3);
  CHECK(wide == wide_start && mbsinit(&state));

  memset(byte_buffer, 'X', sizeof(byte_buffer));
  wide = L"abc";
  wide_start = wide;
  CHECK(wcsrtombs(byte_buffer, &wide, 0, &state) == 0);
  CHECK(wide == wide_start && byte_buffer[0] == 'X');
  CHECK(wcsrtombs(byte_buffer, &wide, 2, &state) == 2);
  CHECK(byte_buffer[0] == 'a' && byte_buffer[1] == 'b');
  CHECK(wide != NULL && *wide == L'c');
  CHECK(wcsrtombs(byte_buffer + 2, &wide, 6, &state) == 1);
  CHECK(wide == NULL && strcmp(byte_buffer, "abc") == 0);

  const wchar_t invalid_wide[] = {L'A', (wchar_t)0x80, L'B', L'\0'};
  wide = invalid_wide;
  wide_start = wide;
  errno = 0;
  CHECK(wcsrtombs(NULL, &wide, 0, &state) == (size_t)-1);
  CHECK(errno == EILSEQ && wide == wide_start);
  wide = invalid_wide;
  memset(byte_buffer, 'X', sizeof(byte_buffer));
  errno = 0;
  CHECK(wcsrtombs(byte_buffer, &wide, sizeof(byte_buffer), &state) ==
        (size_t)-1);
  CHECK(errno == EILSEQ && wide == invalid_wide + 1);
  CHECK(byte_buffer[0] == 'A' && byte_buffer[1] == 'X');

  CHECK(mblen(NULL, 0) == 0);
  CHECK(mblen("A", 1) == 1);
  CHECK(mblen("", 1) == 0);
  errno = 0;
  CHECK(mblen("A", 0) == -1);
  wc = L'X';
  CHECK(mbtowc(&wc, "B", 1) == 1 && wc == L'B');
  CHECK(mbtowc(NULL, NULL, 0) == 0);
  CHECK(wctomb(NULL, L'A') == 0);
  CHECK(wctomb(byte_buffer, L'C') == 1 && byte_buffer[0] == 'C');

  wmemset(wide_buffer, L'Z', 8);
  CHECK(mbstowcs(wide_buffer, "xyz", 8) == 3);
  CHECK(wcscmp(wide_buffer, L"xyz") == 0);
  CHECK(mbstowcs(NULL, "xyz", 0) == 3);
  memset(byte_buffer, 'X', sizeof(byte_buffer));
  CHECK(wcstombs(byte_buffer, L"xyz", sizeof(byte_buffer)) == 3);
  CHECK(strcmp(byte_buffer, "xyz") == 0);
  CHECK(wcstombs(NULL, L"xyz", 0) == 3);
}

static void test_wide_ctype(void)
{
  CHECK(iswalnum(L'A') && iswalnum(L'7') && !iswalnum(L'!'));
  CHECK(iswalpha(L'A') && iswalpha(L'z') && !iswalpha(L'7'));
  CHECK(iswblank(L' ') && iswblank(L'\t') && !iswblank(L'\n'));
  CHECK(iswcntrl(0) && iswcntrl(0x7f) && !iswcntrl(L'A'));
  CHECK(iswdigit(L'0') && iswdigit(L'9') && !iswdigit(L'a'));
  CHECK(iswgraph(L'!') && iswgraph(L'~') && !iswgraph(L' '));
  CHECK(iswlower(L'a') && !iswlower(L'A'));
  CHECK(iswprint(L' ') && iswprint(L'~') && !iswprint(L'\n'));
  CHECK(iswpunct(L'!') && !iswpunct(L'a'));
  CHECK(iswspace(L' ') && iswspace(L'\n') && !iswspace(L'A'));
  CHECK(iswupper(L'A') && !iswupper(L'a'));
  CHECK(iswxdigit(L'0') && iswxdigit(L'f') && iswxdigit(L'F'));
  CHECK(!iswalpha((wint_t)0x80) && !iswprint((wint_t)0x80));
  CHECK(!iswalpha(WEOF) && !iswcntrl(WEOF));
  CHECK(towlower(L'A') == L'a' && towlower(L'!') == L'!');
  CHECK(towupper(L'a') == L'A' && towupper(WEOF) == WEOF);

  const wctype_t alpha = wctype("alpha");
  const wctype_t digit = wctype("digit");
  CHECK(alpha != 0 && digit != 0 && wctype("unknown") == 0);
  CHECK(iswctype(L'Q', alpha) && !iswctype(L'7', alpha));
  CHECK(iswctype(L'7', digit) && !iswctype(L'Q', digit));

  const wctrans_t lower = wctrans("tolower");
  const wctrans_t upper = wctrans("toupper");
  CHECK(lower != 0 && upper != 0 && wctrans("unknown") == 0);
  CHECK(towctrans(L'Q', lower) == L'q');
  CHECK(towctrans(L'q', upper) == L'Q');
}

static void test_time_basics(void)
{
  struct timespec timestamp;
  int time_base = timespec_get(&timestamp, TIME_UTC);
  CHECK(time_base == 0 || time_base == TIME_UTC);
  if (time_base == TIME_UTC)
  {
    CHECK(timestamp.tv_nsec >= 0 && timestamp.tv_nsec < 1000000000L);
  }
  CHECK(timespec_get(&timestamp, 0) == 0);

  time_t epoch = 0;
  struct tm converted;
  CHECK(gmtime_r(&epoch, &converted) == &converted);
  CHECK(converted.tm_year == 70 && converted.tm_mon == 0 &&
        converted.tm_mday == 1 && converted.tm_wday == 4 &&
        converted.tm_yday == 0 && converted.tm_hour == 0 &&
        converted.tm_min == 0 && converted.tm_sec == 0);
  CHECK(localtime_r(&epoch, &converted) == &converted);

  time_t leap_day = (time_t)951782400;
  CHECK(gmtime_r(&leap_day, &converted) == &converted);
  CHECK(converted.tm_year == 100 && converted.tm_mon == 1 &&
        converted.tm_mday == 29 && converted.tm_wday == 2 &&
        converted.tm_yday == 59);
  CHECK(mktime(&converted) == leap_day);

  char calendar_text[26];
  CHECK(gmtime_r(&epoch, &converted) == &converted);
  CHECK(asctime_r(&converted, calendar_text) == calendar_text);
  CHECK(strcmp(calendar_text, "Thu Jan  1 00:00:00 1970\n") == 0);
  CHECK(ctime_r(&epoch, calendar_text) == calendar_text);
  CHECK(strcmp(calendar_text, "Thu Jan  1 00:00:00 1970\n") == 0);

  char formatted[96];
  CHECK(strftime(formatted, sizeof(formatted),
                 "%Y-%m-%d %H:%M:%S %a %j %U %W %V %G %z %Z",
                 &converted) == 51);
  CHECK(strcmp(formatted,
               "1970-01-01 00:00:00 Thu 001 00 00 01 1970 +0000 UTC") == 0);
  wchar_t wide_formatted[32];
  CHECK(wcsftime(wide_formatted, 32, L"%F %T %Z", &converted) == 23);
  CHECK(wcscmp(wide_formatted, L"1970-01-01 00:00:00 UTC") == 0);
  CHECK(wcsftime(wide_formatted, 32, L"日期:%Y", &converted) == 7);
  CHECK(wcscmp(wide_formatted, L"日期:1970") == 0);
  char too_short[4] = "bad";
  CHECK(strftime(too_short, sizeof(too_short), "%Y", &converted) == 0);
  CHECK(too_short[0] == '\0');

  struct tm extreme_year = converted;
  extreme_year.tm_year = INT_MAX;
  CHECK(strftime(formatted, sizeof(formatted), "%Y", &extreme_year) == 10);
  CHECK(strcmp(formatted, "2147485547") == 0);
  errno = 0;
  CHECK(asctime_r(&extreme_year, calendar_text) == NULL);
  CHECK(errno == EOVERFLOW);

  struct tm invalid_calendar = converted;
  invalid_calendar.tm_mon = 12;
  wide_formatted[0] = L'X';
  CHECK(wcsftime(wide_formatted, 32, L"%F", &invalid_calendar) == 0);
  CHECK(wide_formatted[0] == L'\0');

  double delta = difftime((time_t)5, (time_t)-2);
  uint64_t bits = 0;
  memcpy(&bits, &delta, sizeof(bits));
  CHECK(bits == UINT64_C(0x401c000000000000));
}

int main()
{
  CHECK(locale_wide_include_order_probe());
  test_locale();
  test_wide_strings();
  test_multibyte();
  test_wide_ctype();
  test_time_basics();

  printf(failures == 0 ? "LOCALE WIDE TEST PASS\n"
                       : "LOCALE WIDE TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
}
