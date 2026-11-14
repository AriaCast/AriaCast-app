#ifndef ARIA_VERSION_H
#define ARIA_VERSION_H
/* Firmware version 2.1 (major 2, minor 1); bcdDevice and GET_INFO use 0xMMmm. */
#define ARIA_FW_VERSION_MAJOR 2
#define ARIA_FW_VERSION_MINOR 1
#define ARIA_FW_VERSION_BCD ((ARIA_FW_VERSION_MAJOR << 8) | ARIA_FW_VERSION_MINOR)
#endif
