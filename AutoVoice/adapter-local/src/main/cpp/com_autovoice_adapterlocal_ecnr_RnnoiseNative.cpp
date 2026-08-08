/*
 * JNI 桥：com.autovoice.adapterlocal.ecnr.RnnoiseNative
 *
 * Kotlin `object` 的方法编译为静态方法，因此 JNI 第二参数是 jclass。
 * rnnoise 库来自 xiph/rnnoise v0.2（src/main/cpp/ 下的 C 源文件，
 * 含由 rnnoise_data-0b50c45.tar.gz 生成的 rnnoise_data.c 内嵌权重）。
 */
#include <jni.h>
#include <stdlib.h>
#include "rnnoise.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_com_autovoice_adapterlocal_ecnr_RnnoiseNative_create(JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    DenoiseState *st = rnnoise_create(NULL);
    return (jlong) st;
}

JNIEXPORT void JNICALL
Java_com_autovoice_adapterlocal_ecnr_RnnoiseNative_destroy(JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    rnnoise_destroy((DenoiseState *) handle);
}

JNIEXPORT jshortArray JNICALL
Java_com_autovoice_adapterlocal_ecnr_RnnoiseNative_processFrame(JNIEnv *env, jclass clazz, jlong handle, jshortArray frame)
{
    (void) clazz;
    jsize len = env->GetArrayLength(frame);
    DenoiseState *st = (DenoiseState *) handle;

    jshort *in = env->GetShortArrayElements(frame, NULL);
    float *x = (float *) malloc(len * sizeof(float));
    if (x == NULL) {
        env->ReleaseShortArrayElements(frame, in, JNI_ABORT);
        return NULL;
    }
    for (jsize i = 0; i < len; i++) x[i] = (float) in[i] / 32768.0f;

    /* 就地降噪；返回值是语音概率，本任务暂不暴露 */
    (void) rnnoise_process_frame(st, x, x);

    jshortArray out = env->NewShortArray(len);
    if (out != NULL) {
        jshort *buf = (jshort *) malloc(len * sizeof(jshort));
        if (buf != NULL) {
            for (jsize i = 0; i < len; i++) buf[i] = (jshort) (x[i] * 32767.0f);
            env->SetShortArrayRegion(out, 0, len, buf);
            free(buf);
        }
    }

    free(x);
    env->ReleaseShortArrayElements(frame, in, JNI_ABORT);
    return out;
}

#ifdef __cplusplus
}
#endif
