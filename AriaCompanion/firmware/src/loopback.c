#include "aria_companion_proto.h"
#include "loopback.h"

#include <string.h>

#define MASK (ARIA_RING_BYTES - 1u)

_Static_assert((ARIA_RING_BYTES & (ARIA_RING_BYTES - 1u)) == 0, "ring must be a power of two");

static void put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void put32(uint8_t *p, uint32_t v) { put16(p, (uint16_t)v); put16(p + 2, (uint16_t)(v >> 16)); }

static size_t header(aria_loop_t *l, uint8_t *out, uint8_t type, uint16_t payload) {
    out[0] = ARIA_MAGIC;
    out[1] = type;
    put16(out + 2, payload);
    put32(out + 4, l->seq++);
    return ARIA_HEADER_BYTES;
}

static uint16_t sof_add(uint16_t sof, uint32_t ms) { return (uint16_t)((sof + ms) % ARIA_SOF_MODULO); }
static uint16_t sof_diff(uint16_t a, uint16_t b) { return (uint16_t)((a + ARIA_SOF_MODULO - b) % ARIA_SOF_MODULO); }

void aria_loop_init(aria_loop_t *l) {
    memset(l, 0, sizeof(*l));
}

void aria_loop_stream_start(aria_loop_t *l) {
    l->head = l->tail = 0;
    l->timeline_valid = false;
    l->push_carry = l->pop_carry = 0;
}

uint32_t aria_loop_available(const aria_loop_t *l) { return l->head - l->tail; }

static void ring_write(aria_loop_t *l, const uint8_t *src, uint32_t n, bool zeros) {
    /* Drop the oldest audio rather than the newest: latency matters more than continuity. */
    uint32_t free_bytes = ARIA_RING_BYTES - aria_loop_available(l);
    if (n > free_bytes) {
        uint32_t drop = n - free_bytes;
        drop = (drop + 3u) & ~3u;                    /* keep whole stereo frames */
        if (drop > aria_loop_available(l)) drop = aria_loop_available(l);
        l->tail += drop;
        l->pending_overrun += drop;
        l->overruns++;
        /* The dropped audio's time is gone too. */
        uint32_t total = l->pop_carry + drop;
        l->pop_sof = sof_add(l->pop_sof, total / ARIA_BYTES_PER_MS);
        l->pop_carry = total % ARIA_BYTES_PER_MS;
    }
    if (n > ARIA_RING_BYTES) { src += zeros ? 0 : (n - ARIA_RING_BYTES); n = ARIA_RING_BYTES; }
    uint32_t pos = l->head & MASK;
    uint32_t first = ARIA_RING_BYTES - pos;
    if (first > n) first = n;
    if (zeros) { memset(l->ring + pos, 0, first); memset(l->ring, 0, n - first); }
    else { memcpy(l->ring + pos, src, first); memcpy(l->ring, src + first, n - first); }
    l->head += n;
}

static void advance_push(aria_loop_t *l, uint32_t n) {
    uint32_t total = l->push_carry + n;
    l->push_sof = sof_add(l->push_sof, total / ARIA_BYTES_PER_MS);
    l->push_carry = total % ARIA_BYTES_PER_MS;
}

