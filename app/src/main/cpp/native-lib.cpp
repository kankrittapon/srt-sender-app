#include <jni.h>
#include <string>
#include <android/log.h>
#include <memory>
#include "MpegTsMuxer.h"
#include "SrtTransport.h"

static std::unique_ptr<SrtTransport> srtTransport;
static std::unique_ptr<MpegTsMuxer> tsMuxer;

#define LOG_TAG "NativeLib"

// Callback from Muxer to send data
void onMuxerOutput(const uint8_t* data, size_t size) {
    if (srtTransport) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "onMuxerOutput: Sending %zu bytes via SRT", size);
        srtTransport->send(data, (int)size);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "onMuxerOutput: srtTransport is NULL!");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_srtsender_MainActivity_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jstring ip,
        jint port,
        jstring boatId) {
    
    const char *ipStr = env->GetStringUTFChars(ip, 0);
    const char *boatIdStr = env->GetStringUTFChars(boatId, 0);
    
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "nativeInit: Connecting to %s:%d with streamId %s", ipStr, port, boatIdStr);
    
    srtTransport = std::make_unique<SrtTransport>();
    bool success = srtTransport->init(ipStr, port, boatIdStr);
    
    env->ReleaseStringUTFChars(ip, ipStr);
    env->ReleaseStringUTFChars(boatId, boatIdStr);
    
    if (success) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "nativeInit: SRT connected, creating MpegTsMuxer");
        tsMuxer = std::make_unique<MpegTsMuxer>(onMuxerOutput);
        tsMuxer->reset();
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "nativeInit: SRT connection FAILED");
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_srtsender_MainActivity_nativeSendFrame(
        JNIEnv* env,
        jobject /* this */,
        jobject dataBuffer, 
        jint length, 
        jlong timestamp) {
            
    if (!tsMuxer) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "nativeSendFrame: tsMuxer is NULL!");
        return;
    }
    
    uint8_t* buf = (uint8_t*)env->GetDirectBufferAddress(dataBuffer);
    if (buf == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "nativeSendFrame: GetDirectBufferAddress returned NULL!");
        return;
    }
    
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "nativeSendFrame: Encoding frame: %d bytes, ts: %lld", length, (long long)timestamp);
    tsMuxer->encode(buf, length, (uint64_t)timestamp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_srtsender_MainActivity_nativeRelease(
        JNIEnv* env,
        jobject /* this */) {
            
    tsMuxer.reset();
    if (srtTransport) {
        srtTransport->release();
    }
    srtTransport.reset();
}
