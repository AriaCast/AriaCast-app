/*
 * JNI wrapper around BlueZ libsbc: decodes the SBC frames forwarded by the AriaCompanion BT
 * board (docs/ariacompanion-bt.md) into interleaved 16-bit little-endian PCM.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "sbc.h"

#define SBC_SYNCWORD 0x9C

JNIEXPORT jlong JNICALL
Java_com_aria_ariacast_companion_SbcDecoder_nativeCreate(JNIEnv *env, jclass clazz) {
    sbc_t *sbc = calloc(1, sizeof(sbc_t));
    if (sbc == NULL) return 0;
    if (sbc_init(sbc, 0) < 0) { free(sbc); return 0; }
    sbc->endian = SBC_LE;
    return (jlong)(intptr_t)sbc;
}

/*
 * Decodes every SBC frame in in[offset, offset+length) into out. Bytes that are not the start
 * of a valid frame (an A2DP media header, a damaged frame) are skipped one at a time.
 * Returns the number of PCM bytes written.
 */
JNIEXPORT jint JNICALL
Java_com_aria_ariacast_companion_SbcDecoder_nativeDecode(JNIEnv *env, jclass clazz, jlong handle,
                                                        jbyteArray in, jint offset, jint length, jbyteArray out) {
    sbc_t *sbc = (sbc_t *)(intptr_t)handle;
    if (sbc == NULL || length <= 0) return 0;
    jbyte *src = (*env)->GetByteArrayElements(env, in, NULL);
    jbyte *dst = (*env)->GetByteArrayElements(env, out, NULL);
    jsize cap = (*env)->GetArrayLength(env, out);
    const uint8_t *p = (const uint8_t *)src + offset;
    size_t pos = 0, len = (size_t)length, w = 0;
    while (pos < len && w < (size_t)cap) {
        if (p[pos] != SBC_SYNCWORD) { pos++; continue; }
        size_t written = 0;
        ssize_t used = sbc_decode(sbc, p + pos, len - pos, (uint8_t *)dst + w, (size_t)cap - w, &written);
        if (used <= 0) { pos++; continue; }   /* not a frame after all, or not enough room */
        pos += (size_t)used;
        w += written;
    }
    (*env)->ReleaseByteArrayElements(env, in, src, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, dst, 0);
    return (jint)w;
}

/* Channels of the last decoded frame: 1 for mono, 2 otherwise. */
JNIEXPORT jint JNICALL
Java_com_aria_ariacast_companion_SbcDecoder_nativeChannels(JNIEnv *env, jclass clazz, jlong handle) {
    sbc_t *sbc = (sbc_t *)(intptr_t)handle;
    return (sbc != NULL && sbc->mode == SBC_MODE_MONO) ? 1 : 2;
}

/* Sample rate of the last decoded frame in Hz. */
JNIEXPORT jint JNICALL
Java_com_aria_ariacast_companion_SbcDecoder_nativeSampleRate(JNIEnv *env, jclass clazz, jlong handle) {
    sbc_t *sbc = (sbc_t *)(intptr_t)handle;
    if (sbc == NULL) return 44100;
    switch (sbc->frequency) {
    case SBC_FREQ_16000: return 16000;
    case SBC_FREQ_32000: return 32000;
    case SBC_FREQ_48000: return 48000;
    default: return 44100;
    }
}

JNIEXPORT void JNICALL
Java_com_aria_ariacast_companion_SbcDecoder_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    sbc_t *sbc = (sbc_t *)(intptr_t)handle;
    if (sbc == NULL) return;
    sbc_finish(sbc);
    free(sbc);
}
