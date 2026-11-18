#include <am.h>
#include <klib.h>
#include <stdint.h>

static int failures;
static int compare_calls;

#define CHECK(cond) \
  do { \
    if (!(cond)) { \
      failures++; \
      printf("qsort-test check failed at line %d\n", __LINE__); \
    } \
  } while (0)

static int CompareInt(const void *left, const void *right)
{
  int a = *(const int *)left;
  int b = *(const int *)right;
  return (a > b) - (a < b);
}

static int CountingCompare(const void *left, const void *right)
{
  (void)left;
  (void)right;
  compare_calls++;
  return 0;
}

typedef struct
{
  int key;
  uint32_t id;
} Record;

static int CompareRecord(const void *left, const void *right)
{
  const Record *a = (const Record *)left;
  const Record *b = (const Record *)right;
  if (a->key != b->key)
  {
    return (a->key > b->key) - (a->key < b->key);
  }
  return (a->id > b->id) - (a->id < b->id);
}

typedef struct
{
  unsigned char bytes[3];
} Triple;

static int CompareTriple(const void *left, const void *right)
{
  const Triple *a = (const Triple *)left;
  const Triple *b = (const Triple *)right;
  for (size_t i = 0; i < sizeof(a->bytes); i++)
  {
    if (a->bytes[i] != b->bytes[i])
    {
      return (a->bytes[i] > b->bytes[i]) -
             (a->bytes[i] < b->bytes[i]);
    }
  }
  return 0;
}

enum
{
  BigPayloadSize = 1021,
  BigElementCount = 19,
};

typedef struct
{
  int key;
  uint32_t id;
  unsigned char payload[BigPayloadSize];
  uint32_t canary;
} BigElement;

static BigElement big_elements[BigElementCount];

static int CompareBigElement(const void *left, const void *right)
{
  const BigElement *a = (const BigElement *)left;
  const BigElement *b = (const BigElement *)right;
  if (a->key != b->key)
  {
    return (a->key > b->key) - (a->key < b->key);
  }
  return (a->id > b->id) - (a->id < b->id);
}

static unsigned char PayloadByte(uint32_t id, size_t offset)
{
  return (unsigned char)(id * 29u + (uint32_t)offset * 17u + 3u);
}

static void TestNoOpCases(void)
{
  int one = 42;

  compare_calls = 0;
  qsort(NULL, 0, sizeof(one), CountingCompare);
  CHECK(compare_calls == 0);

  qsort(&one, 1, sizeof(one), CountingCompare);
  CHECK(one == 42);
  CHECK(compare_calls == 0);

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
  qsort(NULL, SIZE_MAX, 0, CountingCompare);
  CHECK(compare_calls == 0);

  /* The product overflows size_t, so no element address may be formed. */
  qsort(&one, SIZE_MAX / 2 + 1, 2, CountingCompare);
  CHECK(one == 42);
  CHECK(compare_calls == 0);

  CHECK(bsearch(&one, NULL, 0, sizeof(one), CountingCompare) == NULL);
  CHECK(bsearch(&one, &one, 1, 0, CountingCompare) == NULL);
  CHECK(bsearch(&one, &one, SIZE_MAX / 2 + 1, 2,
                CountingCompare) == NULL);
  CHECK(bsearch(NULL, &one, 1, sizeof(one), CountingCompare) == NULL);
  CHECK(bsearch(&one, &one, 1, sizeof(one), NULL) == NULL);
  CHECK(compare_calls == 0);
#endif
}

static void TestIntsAndDuplicates(void)
{
  int values[] = {
      7, -4, 7, 0, -4, 99, -100, 7, 1, 1, 1, 42, -100, 5,
  };
  const int expected[] = {
      -100, -100, -4, -4, 0, 1, 1, 1, 5, 7, 7, 7, 42, 99,
  };

  qsort(values, sizeof(values) / sizeof(values[0]), sizeof(values[0]),
        CompareInt);
  CHECK(memcmp(values, expected, sizeof(values)) == 0);
}

