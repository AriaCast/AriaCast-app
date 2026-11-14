/*
 * Bluetooth low-complexity, subband codec (SBC) library
 * Copyright (C) 2004-2010  Marcel Holtmann <marcel@holtmann.org>
 * Copyright (C) 2004-2005  Henryk Ploetz <henryk@ploetzli.ch>
 * Copyright (C) 2005-2008  Brad Midgley <bmidgley@xmission.com>
 * Copyright (C) 2008-2010  Nokia Corporation
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#define fabs(x) ((x) < 0 ? -(x) : (x))
/* C does not provide an explicit arithmetic shift right but this will
   always be correct and every compiler *should* generate optimal code */
#define ASR(val, bits) ((-2 >> 1 == -1) ? \
		 ((int32_t)(val)) >> (bits) : ((int32_t) (val)) / (1 << (bits)))

#define SCALE_SPROTO4_TBL	12
#define SCALE_SPROTO8_TBL	14
#define SCALE_NPROTO4_TBL	11
#define SCALE_NPROTO8_TBL	11
#define SCALE4_STAGED1_BITS	15
#define SCALE4_STAGED2_BITS	16
#define SCALE8_STAGED1_BITS	15
#define SCALE8_STAGED2_BITS	16

typedef int32_t sbc_fixed_t;

#define SCALE4_STAGED1(src) ASR(src, SCALE4_STAGED1_BITS)
#define SCALE4_STAGED2(src) ASR(src, SCALE4_STAGED2_BITS)
#define SCALE8_STAGED1(src) ASR(src, SCALE8_STAGED1_BITS)
#define SCALE8_STAGED2(src) ASR(src, SCALE8_STAGED2_BITS)

#define SBC_FIXED_0(val) { val = 0; }
#define MUL(a, b)        ((a) * (b))
#if defined(__arm__) && (!defined(__thumb__) || defined(__thumb2__))
#define MULA(a, b, res) ({				\
		int tmp = res;			\
		__asm__(				\
			"mla %0, %2, %3, %0"		\
			: "=&r" (tmp)			\
			: "0" (tmp), "r" (a), "r" (b));	\
		tmp; })
#else
#define MULA(a, b, res)  ((a) * (b) + (res))
#endif
