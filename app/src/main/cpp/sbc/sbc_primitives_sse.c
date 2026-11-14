/*
 * Bluetooth low-complexity, subband codec (SBC) library
 * Copyright (C) 2020  Intel Corporation
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#include <stdint.h>
#include <limits.h>
#include "sbc.h"
#include "sbc_math.h"
#include "sbc_tables.h"

#include "sbc_primitives_sse.h"

/*
 * SSE optimizations
 */

#ifdef SBC_BUILD_WITH_SSE_SUPPORT

static inline void sbc_analyze_four_sse(const int16_t *in, int32_t *out,
					const FIXED_T *consts)
{
	static const SBC_ALIGNED int32_t round_c[4] = {
		1 << (SBC_PROTO_FIXED4_SCALE - 1),
		1 << (SBC_PROTO_FIXED4_SCALE - 1),
		1 << (SBC_PROTO_FIXED4_SCALE - 1),
		1 << (SBC_PROTO_FIXED4_SCALE - 1),
	};
	__asm__ volatile (
		"movdqu      (%0), %%xmm0\n"
		"pmaddwd     (%1), %%xmm0\n"
		"paddd       (%2), %%xmm0\n"
		"\n"
		"movdqu    16(%0), %%xmm1\n"
		"pmaddwd   16(%1), %%xmm1\n"
		"paddd     %%xmm1, %%xmm0\n"
		"\n"
		"movdqu    32(%0), %%xmm1\n"
		"pmaddwd   32(%1), %%xmm1\n"
		"paddd     %%xmm1, %%xmm0\n"
		"\n"
		"movdqu    48(%0), %%xmm1\n"
		"pmaddwd   48(%1), %%xmm1\n"
		"paddd     %%xmm1, %%xmm0\n"
		"\n"
		"movdqu    64(%0), %%xmm1\n"
		"pmaddwd   64(%1), %%xmm1\n"
		"paddd     %%xmm1, %%xmm0\n"
		"\n"
		"psrad         %4, %%xmm0\n"
		"\n"
		"movdqa    %%xmm0, %%xmm1\n"
		"punpckhqdq %%xmm1, %%xmm1\n"
		"movdq2q   %%xmm0, %%mm0\n"
		"movdq2q   %%xmm1, %%mm1\n"
		"\n"
		"packssdw   %%mm0, %%mm0\n"
		"packssdw   %%mm1, %%mm1\n"
		"\n"
		"movq       %%mm0, %%mm2\n"
		"pmaddwd   80(%1), %%mm0\n"
		"pmaddwd   88(%1), %%mm2\n"
		"\n"
		"movq       %%mm1, %%mm3\n"
		"pmaddwd   96(%1), %%mm1\n"
		"pmaddwd  104(%1), %%mm3\n"
		"paddd      %%mm1, %%mm0\n"
		"paddd      %%mm3, %%mm2\n"
		"\n"
		"movq       %%mm0, (%3)\n"
		"movq       %%mm2, 8(%3)\n"
		:
		: "r" (in), "r" (consts), "r" (&round_c), "r" (out),
			"i" (SBC_PROTO_FIXED4_SCALE)
		: "cc", "memory");
}

