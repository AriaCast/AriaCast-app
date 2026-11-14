/*
 * AriaCompanion BT protocol, version 1 (docs/ariacompanion-bt.md §3).
 *
 * Source of truth for the SPP link between the ESP32 board and the app. The chunk framing
 * and the command codes are shared with the USB board (firmware/include/aria_companion_proto.h);
 * the app mirrors both in CompanionProtocol.kt. All multi-byte fields are little-endian.
 */
#ifndef ARIA_BT_PROTO_H
#define ARIA_BT_PROTO_H

#define ARIA_BT_PROTOCOL_VERSION   1

#define ARIA_BT_DEVICE_NAME        "AriaCompanion BT"
#define ARIA_BT_SPP_NAME           "AriaCompanion"

/* Board -> app chunk header: u8 magic, u8 type, u16 length (payload), u32 seq */
#define ARIA_MAGIC                 0xAC
#define ARIA_HEADER_BYTES          8
#define ARIA_MAX_PAYLOAD           4096

#define ARIA_CHUNK_STREAM          0x03  /* u8 state: 0 suspended, 1 streaming                   */
#define ARIA_CHUNK_OVERRUN         0x04  /* u32 dropped_bytes                                    */
#define ARIA_CHUNK_MODE            0x05  /* u8 mode                                              */
#define ARIA_CHUNK_SBC_CONFIG      0x06  /* u32 rate, u8 ch_mode, u8 blocks, u8 subbands, u8 alloc,
                                            u8 min_bitpool, u8 max_bitpool, u16 reserved         */
#define ARIA_CHUNK_SBC_AUDIO       0x07  /* u32 timestamp, u16 frames, u16 reserved, SBC frames  */
#define ARIA_CHUNK_INFO            0x08  /* 8 bytes, same layout as the USB GET_INFO reply       */
#define ARIA_CHUNK_STATS           0x09  /* 20 bytes, same layout as the USB GET_STATS reply     */

#define ARIA_SBC_CONFIG_BYTES      12
#define ARIA_SBC_AUDIO_PREFIX      8
#define ARIA_INFO_BYTES            8
#define ARIA_STATS_BYTES           20

/* App -> board command: u8 magic, u8 command, u16 value */
#define ARIA_CMD_BYTES             4
#define ARIA_CMD_GET_INFO          0x01
#define ARIA_CMD_SET_MODE          0x02
#define ARIA_CMD_HEARTBEAT         0x03
#define ARIA_CMD_GET_STATS         0x04
#define ARIA_CMD_RESET_STATS       0x06

#define ARIA_MODE_PRIVATE          0
#define ARIA_MODE_SPEAKER          1

#define ARIA_CAP_LED               (1u << 3)
#define ARIA_CAP_BLUETOOTH         (1u << 4)   /* the INFO came over SPP: an ESP32 board */

/* SBC_CONFIG channel_mode / alloc values */
#define ARIA_SBC_MONO              0
#define ARIA_SBC_DUAL              1
#define ARIA_SBC_STEREO            2
#define ARIA_SBC_JOINT             3
#define ARIA_SBC_ALLOC_LOUDNESS    0
#define ARIA_SBC_ALLOC_SNR         1

#endif
