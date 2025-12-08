#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "ggml.h"
#include "llama.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <iostream>
#include <csignal>
#include <unistd.h>
#include <android/log.h>

class AndroidLogBuf : public std::streambuf {
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        __android_log_print(ANDROID_LOG_INFO, "Llama", "%.*s", n, s);
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) {
            char c_as_char = static_cast<char>(c);
            __android_log_write(ANDROID_LOG_INFO, "Llama", &c_as_char);
        }
        return c;
    }
};

#define TAG "llama-android.cpp"
static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, data);
    else __android_log_print(ANDROID_LOG_DEFAULT, TAG, fmt, data);
}

extern "C" JNIEXPORT jint
JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaCpp_init(JNIEnv *env, jobject object) {

    // Redirect std::cerr to logcat
    AndroidLogBuf androidLogBuf;
    std::cerr.rdbuf(&androidLogBuf);

    llama_log_set(log_callback, NULL);
    llama_backend_init();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaCpp_systemInfo(JNIEnv *env, jobject object) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong
JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaModel_nativeLoadModel(
        JNIEnv *env,
        jobject thiz,
        jstring jpath,
        jstring jprefix,
        jstring jsuffix,
        jobjectArray janti_prompts) {

    // Struct to hold progress callback context
    struct CallbackContext {
        JNIEnv *env;
        jobject progressCallback;
    };

    auto* model = new LlamaModel();
    const char* utfModelPath = env->GetStringUTFChars(jpath, nullptr);

    // Note: prefix, suffix, anti_prompts are not used in the new implementation
    // (chat templates are handled automatically by llama.cpp)

    model->loadModel(utfModelPath,
                     -1, // n_gpu_layers (not used on Android CPU)
                     nullptr, // progress_callback (can be added later if needed)
                     nullptr  // progress_callback_user_data
                     );

    env->ReleaseStringUTFChars(jpath, utfModelPath);

    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaModel_nativeGetModelSize(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto* model = reinterpret_cast<LlamaModel*>(handle);
    if (!model) {
        return 0;
    }
    return model->getModelSize();
}

extern "C"
JNIEXPORT void JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaModel_nativeUnloadModel(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto* model = reinterpret_cast<LlamaModel*>(handle);
    if (model) {
        model->unloadModel();
        delete model;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaGenerationSession_nativeCreateSession(
        JNIEnv *env,
        jobject thiz,
        jlong model_handle) {

    auto* model = reinterpret_cast<LlamaModel*>(model_handle);
    if (!model) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Invalid model");
        return 0;
    }

    LlamaGenerationSession* session = model->createGenerationSession();
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaGenerationSession_nativeGenerate(
        JNIEnv *env,
        jobject thiz,
        jlong session_handle,
        jobject callback) {

    auto *session = reinterpret_cast<LlamaGenerationSession*>(session_handle);
    if (!session) {
        return -1;
    }

    jclass javaClass = env->GetObjectClass(callback);
    jmethodID onNewTokensMethodId = env->GetMethodID(javaClass, "onNewTokens", "([B)V");

    return session->generate(
            [env, onNewTokensMethodId, callback](const std::string &response) {
                const char *cStr = response.c_str();
                jsize len = strlen(cStr);
                jbyteArray result = env->NewByteArray(len);
                env->SetByteArrayRegion(result, 0, len, (jbyte *) cStr);
                env->CallVoidMethod(callback, onNewTokensMethodId, result);
                env->DeleteLocalRef(result);
            }
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaGenerationSession_nativeAddMessage(
        JNIEnv *env,
        jobject thiz,
        jlong session_handle,
        jstring message) {

    auto *session = reinterpret_cast<LlamaGenerationSession*>(session_handle);
    if (!session) {
        return;
    }

    const char* utfMessage = env->GetStringUTFChars(message, nullptr);
    session->addMessage(utfMessage);
    env->ReleaseStringUTFChars(message, utfMessage);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaGenerationSession_nativeGetReport(
        JNIEnv *env,
        jobject thiz,
        jlong session_handle) {

    auto *session = reinterpret_cast<LlamaGenerationSession*>(session_handle);
    if (!session) {
        return env->NewStringUTF("");
    }

    auto report = session->getReport();
    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_de_danoeh_antennapod_net_download_service_llama_LlamaGenerationSession_nativeDestroy(
        JNIEnv *env,
        jobject thiz,
        jlong session_handle) {

    auto *session = reinterpret_cast<LlamaGenerationSession*>(session_handle);
    if (session != nullptr) {
        delete session;
        __android_log_print(ANDROID_LOG_DEBUG, "Llama", "Destroy");
    }
}