static inline void sbc_analyze_eight_sse(const int16_t *in, int32_t *out,
							const FIXED_T *consts)
{
	static const SBC_ALIGNED int32_t round_c[4] = {
		1 << (SBC_PROTO_FIXED8_SCALE - 1),
		1 << (SBC_PROTO_FIXED8_SCALE - 1),
		1 << (SBC_PROTO_FIXED8_SCALE - 1),
		1 << (SBC_PROTO_FIXED8_SCALE - 1),
	};
	__asm__ volatile (
		"movdqu      (%0), %%xmm0\n"
		"movdqu    16(%0), %%xmm1\n"
		"pmaddwd     (%1), %%xmm0\n"
		"pmaddwd   16(%1), %%xmm1\n"
		"paddd       (%2), %%xmm0\n"
		"paddd       (%2), %%xmm1\n"
		"\n"
		"movdqu    32(%0), %%xmm2\n"
		"movdqu    48(%0), %%xmm3\n"
		"pmaddwd   32(%1), %%xmm2\n"
		"pmaddwd   48(%1), %%xmm3\n"
		"paddd     %%xmm2, %%xmm0\n"
		"paddd     %%xmm3, %%xmm1\n"
		"\n"
		"movdqu    64(%0), %%xmm2\n"
		"movdqu    80(%0), %%xmm3\n"
		"pmaddwd   64(%1), %%xmm2\n"
		"pmaddwd   80(%1), %%xmm3\n"
		"paddd     %%xmm2, %%xmm0\n"
		"paddd     %%xmm3, %%xmm1\n"
		"\n"
		"movdqu    96(%0), %%xmm2\n"
		"movdqu   112(%0), %%xmm3\n"
		"pmaddwd   96(%1), %%xmm2\n"
		"pmaddwd  112(%1), %%xmm3\n"
		"paddd     %%xmm2, %%xmm0\n"
		"paddd     %%xmm3, %%xmm1\n"
		"\n"
		"movdqu    128(%0), %%xmm2\n"
		"movdqu    144(%0), %%xmm3\n"
		"pmaddwd   128(%1), %%xmm2\n"
		"pmaddwd   144(%1), %%xmm3\n"
		"paddd      %%xmm2, %%xmm0\n"
		"paddd      %%xmm3, %%xmm1\n"
		"\n"
		"psrad         %4, %%xmm0\n"
		"psrad         %4, %%xmm1\n"
		"\n"
		"movdqa     %%xmm0, %%xmm2\n"
		"movdqa     %%xmm1, %%xmm3\n"
		"punpckhqdq %%xmm2, %%xmm2\n"
		"punpckhqdq %%xmm3, %%xmm3\n"
		"movdq2q    %%xmm0, %%mm0\n"
		"movdq2q    %%xmm2, %%mm1\n"
		"movdq2q    %%xmm1, %%mm2\n"
		"movdq2q    %%xmm3, %%mm3\n"
		"\n"
		"packssdw   %%mm0, %%mm0\n"
		"packssdw   %%mm1, %%mm1\n"
		"packssdw   %%mm2, %%mm2\n"
		"packssdw   %%mm3, %%mm3\n"
		"\n"
		"movq       %%mm0, %%mm4\n"
		"movq       %%mm0, %%mm5\n"
		"pmaddwd  160(%1), %%mm4\n"
		"pmaddwd  168(%1), %%mm5\n"
		"\n"
		"movq       %%mm1, %%mm6\n"
		"movq       %%mm1, %%mm7\n"
		"pmaddwd  192(%1), %%mm6\n"
		"pmaddwd  200(%1), %%mm7\n"
		"paddd      %%mm6, %%mm4\n"
		"paddd      %%mm7, %%mm5\n"
		"\n"
		"movq       %%mm2, %%mm6\n"
		"movq       %%mm2, %%mm7\n"
		"pmaddwd  224(%1), %%mm6\n"
		"pmaddwd  232(%1), %%mm7\n"
		"paddd      %%mm6, %%mm4\n"
		"paddd      %%mm7, %%mm5\n"
		"\n"
		"movq       %%mm3, %%mm6\n"
		"movq       %%mm3, %%mm7\n"
		"pmaddwd  256(%1), %%mm6\n"
		"pmaddwd  264(%1), %%mm7\n"
		"paddd      %%mm6, %%mm4\n"
		"paddd      %%mm7, %%mm5\n"
		"\n"
		"movq       %%mm4, (%3)\n"
		"movq       %%mm5, 8(%3)\n"
		"\n"
		"movq       %%mm0, %%mm5\n"
		"pmaddwd  176(%1), %%mm0\n"
		"pmaddwd  184(%1), %%mm5\n"
		"\n"
		"movq       %%mm1, %%mm7\n"
		"pmaddwd  208(%1), %%mm1\n"
		"pmaddwd  216(%1), %%mm7\n"
		"paddd      %%mm1, %%mm0\n"
		"paddd      %%mm7, %%mm5\n"
		"\n"
		"movq       %%mm2, %%mm7\n"
		"pmaddwd  240(%1), %%mm2\n"
		"pmaddwd  248(%1), %%mm7\n"
		"paddd      %%mm2, %%mm0\n"
		"paddd      %%mm7, %%mm5\n"
		"\n"
		"movq       %%mm3, %%mm7\n"
		"pmaddwd  272(%1), %%mm3\n"
		"pmaddwd  280(%1), %%mm7\n"
		"paddd      %%mm3, %%mm0\n"
		"paddd      %%mm7, %%mm5\n"
		"\n"
		"movq       %%mm0, 16(%3)\n"
		"movq       %%mm5, 24(%3)\n"
		:
		: "r" (in), "r" (consts), "r" (&round_c), "r" (out),
			"i" (SBC_PROTO_FIXED8_SCALE)
		: "cc", "memory");
}

