#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BLOCK_SIZE (512 * 1024)

int main() {
  unsigned char *p = malloc(BLOCK_SIZE);
  assert(p != NULL);
  memset(p, 0x5a, BLOCK_SIZE);
  for (int i = 0; i < BLOCK_SIZE; i++) {
    assert(p[i] == 0x5a);
  }
  free(p);
  printf("malloc-test: PASS, %d KB allocated\n", BLOCK_SIZE / 1024);
  return 0;
}
