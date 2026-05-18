#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define ALAC_HEADER_LEN 3
#define RAOP_SAMPLES_PER_PACKET 352

struct AlacEncoderWrapper {
    uint32_t sampleRate;
    uint32_t channels;
    uint32_t frameSize;
    uint8_t *outputBuffer;
    int outputSize;
};

static void
alac_write_bits(uint8_t **p, uint8_t val, int blen, int *bpos)
{
    int lb, rb, bd;
    lb = 7 - *bpos + 1;
    rb = lb - blen;

    if (rb >= 0) {
        bd = (uint8_t)(val << rb);
        if (*bpos == 0)
            **p = (uint8_t)bd;
        else
            **p |= (uint8_t)bd;

        if (rb == 0) {
            *p += 1;
            *bpos = 0;
        } else {
            *bpos += blen;
        }
    } else {
        bd = (uint8_t)(val >> -rb);
        **p |= (uint8_t)bd;

        *p += 1;
        **p = (uint8_t)(val << (8 + rb));
        *bpos = -rb;
    }
}

static int
alac_encode_uncompressed(uint8_t *dst, uint8_t *raw, int len)
{
    uint8_t *maxraw = raw + len;
    int bpos = 0;

    alac_write_bits(&dst, 1, 3, &bpos);
    alac_write_bits(&dst, 0, 4, &bpos);
    alac_write_bits(&dst, 0, 8, &bpos);
    alac_write_bits(&dst, 0, 4, &bpos);
    alac_write_bits(&dst, 0, 1, &bpos);

    alac_write_bits(&dst, 0, 2, &bpos);
    alac_write_bits(&dst, 1, 1, &bpos);

    for (; raw < maxraw; raw += 4) {
        alac_write_bits(&dst, *(raw + 1), 8, &bpos);
        alac_write_bits(&dst, *raw, 8, &bpos);
        alac_write_bits(&dst, *(raw + 3), 8, &bpos);
        alac_write_bits(&dst, *(raw + 2), 8, &bpos);
    }

    alac_write_bits(&dst, 7, 3, &bpos);

    return ALAC_HEADER_LEN + len + 1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aria_ariacast_raop_NativeAlac_createEncoder(
        JNIEnv *, jobject, jint sampleRate, jint channels, jint frameSize) {
    auto *wrapper = new AlacEncoderWrapper();
    wrapper->sampleRate = (uint32_t)sampleRate;
    wrapper->channels = (uint32_t)channels;
    wrapper->frameSize = (uint32_t)frameSize;
    wrapper->outputSize = ALAC_HEADER_LEN + (int)(frameSize * channels * 2) + 1;
    wrapper->outputBuffer = (uint8_t *)malloc((size_t)wrapper->outputSize);
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aria_ariacast_raop_NativeAlac_encode(
        JNIEnv *env, jobject, jlong handle, jbyteArray pcmBytes, jint pcmLen, jbyteArray outBuffer) {
    auto *wrapper = reinterpret_cast<AlacEncoderWrapper *>(handle);
    if (!wrapper) return 0;

    jbyte *pcm = env->GetByteArrayElements(pcmBytes, nullptr);
    if (!pcm) return 0;

    int encoded = alac_encode_uncompressed(
        wrapper->outputBuffer,
        reinterpret_cast<uint8_t *>(pcm),
        pcmLen
    );

    env->ReleaseByteArrayElements(pcmBytes, pcm, JNI_ABORT);

    if (encoded > 0 && encoded <= wrapper->outputSize) {
        env->SetByteArrayRegion(outBuffer, 0, encoded,
            reinterpret_cast<const jbyte *>(wrapper->outputBuffer));
    }

    return encoded;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aria_ariacast_raop_NativeAlac_destroyEncoder(JNIEnv *, jobject, jlong handle) {
    auto *wrapper = reinterpret_cast<AlacEncoderWrapper *>(handle);
    if (wrapper) {
        free(wrapper->outputBuffer);
        delete wrapper;
    }
}