static void TestStructures(void)
{
  Record records[] = {
      {2, 9}, {-1, 4}, {2, 3}, {0, 8}, {-1, 1}, {2, 2}, {0, 7},
  };
  const Record expected[] = {
      {-1, 1}, {-1, 4}, {0, 7}, {0, 8}, {2, 2}, {2, 3}, {2, 9},
  };

  qsort(records, sizeof(records) / sizeof(records[0]), sizeof(records[0]),
        CompareRecord);
  CHECK(memcmp(records, expected, sizeof(records)) == 0);
}

static void TestOddElementSize(void)
{
  Triple triples[] = {
      {{1, 2, 9}}, {{0, 255, 1}}, {{1, 2, 3}},
      {{1, 1, 255}}, {{0, 255, 0}}, {{1, 2, 3}},
  };
  const Triple expected[] = {
      {{0, 255, 0}}, {{0, 255, 1}}, {{1, 1, 255}},
      {{1, 2, 3}}, {{1, 2, 3}}, {{1, 2, 9}},
  };

  qsort(triples, sizeof(triples) / sizeof(triples[0]), sizeof(triples[0]),
        CompareTriple);
  CHECK(memcmp(triples, expected, sizeof(triples)) == 0);
}

static void TestLargeElements(void)
{
  for (uint32_t i = 0; i < BigElementCount; i++)
  {
    big_elements[i].key = (int)((i * 11u) % 9u) - 4;
    big_elements[i].id = i;
    for (size_t j = 0; j < BigPayloadSize; j++)
    {
      big_elements[i].payload[j] = PayloadByte(i, j);
    }
    big_elements[i].canary = 0xc0010000u ^ i;
  }

  qsort(big_elements, BigElementCount, sizeof(big_elements[0]),
        CompareBigElement);

  for (size_t i = 0; i < BigElementCount; i++)
  {
    if (i != 0)
    {
      CHECK(CompareBigElement(&big_elements[i - 1], &big_elements[i]) <= 0);
    }
    uint32_t id = big_elements[i].id;
    CHECK(id < BigElementCount);
    CHECK(big_elements[i].canary == (0xc0010000u ^ id));
    for (size_t j = 0; j < BigPayloadSize; j++)
    {
      CHECK(big_elements[i].payload[j] == PayloadByte(id, j));
    }
  }
}

static void TestBsearchInts(void)
{
  const int values[] = {
      -100, -17, -4, 0, 1, 5, 7, 7, 7, 42, 99, 1000,
  };
  const int present[] = {-100, -4, 0, 1, 7, 42, 1000};
  const int absent[] = {-101, -99, -1, 6, 8, 43, 1001};

  for (size_t i = 0; i < sizeof(present) / sizeof(present[0]); i++)
  {
    const int *found = (const int *)bsearch(
        &present[i], values, sizeof(values) / sizeof(values[0]),
        sizeof(values[0]), CompareInt);
    CHECK(found != NULL);
    if (found != NULL)
    {
      CHECK(*found == present[i]);
      CHECK(found >= values && found < values + sizeof(values) / sizeof(values[0]));
    }
  }

  for (size_t i = 0; i < sizeof(absent) / sizeof(absent[0]); i++)
  {
    CHECK(bsearch(&absent[i], values,
                  sizeof(values) / sizeof(values[0]), sizeof(values[0]),
                  CompareInt) == NULL);
  }
}

static void TestBsearchStructures(void)
{
  const Record records[] = {
      {-7, 1}, {-7, 9}, {0, 0}, {3, 4}, {3, 8}, {11, 2},
  };
  const Record key = {3, 8};
  const Record missing = {3, 7};

  const Record *found = (const Record *)bsearch(
      &key, records, sizeof(records) / sizeof(records[0]),
      sizeof(records[0]), CompareRecord);
  CHECK(found == &records[4]);
  CHECK(bsearch(&missing, records, sizeof(records) / sizeof(records[0]),
                sizeof(records[0]), CompareRecord) == NULL);
}

int main()
{
  TestNoOpCases();
  TestIntsAndDuplicates();
  TestStructures();
  TestOddElementSize();
  TestLargeElements();
  TestBsearchInts();
  TestBsearchStructures();

  printf(failures == 0 ? "QSORT/BSEARCH TEST PASS\n"
                       : "QSORT/BSEARCH TEST FAIL, failures=%d\n",
         failures);
  halt(failures == 0 ? 0 : 1);
  return failures == 0 ? 0 : 1;
}
