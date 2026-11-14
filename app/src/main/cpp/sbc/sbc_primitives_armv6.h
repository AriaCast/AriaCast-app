/*
 * Bluetooth low-complexity, subband codec (SBC) library
 * Copyright (C) 2004-2010  Marcel Holtmann <marcel@holtmann.org>
 * Copyright (C) 2004-2005  Henryk Ploetz <henryk@ploetzli.ch>
 * Copyright (C) 2005-2006  Brad Midgley <bmidgley@xmission.com>
 * Copyright (C) 2008-2010  Nokia Corporation
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#ifndef __SBC_PRIMITIVES_ARMV6_H
#define __SBC_PRIMITIVES_ARMV6_H

#include "sbc_primitives.h"

#if defined(__ARM_ARCH_6__) || defined(__ARM_ARCH_6J__) || \
	defined(__ARM_ARCH_6K__) || defined(__ARM_ARCH_6Z__) || \
	defined(__ARM_ARCH_6ZK__) || defined(__ARM_ARCH_6T2__) || \
	defined(__ARM_ARCH_6M__) || defined(__ARM_ARCH_7__) || \
	defined(__ARM_ARCH_7A__) || defined(__ARM_ARCH_7R__) || \
	defined(__ARM_ARCH_7M__)
#define SBC_HAVE_ARMV6 1
#endif

#if !defined(SBC_HIGH_PRECISION) && (SCALE_OUT_BITS == 15) && \
	defined(__GNUC__) && defined(SBC_HAVE_ARMV6) && \
	defined(__ARM_EABI__) && !defined(__ARM_NEON__) && \
	(!defined(__thumb__) || defined(__thumb2__))

#define SBC_BUILD_WITH_ARMV6_SUPPORT

void sbc_init_primitives_armv6(struct sbc_encoder_state *encoder_state);

#endif

#endif
