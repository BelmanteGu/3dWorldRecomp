// JNI side of the Android app's game import (libm3dwimport.so): org.wwhdrecomp.wwhd.GameImportNative.
// A small library of its own, so the import screen and its service run without loading the game.
#include "game_import.h"

#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

namespace {
std::atomic<bool> g_cancel{false};

std::string text(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* utf = env->GetStringUTFChars(s, nullptr);
    std::string out = utf ? utf : "";
    if (utf) env->ReleaseStringUTFChars(s, utf);
    return out;
}

std::vector<std::string> blobs(JNIEnv* env, jobjectArray files) {
    std::vector<std::string> out;
    if (!files) return out;
    for (jsize i = 0, n = env->GetArrayLength(files); i < n; ++i) {
        auto b = static_cast<jbyteArray>(env->GetObjectArrayElement(files, i));
        if (!b) continue;
        std::string s(static_cast<size_t>(env->GetArrayLength(b)), '\0');
        env->GetByteArrayRegion(b, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte*>(s.data()));
        out.push_back(std::move(s));
        env->DeleteLocalRef(b);
    }
    return out;
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray a) {
    std::vector<std::string> out;
    if (!a) return out;
    for (jsize i = 0, n = env->GetArrayLength(a); i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(a, i));
        out.push_back(text(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

// {code, message, format, title id, files, bytes}
jobjectArray result(JNIEnv* env, const game_import::Result& r) {
    const std::string parts[] = {std::to_string(r.code), r.message, r.format, r.title_id, std::to_string(r.files),
                                 std::to_string(r.bytes)};
    jobjectArray out = env->NewObjectArray(6, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 6; ++i) {
        jstring s = env->NewStringUTF(parts[i].c_str());
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

void scrub(std::vector<std::string>& keys) {  // the key files' contents do not outlive the call
    for (auto& k : keys) std::fill(k.begin(), k.end(), '\0');
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wwhdrecomp_wwhd_GameImportNative_inspect(
        JNIEnv* env, jclass, jstring path, jobjectArray key_files, jstring want) {
    auto keys = blobs(env, key_files);
    auto r = game_import::inspect(text(env, path), keys, text(env, want));
    scrub(keys);
    return result(env, r);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wwhdrecomp_wwhd_GameImportNative_extract(
        JNIEnv* env, jclass, jstring path, jobjectArray key_files, jstring want, jstring out_dir, jobjectArray only,
        jboolean verify, jobject progress) {
    auto keys = blobs(env, key_files);
    jmethodID update = nullptr;
    if (progress) update = env->GetMethodID(env->GetObjectClass(progress), "update", "(IJJ)V");
    auto report = [&](int phase, uint64_t done, uint64_t total) {
        if (!update) return;
        env->CallVoidMethod(progress, update, jint(phase), jlong(done), jlong(total));
        if (env->ExceptionCheck()) {  // a Java exception in the callback: stop as if cancelled
            env->ExceptionClear();
            g_cancel = true;
        }
    };
    auto r = game_import::extract(text(env, path), keys, text(env, want), text(env, out_dir), strings(env, only),
                                  verify, report, g_cancel);
    scrub(keys);
    return result(env, r);
}

extern "C" JNIEXPORT void JNICALL Java_org_wwhdrecomp_wwhd_GameImportNative_setCancelled(JNIEnv*, jclass, jboolean c) {
    g_cancel = c;
}