void aria_loop_push(aria_loop_t *l, const uint8_t *pcm, uint32_t n, uint16_t now_sof) {
    if (n == 0) return;
    n &= ~3u;
    uint16_t ms = (uint16_t)(n / ARIA_BYTES_PER_MS);
    /* Timeline frame at which this data started arriving. */
    uint16_t start = sof_diff(now_sof, ms);
    if (!l->timeline_valid) {
        l->push_sof = l->pop_sof = start;
        l->push_carry = l->pop_carry = 0;
        l->timeline_valid = true;
    } else {
        uint16_t lag = sof_diff(start, l->push_sof);
        /* lag < 1024: the host is behind our timeline, i.e. it paused; fill with silence.
         * lag >= 1024 means the data is "early" (normal jitter); ignore. */
        if (lag > ARIA_STALL_FRAMES && lag < 1024u) {
            uint32_t gap_ms = lag > ARIA_MAX_GAP_MS ? ARIA_MAX_GAP_MS : lag;
            ring_write(l, NULL, gap_ms * ARIA_BYTES_PER_MS, true);
            advance_push(l, gap_ms * ARIA_BYTES_PER_MS);
            if (lag > ARIA_MAX_GAP_MS) l->push_sof = start; /* long pause: resync */
        }
    }
    /* Peak meter (16-bit little-endian stereo). */
    for (uint32_t i = 0; i + 3 < n; i += 4) {
        int16_t sl = (int16_t)(pcm[i] | (pcm[i + 1] << 8));
        int16_t sr = (int16_t)(pcm[i + 2] | (pcm[i + 3] << 8));
        uint16_t al = (uint16_t)(sl < 0 ? -(int32_t)sl : sl);
        uint16_t ar = (uint16_t)(sr < 0 ? -(int32_t)sr : sr);
        if (al > l->peak_l) l->peak_l = al;
        if (ar > l->peak_r) l->peak_r = ar;
    }
    ring_write(l, pcm, n, false);
    advance_push(l, n);
    l->iso_bytes += n;
}

size_t aria_write_audio_chunk(aria_loop_t *l, uint8_t *out, size_t cap, bool flush) {
    uint32_t avail = aria_loop_available(l);
    uint32_t n = avail >= ARIA_CHUNK_PCM_BYTES ? ARIA_CHUNK_PCM_BYTES : (flush ? (avail & ~3u) : 0);
    if (n == 0) return 0;
    size_t total = ARIA_HEADER_BYTES + ARIA_AUDIO_PREFIX_BYTES + n;
    if (total > cap) return 0;
    size_t off = header(l, out, ARIA_CHUNK_AUDIO, (uint16_t)(ARIA_AUDIO_PREFIX_BYTES + n));
    put16(out + off, l->pop_sof);
    put16(out + off + 2, 0);
    off += ARIA_AUDIO_PREFIX_BYTES;
    uint32_t pos = l->tail & MASK;
    uint32_t first = ARIA_RING_BYTES - pos;
    if (first > n) first = n;
    memcpy(out + off, l->ring + pos, first);
    memcpy(out + off + first, l->ring, n - first);
    l->tail += n;
    uint32_t t = l->pop_carry + n;
    l->pop_sof = sof_add(l->pop_sof, t / ARIA_BYTES_PER_MS);
    l->pop_carry = t % ARIA_BYTES_PER_MS;
    l->bulk_bytes += (uint32_t)total;
    return total;
}

size_t aria_write_format_chunk(aria_loop_t *l, uint8_t *out, size_t cap) {
    if (cap < ARIA_HEADER_BYTES + 8) return 0;
    size_t off = header(l, out, ARIA_CHUNK_FORMAT, 8);
    put32(out + off, ARIA_SAMPLE_RATE);
    out[off + 4] = 16;
    out[off + 5] = ARIA_BYTES_PER_SAMPLE;
    out[off + 6] = ARIA_CHANNELS;
    out[off + 7] = 0;
    return off + 8;
}

static size_t one_byte_chunk(aria_loop_t *l, uint8_t *out, size_t cap, uint8_t type, uint8_t v) {
    if (cap < ARIA_HEADER_BYTES + 1) return 0;
    size_t off = header(l, out, type, 1);
    out[off] = v;
    return off + 1;
}

size_t aria_write_stream_chunk(aria_loop_t *l, uint8_t *out, size_t cap, bool active) {
    return one_byte_chunk(l, out, cap, ARIA_CHUNK_STREAM, active ? 1 : 0);
}

size_t aria_write_mode_chunk(aria_loop_t *l, uint8_t *out, size_t cap, uint8_t mode) {
    return one_byte_chunk(l, out, cap, ARIA_CHUNK_MODE, mode);
}

size_t aria_write_overrun_chunk(aria_loop_t *l, uint8_t *out, size_t cap) {
    if (l->pending_overrun == 0 || cap < ARIA_HEADER_BYTES + 4) return 0;
    size_t off = header(l, out, ARIA_CHUNK_OVERRUN, 4);
    put32(out + off, l->pending_overrun);
    l->pending_overrun = 0;
    return off + 4;
}