static inline void sbc_analyze_4b_4s_sse(struct sbc_encoder_state *state,
		int16_t *x, int32_t *out, int out_stride)
{
	/* Analyze blocks */
	sbc_analyze_four_sse(x + 12, out, analysis_consts_fixed4_simd_odd);
	out += out_stride;
	sbc_analyze_four_sse(x + 8, out, analysis_consts_fixed4_simd_even);
	out += out_stride;
	sbc_analyze_four_sse(x + 4, out, analysis_consts_fixed4_simd_odd);
	out += out_stride;
	sbc_analyze_four_sse(x + 0, out, analysis_consts_fixed4_simd_even);

	__asm__ volatile ("emms\n");
}

static inline void sbc_analyze_4b_8s_sse(struct sbc_encoder_state *state,
		int16_t *x, int32_t *out, int out_stride)
{
	/* Analyze blocks */
	sbc_analyze_eight_sse(x + 24, out, analysis_consts_fixed8_simd_odd);
	out += out_stride;
	sbc_analyze_eight_sse(x + 16, out, analysis_consts_fixed8_simd_even);
	out += out_stride;
	sbc_analyze_eight_sse(x + 8, out, analysis_consts_fixed8_simd_odd);
	out += out_stride;
	sbc_analyze_eight_sse(x + 0, out, analysis_consts_fixed8_simd_even);

	__asm__ volatile ("emms\n");
}

static inline void sbc_analyze_1b_8s_sse_even(struct sbc_encoder_state *state,
		int16_t *x, int32_t *out, int out_stride);

static inline void sbc_analyze_1b_8s_sse_odd(struct sbc_encoder_state *state,
		int16_t *x, int32_t *out, int out_stride)
{
	sbc_analyze_eight_sse(x, out, analysis_consts_fixed8_simd_odd);
	state->sbc_analyze_8s = sbc_analyze_1b_8s_sse_even;

	__asm__ volatile ("emms\n");
}

static inline void sbc_analyze_1b_8s_sse_even(struct sbc_encoder_state *state,
		int16_t *x, int32_t *out, int out_stride)
{
	sbc_analyze_eight_sse(x, out, analysis_consts_fixed8_simd_even);
	state->sbc_analyze_8s = sbc_analyze_1b_8s_sse_odd;

	__asm__ volatile ("emms\n");
}

static void sbc_calc_scalefactors_sse(
	int32_t sb_sample_f[16][2][8],
	uint32_t scale_factor[2][8],
	int blocks, int channels, int subbands)
{
	static const SBC_ALIGNED int32_t consts[2] = {
		1 << SCALE_OUT_BITS,
		1 << SCALE_OUT_BITS,
	};
	int ch, sb;
	intptr_t blk;
	for (ch = 0; ch < channels; ch++) {
		for (sb = 0; sb < subbands; sb += 2) {
			blk = (blocks - 1) * (((char *) &sb_sample_f[1][0][0] -
				(char *) &sb_sample_f[0][0][0]));
			__asm__ volatile (
				"movq         (%4), %%mm0\n"
			"1:\n"
				"movq     (%1, %0), %%mm1\n"
				"pxor        %%mm2, %%mm2\n"
				"pcmpgtd     %%mm2, %%mm1\n"
				"paddd    (%1, %0), %%mm1\n"
				"pcmpgtd     %%mm1, %%mm2\n"
				"pxor        %%mm2, %%mm1\n"

				"por         %%mm1, %%mm0\n"

				"sub            %2, %0\n"
				"jns            1b\n"

				"movd        %%mm0, %k0\n"
				"psrlq         $32, %%mm0\n"
				"bsrl          %k0, %k0\n"
				"subl           %5, %k0\n"
				"movl          %k0, (%3)\n"

				"movd        %%mm0, %k0\n"
				"bsrl          %k0, %k0\n"
				"subl           %5, %k0\n"
				"movl          %k0, 4(%3)\n"
			: "+r" (blk)
			: "r" (&sb_sample_f[0][ch][sb]),
				"i" ((char *) &sb_sample_f[1][0][0] -
					(char *) &sb_sample_f[0][0][0]),
				"r" (&scale_factor[ch][sb]),
				"r" (&consts),
				"i" (SCALE_OUT_BITS)
			: "cc", "memory");
		}
	}
	__asm__ volatile ("emms\n");
}

void sbc_init_primitives_sse(struct sbc_encoder_state *state)
{
	state->sbc_analyze_4s = sbc_analyze_4b_4s_sse;
	if (state->increment == 1)
		state->sbc_analyze_8s = sbc_analyze_1b_8s_sse_odd;
	else
		state->sbc_analyze_8s = sbc_analyze_4b_8s_sse;
	state->sbc_calc_scalefactors = sbc_calc_scalefactors_sse;
	state->implementation_info = "SSE";
}

#endif
