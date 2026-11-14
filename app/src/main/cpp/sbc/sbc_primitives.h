/*
 * Bluetooth low-complexity, subband codec (SBC) library
 * Copyright (C) 2004-2010  Marcel Holtmann <marcel@holtmann.org>
 * Copyright (C) 2004-2005  Henryk Ploetz <henryk@ploetzli.ch>
 * Copyright (C) 2005-2006  Brad Midgley <bmidgley@xmission.com>
 * Copyright (C) 2008-2010  Nokia Corporation
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#ifndef __SBC_PRIMITIVES_H
#define __SBC_PRIMITIVES_H

#define SCALE_OUT_BITS 15
#define SBC_X_BUFFER_SIZE 328

#ifdef __GNUC__
#define SBC_ALWAYS_INLINE inline __attribute__((always_inline))
#else
#define SBC_ALWAYS_INLINE inline
#endif

struct sbc_encoder_state {
	int position;
	/* Number of consecutive blocks handled by the encoder */
	uint8_t increment;
	int16_t SBC_ALIGNED X[2][SBC_X_BUFFER_SIZE];
	/* Polyphase analysis filter for 4 subbands configuration,
	 * it handles "increment" blocks at once */
	void (*sbc_analyze_4s)(struct sbc_encoder_state *state,
			int16_t *x, int32_t *out, int out_stride);
	/* Polyphase analysis filter for 8 subbands configuration,
	 * it handles "increment" blocks at once */
	void (*sbc_analyze_8s)(struct sbc_encoder_state *state,
			int16_t *x, int32_t *out, int out_stride);
	/* Process input data (deinterleave, endian conversion, reordering),
	 * depending on the number of subbands and input data byte order */
	int (*sbc_enc_process_input_4s_le)(int position,
			const uint8_t *pcm, int16_t X[2][SBC_X_BUFFER_SIZE],
			int nsamples, int nchannels);
	int (*sbc_enc_process_input_4s_be)(int position,
			const uint8_t *pcm, int16_t X[2][SBC_X_BUFFER_SIZE],
			int nsamples, int nchannels);
	int (*sbc_enc_process_input_8s_le)(int position,
			const uint8_t *pcm, int16_t X[2][SBC_X_BUFFER_SIZE],
			int nsamples, int nchannels);
	int (*sbc_enc_process_input_8s_be)(int position,
			const uint8_t *pcm, int16_t X[2][SBC_X_BUFFER_SIZE],
			int nsamples, int nchannels);
	/* Scale factors calculation */
	void (*sbc_calc_scalefactors)(int32_t sb_sample_f[16][2][8],
			uint32_t scale_factor[2][8],
			int blocks, int channels, int subbands);
	/* Scale factors calculation with joint stereo support */
	int (*sbc_calc_scalefactors_j)(int32_t sb_sample_f[16][2][8],
			uint32_t scale_factor[2][8],
			int blocks, int subbands);
	const char *implementation_info;
};

/*
 * Initialize pointers to the functions which are the basic "building bricks"
 * of SBC codec. Best implementation is selected based on target CPU
 * capabilities.
 */
void sbc_init_primitives(struct sbc_encoder_state *encoder_state);

#endif
