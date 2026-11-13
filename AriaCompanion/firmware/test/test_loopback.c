/* Host-side unit tests for loopback.c: cc -I../include -I../src test_loopback.c ../src/loopback.c */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "aria_companion_proto.h"
#include "loopback.h"

static aria_loop_t l;
static uint8_t out[8192];
static int tests = 0;
#define CHECK(c) do { tests++; if (!(c)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) { return rd16(p) | ((uint32_t)rd16(p + 2) << 16); }

static void fill(uint8_t *b, uint32_t n, uint8_t start) { for (uint32_t i = 0; i < n; i++) b[i] = (uint8_t)(start + i); }

static int test_chunk_format(void) {
    aria_loop_init(&l); aria_loop_stream_start(&l);
    uint8_t pcm[ARIA_BYTES_PER_MS * 2]; fill(pcm, sizeof pcm, 7);
    aria_loop_push(&l, pcm, ARIA_BYTES_PER_MS, 100);
    aria_loop_push(&l, pcm + ARIA_BYTES_PER_MS, ARIA_BYTES_PER_MS, 101);
    size_t n = aria_write_audio_chunk(&l, out, sizeof out, false);
    CHECK(n == ARIA_HEADER_BYTES + ARIA_AUDIO_PREFIX_BYTES + ARIA_CHUNK_PCM_BYTES);
    CHECK(out[0] == ARIA_MAGIC && out[1] == ARIA_CHUNK_AUDIO);
    CHECK(rd16(out + 2) == ARIA_AUDIO_PREFIX_BYTES + ARIA_CHUNK_PCM_BYTES);
    CHECK(rd32(out + 4) == 0);                       /* first sequence number */
    CHECK(rd16(out + 8) == 99);                      /* first push arrived in frame 100, 1 ms long */
    CHECK(memcmp(out + 12, pcm, sizeof pcm) == 0);
    CHECK(aria_write_audio_chunk(&l, out, sizeof out, false) == 0); /* empty now */
    return 0;
}

static int test_wraparound_is_lossless(void) {
    aria_loop_init(&l); aria_loop_stream_start(&l);
    uint8_t pcm[ARIA_BYTES_PER_MS], got[ARIA_CHUNK_PCM_BYTES];
    uint16_t sof = 2040; /* crosses the 2048 SOF wrap too */
    uint32_t pushed = 0, popped = 0; uint8_t next_expected = 0;
    for (int i = 0; i < 2000; i++) {
        fill(pcm, sizeof pcm, (uint8_t)pushed);
        aria_loop_push(&l, pcm, sizeof pcm, sof);
        sof = (uint16_t)((sof + 1) % ARIA_SOF_MODULO);
        pushed += sizeof pcm;
        size_t n;
        while ((n = aria_write_audio_chunk(&l, out, sizeof out, false)) > 0) {
            memcpy(got, out + 12, n - 12);
            for (size_t k = 0; k < n - 12; k++) { CHECK(got[k] == next_expected); next_expected++; }
            popped += (uint32_t)(n - 12);
        }
    }
    CHECK(popped == pushed);
    CHECK(l.overruns == 0);
    return 0;
}

static int test_overrun_drops_oldest(void) {
    aria_loop_init(&l); aria_loop_stream_start(&l);
    static uint8_t pcm[ARIA_RING_BYTES + 4 * ARIA_BYTES_PER_MS];
    uint16_t sof = 0;
    for (uint32_t off = 0; off < sizeof pcm; off += ARIA_BYTES_PER_MS) {
        aria_loop_push(&l, pcm + off, ARIA_BYTES_PER_MS, sof++);
    }
    CHECK(l.overruns > 0);
    CHECK(aria_loop_available(&l) <= ARIA_RING_BYTES);
    size_t n = aria_write_overrun_chunk(&l, out, sizeof out);
    CHECK(n == ARIA_HEADER_BYTES + 4 && out[1] == ARIA_CHUNK_OVERRUN && rd32(out + 8) >= 4 * ARIA_BYTES_PER_MS);
    CHECK(aria_write_overrun_chunk(&l, out, sizeof out) == 0); /* reported once */
    return 0;
}

static int test_pause_is_filled_with_silence(void) {
    aria_loop_init(&l); aria_loop_stream_start(&l);
    uint8_t pcm[ARIA_BYTES_PER_MS]; memset(pcm, 0x55, sizeof pcm);
    aria_loop_push(&l, pcm, sizeof pcm, 10);
    aria_loop_push(&l, pcm, sizeof pcm, 30);   /* host sent nothing for ~19 ms */
    /* 1 ms data + 19 ms silence + 1 ms data */
    CHECK(aria_loop_available(&l) == 21 * ARIA_BYTES_PER_MS);
    return 0;
}

static int test_jitter_is_not_a_pause(void) {
    aria_loop_init(&l); aria_loop_stream_start(&l);
    uint8_t pcm[ARIA_BYTES_PER_MS * 3]; memset(pcm, 1, sizeof pcm);
    aria_loop_push(&l, pcm, ARIA_BYTES_PER_MS, 10);
    aria_loop_push(&l, pcm, ARIA_BYTES_PER_MS * 3, 14); /* three frames read at once, a bit late */
    CHECK(aria_loop_available(&l) == 4 * ARIA_BYTES_PER_MS);
    return 0;
}

static int test_events(void) {
    aria_loop_init(&l);
    size_t n = aria_write_format_chunk(&l, out, sizeof out);
    CHECK(n == 16 && out[1] == ARIA_CHUNK_FORMAT && rd32(out + 8) == ARIA_SAMPLE_RATE && out[12] == 16 && out[13] == 2 && out[14] == 2);
    n = aria_write_stream_chunk(&l, out, sizeof out, true);
    CHECK(n == 9 && out[1] == ARIA_CHUNK_STREAM && out[8] == 1 && rd32(out + 4) == 1);
    n = aria_write_mode_chunk(&l, out, sizeof out, ARIA_MODE_SPEAKER);
    CHECK(n == 9 && out[1] == ARIA_CHUNK_MODE && out[8] == ARIA_MODE_SPEAKER && rd32(out + 4) == 2);
    CHECK(aria_write_format_chunk(&l, out, 10) == 0); /* doesn't fit */
    return 0;
}

static int test_peak_meter(void) {
    aria_loop_init(&l); aria_loop_stream_start(&l);
    int16_t s[4] = { 1000, -32768, -500, 200 };
    aria_loop_push(&l, (uint8_t *)s, sizeof s, 5);
    CHECK(l.peak_l == 1000);   /* max(|1000|, |-500|) */
    CHECK(l.peak_r == 32768);  /* |-32768| still fits a u16 */
    return 0;
}

int main(void) {
    int (*all[])(void) = { test_chunk_format, test_wraparound_is_lossless, test_overrun_drops_oldest,
                           test_pause_is_filled_with_silence, test_jitter_is_not_a_pause, test_events, test_peak_meter };
    for (size_t i = 0; i < sizeof all / sizeof all[0]; i++) if (all[i]()) return 1;
    printf("loopback: %d checks passed\n", tests);
    return 0;
}
