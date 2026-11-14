/*
 * Bluetooth low-complexity, subband codec (SBC) library
 * Copyright (C) 2020  Intel Corporation
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#ifndef __SBC_PRIMITIVES_SSE_H
#define __SBC_PRIMITIVES_SSE_H

#include "sbc_primitives.h"

#if defined(__GNUC__) && (defined(__i386__) || defined(__amd64__)) && \
		!defined(SBC_HIGH_PRECISION) && (SCALE_OUT_BITS == 15)

#define SBC_BUILD_WITH_SSE_SUPPORT

void sbc_init_primitives_sse(struct sbc_encoder_state *encoder_state);

#endif

#endif
