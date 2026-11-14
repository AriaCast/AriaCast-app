/*
 * Bluetooth low-complexity, subband codec (SBC) library
 * Copyright (C) 2004-2010  Marcel Holtmann <marcel@holtmann.org>
 * Copyright (C) 2004-2005  Henryk Ploetz <henryk@ploetzli.ch>
 * Copyright (C) 2005-2006  Brad Midgley <bmidgley@xmission.com>
 * Copyright (C) 2008-2010  Nokia Corporation
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#ifndef __SBC_PRIMITIVES_NEON_H
#define __SBC_PRIMITIVES_NEON_H

#include "sbc_primitives.h"

#if defined(__GNUC__) && defined(__ARM_NEON__) && \
		!defined(SBC_HIGH_PRECISION) && (SCALE_OUT_BITS == 15)

#define SBC_BUILD_WITH_NEON_SUPPORT

void sbc_init_primitives_neon(struct sbc_encoder_state *encoder_state);

#endif

#endif
