/*
 * AriaCompanion 2 vendor protocol, version 1 (docs/ariacompanion-2.md §4).
 *
 * This header is the source of truth. The Android app mirrors it in
 * app/src/main/java/com/aria/ariacast/companion/CompanionProtocol.kt and a unit test
 * (CompanionProtocolTest) fails if the two disagree. All multi-byte fields are little-endian.
 */
#ifndef ARIA_COMPANION_PROTO_H
#define ARIA_COMPANION_PROTO_H

#define ARIA_PROTOCOL_VERSION      1

/* Vendor interface identity: the app matches on these, not on VID/PID. */
#define ARIA_IFACE_CLASS           0xFF
#define ARIA_IFACE_SUBCLASS        0xAC
#define ARIA_IFACE_PROTOCOL        0x01

/* pid.codes test PID for development; replace with the Raspberry Pi assigned PID. */
#define ARIA_VID                   0x1209
#define ARIA_PID                   0x0001

/* Bulk IN chunk header: u8 magic, u8 type, u16 length (payload), u32 seq */
#define ARIA_MAGIC                 0xAC
#define ARIA_HEADER_BYTES          8
#define ARIA_MAX_PAYLOAD           4096

#define ARIA_CHUNK_AUDIO           0x01  /* u16 sof_frame, u16 reserved, PCM...           */
#define ARIA_CHUNK_FORMAT          0x02  /* u32 rate, u8 bits, u8 subslot, u8 ch, u8 res  */
#define ARIA_CHUNK_STREAM          0x03  /* u8 state: 0 closed, 1 streaming               */
#define ARIA_CHUNK_OVERRUN         0x04  /* u32 dropped_bytes                             */
#define ARIA_CHUNK_MODE            0x05  /* u8 mode                                       */

#define ARIA_AUDIO_PREFIX_BYTES    4

/* EP0 vendor requests, recipient = the vendor interface */
#define ARIA_REQTYPE_OUT           0x41
#define ARIA_REQTYPE_IN            0xC1
#define ARIA_REQ_GET_INFO          0x01  /* IN  8 bytes: proto, mode, fw u16, caps u16, ring_kb u16 */
#define ARIA_REQ_SET_MODE          0x02  /* OUT wValue = mode                              */
#define ARIA_REQ_HEARTBEAT         0x03  /* OUT wValue = timeout seconds (1..10)            */
#define ARIA_REQ_GET_STATS         0x04  /* IN  20 bytes: iso, bulk, overruns, resets (u32), peak_l, peak_r (u16) */
#define ARIA_REQ_REBOOT_BOOTLOADER 0x05
#define ARIA_REQ_RESET_STATS       0x06

#define ARIA_INFO_BYTES            8
#define ARIA_STATS_BYTES           20

#define ARIA_MODE_PRIVATE          0
#define ARIA_MODE_SPEAKER          1

#define ARIA_CAP_24BIT             (1u << 0)
#define ARIA_CAP_44K1              (1u << 1)
#define ARIA_CAP_96K               (1u << 2)
#define ARIA_CAP_LED               (1u << 3)

#define ARIA_SOF_MODULO            2048

/* Audio format of protocol v1 */
#define ARIA_SAMPLE_RATE           48000
#define ARIA_CHANNELS              2
#define ARIA_BYTES_PER_SAMPLE      2
#define ARIA_BYTES_PER_MS          (ARIA_SAMPLE_RATE / 1000 * ARIA_CHANNELS * ARIA_BYTES_PER_SAMPLE)

#endif
