/*
 * Ring buffer and chunk encoder for the USB loopback. Portable C with no Pico or TinyUSB
 * dependencies, so it is unit-tested on the build host (firmware/test).
 */
#ifndef ARIA_LOOPBACK_H
#define ARIA_LOOPBACK_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#include "aria_companion_proto.h"

#ifndef ARIA_RING_BYTES
#define ARIA_RING_BYTES (64u * 1024u)   /* must be a power of two; ~340 ms at 48 kHz/16-bit */
#endif

/* One AUDIO chunk carries 2 ms of audio. */
#define ARIA_CHUNK_PCM_BYTES (2u * ARIA_BYTES_PER_MS)

/* A pause in the host's packets longer than this is filled with silence. */
#define ARIA_STALL_FRAMES 4u
#define ARIA_MAX_GAP_MS   200u

typedef struct {
    uint8_t  ring[ARIA_RING_BYTES];
    uint32_t head;          /* write position (monotonic, wraps via mask) */
    uint32_t tail;          /* read position */
    uint32_t seq;           /* next chunk sequence number */
    uint16_t push_sof;      /* USB frame of the next sample to be pushed */
    uint16_t pop_sof;       /* USB frame of the next sample to be popped */
    uint32_t push_carry;    /* bytes pushed beyond whole milliseconds */
    uint32_t pop_carry;
    bool     timeline_valid;
    uint32_t pending_overrun;  /* dropped bytes not yet reported */
    /* statistics */
    uint32_t iso_bytes;
    uint32_t bulk_bytes;
    uint32_t overruns;
    uint16_t peak_l, peak_r;
} aria_loop_t;

void     aria_loop_init(aria_loop_t *l);
/* Starts a new stream (host selected the streaming alt setting). */
void     aria_loop_stream_start(aria_loop_t *l);
/* Adds PCM received from the host during USB frame now_sof. */
void     aria_loop_push(aria_loop_t *l, const uint8_t *pcm, uint32_t n, uint16_t now_sof);
uint32_t aria_loop_available(const aria_loop_t *l);

/* Chunk writers: each writes one complete chunk into out (capacity cap) and returns its
 * size, or 0 if it doesn't fit / there is nothing to send. */
size_t   aria_write_audio_chunk(aria_loop_t *l, uint8_t *out, size_t cap, bool flush);
size_t   aria_write_format_chunk(aria_loop_t *l, uint8_t *out, size_t cap);
size_t   aria_write_stream_chunk(aria_loop_t *l, uint8_t *out, size_t cap, bool active);
size_t   aria_write_mode_chunk(aria_loop_t *l, uint8_t *out, size_t cap, uint8_t mode);
size_t   aria_write_overrun_chunk(aria_loop_t *l, uint8_t *out, size_t cap);

#endif
