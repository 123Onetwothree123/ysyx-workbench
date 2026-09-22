#include <wctype.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#include <klib.h>

enum {
  WCTYPE_NONE = 0,
  WCTYPE_ALNUM,
  WCTYPE_ALPHA,
  WCTYPE_BLANK,
  WCTYPE_CNTRL,
  WCTYPE_DIGIT,
  WCTYPE_GRAPH,
  WCTYPE_LOWER,
  WCTYPE_PRINT,
  WCTYPE_PUNCT,
  WCTYPE_SPACE,
  WCTYPE_UPPER,
  WCTYPE_XDIGIT,
};

enum {
  WCTRANS_TOLOWER = 1,
  WCTRANS_TOUPPER = 2,
};

int iswalpha(wint_t wc)
{
  return (wc >= (wint_t)'A' && wc <= (wint_t)'Z') ||
         (wc >= (wint_t)'a' && wc <= (wint_t)'z');
}

int iswdigit(wint_t wc)
{
  return wc >= (wint_t)'0' && wc <= (wint_t)'9';
}

int iswalnum(wint_t wc)
{
  return iswalpha(wc) || iswdigit(wc);
}

int iswblank(wint_t wc)
{
  return wc == (wint_t)' ' || wc == (wint_t)'\t';
}

int iswcntrl(wint_t wc)
{
  return wc != WEOF &&
         (wc <= (wint_t)0x1f || wc == (wint_t)0x7f);
}

int iswgraph(wint_t wc)
{
  return wc >= (wint_t)0x21 && wc <= (wint_t)0x7e;
}

int iswlower(wint_t wc)
{
  return wc >= (wint_t)'a' && wc <= (wint_t)'z';
}

int iswprint(wint_t wc)
{
  return wc >= (wint_t)0x20 && wc <= (wint_t)0x7e;
}

int iswpunct(wint_t wc)
{
  return iswgraph(wc) && !iswalnum(wc);
}

int iswspace(wint_t wc)
{
  return wc == (wint_t)' ' ||
         (wc >= (wint_t)'\t' && wc <= (wint_t)'\r');
}

int iswupper(wint_t wc)
{
  return wc >= (wint_t)'A' && wc <= (wint_t)'Z';
}

int iswxdigit(wint_t wc)
{
  return iswdigit(wc) ||
         (wc >= (wint_t)'A' && wc <= (wint_t)'F') ||
         (wc >= (wint_t)'a' && wc <= (wint_t)'f');
}

wint_t towlower(wint_t wc)
{
  return iswupper(wc) ? wc + ((wint_t)'a' - (wint_t)'A') : wc;
}

wint_t towupper(wint_t wc)
{
  return iswlower(wc) ? wc - ((wint_t)'a' - (wint_t)'A') : wc;
}

wctype_t wctype(const char *property)
{
  if (property == NULL)
  {
    return WCTYPE_NONE;
  }
  if (strcmp(property, "alnum") == 0) return WCTYPE_ALNUM;
  if (strcmp(property, "alpha") == 0) return WCTYPE_ALPHA;
  if (strcmp(property, "blank") == 0) return WCTYPE_BLANK;
  if (strcmp(property, "cntrl") == 0) return WCTYPE_CNTRL;
  if (strcmp(property, "digit") == 0) return WCTYPE_DIGIT;
  if (strcmp(property, "graph") == 0) return WCTYPE_GRAPH;
  if (strcmp(property, "lower") == 0) return WCTYPE_LOWER;
  if (strcmp(property, "print") == 0) return WCTYPE_PRINT;
  if (strcmp(property, "punct") == 0) return WCTYPE_PUNCT;
  if (strcmp(property, "space") == 0) return WCTYPE_SPACE;
  if (strcmp(property, "upper") == 0) return WCTYPE_UPPER;
  if (strcmp(property, "xdigit") == 0) return WCTYPE_XDIGIT;
  return WCTYPE_NONE;
}

int iswctype(wint_t wc, wctype_t property)
{
  switch (property)
  {
  case WCTYPE_ALNUM: return iswalnum(wc);
  case WCTYPE_ALPHA: return iswalpha(wc);
  case WCTYPE_BLANK: return iswblank(wc);
  case WCTYPE_CNTRL: return iswcntrl(wc);
  case WCTYPE_DIGIT: return iswdigit(wc);
  case WCTYPE_GRAPH: return iswgraph(wc);
  case WCTYPE_LOWER: return iswlower(wc);
  case WCTYPE_PRINT: return iswprint(wc);
  case WCTYPE_PUNCT: return iswpunct(wc);
  case WCTYPE_SPACE: return iswspace(wc);
  case WCTYPE_UPPER: return iswupper(wc);
  case WCTYPE_XDIGIT: return iswxdigit(wc);
  default: return 0;
  }
}

wctrans_t wctrans(const char *property)
{
  if (property == NULL)
  {
    return (wctrans_t)0;
  }
  if (strcmp(property, "tolower") == 0)
  {
    return WCTRANS_TOLOWER;
  }
  if (strcmp(property, "toupper") == 0)
  {
    return WCTRANS_TOUPPER;
  }
  return (wctrans_t)0;
}

wint_t towctrans(wint_t wc, wctrans_t conversion)
{
  if (conversion == WCTRANS_TOLOWER)
  {
    return towlower(wc);
  }
  if (conversion == WCTRANS_TOUPPER)
  {
    return towupper(wc);
  }
  return wc;
}

#endif
