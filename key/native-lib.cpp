#include <jni.h>
#include <string>

extern "C" {
#include "aes.h"
}

#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CleanIPTV", __VA_ARGS__)

// SHA-256 từ Keystore thật của bạn (điền vào đây sau khi chạy get_signature.bat)
// Tạm thời để mã giả, khi chạy app thật hãy log ra và đổi lại
const char* EXPECTED_SIGNATURE = "2242FB7C962BE26B25B6A5E1A7FD5CED43A6D41ACB704FC1DA6EE04653340220";

extern "C" JNIEXPORT void JNICALL
Java_com_haidaklak_iptv_SecurityHelper_verifySignature(JNIEnv *env, jclass clazz, jobject context) {
    if (context == nullptr) return;

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManagerId = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jmethodID getPackageNameId = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");

    jobject packageManager = env->CallObjectMethod(context, getPackageManagerId);
    jstring packageName = (jstring) env->CallObjectMethod(context, getPackageNameId);

    jclass packageManagerClass = env->GetObjectClass(packageManager);
    jmethodID getPackageInfoId = env->GetMethodID(packageManagerClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");

    // GET_SIGNATURES = 64 (deprecated flag, but works), GET_SIGNING_CERTIFICATES = 134217728
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfoId, packageName, 64);
    if (packageInfo == nullptr) {
        LOGE("Could not get PackageInfo. Exiting.");
        exit(0); // SECURITY: dừng app nếu không verify được signature
    }

    jclass packageInfoClass = env->GetObjectClass(packageInfo);
    jfieldID signaturesFieldId = env->GetFieldID(packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signatures = (jobjectArray) env->GetObjectField(packageInfo, signaturesFieldId);

    if (signatures == nullptr || env->GetArrayLength(signatures) == 0) {
        LOGE("No signatures found. Exiting.");
        exit(0); // SECURITY: dừng app nếu APK không có signature
    }

    jobject signature = env->GetObjectArrayElement(signatures, 0);
    jclass signatureClass = env->GetObjectClass(signature);
    jmethodID toByteArrayId = env->GetMethodID(signatureClass, "toByteArray", "()[B");
    jbyteArray sigBytes = (jbyteArray) env->CallObjectMethod(signature, toByteArrayId);

    jclass mdClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstanceId = env->GetStaticMethodID(mdClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring sha256Str = env->NewStringUTF("SHA-256");
    jobject md = env->CallStaticObjectMethod(mdClass, getInstanceId, sha256Str);
    env->DeleteLocalRef(sha256Str);

    jmethodID digestId = env->GetMethodID(mdClass, "digest", "([B)[B");
    jbyteArray digestBytes = (jbyteArray) env->CallObjectMethod(md, digestId, sigBytes);

    jsize length = env->GetArrayLength(digestBytes);
    jbyte* bytes = env->GetByteArrayElements(digestBytes, nullptr);
    
    char hexStr[65];
    const char* hexChars = "0123456789ABCDEF";
    for(int i = 0; i < length; i++) {
        uint8_t b = bytes[i];
        hexStr[i * 2]     = hexChars[(b >> 4) & 0x0F];
        hexStr[i * 2 + 1] = hexChars[b & 0x0F];
    }
    hexStr[64] = '\0';
    env->ReleaseByteArrayElements(digestBytes, bytes, JNI_ABORT);

    if (strcmp(EXPECTED_SIGNATURE, hexStr) != 0) {
        LOGE("TAMPER DETECTED");
        exit(0); // SECURITY: APK bị repackage — dừng app ngay
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_haidaklak_iptv_SecurityHelper_getM3uUrl(JNIEnv *env, jclass clazz) {
    // Đã mã hóa XOR bằng key 0x5A
    uint8_t enc_url[] = {
        0x32,0x2E,0x2E,0x2A,0x29,0x60,0x75,0x75,0x28,0x3B,0x2D,0x74,0x3D,0x33,
        0x2E,0x32,0x2F,0x38,0x2F,0x29,0x3F,0x28,0x39,0x35,0x34,0x2E,0x3F,0x34,
        0x2E,0x74,0x39,0x35,0x37,0x75,0x32,0x3B,0x33,0x77,0x39,0x28,0x23,0x2A,
        0x2E,0x35,0x68,0x6A,0x68,0x6C,0x75,0x36,0x33,0x29,0x2E,0x37,0x23,0x2E,
        0x2C,0x75,0x37,0x3B,0x33,0x34,0x75,0x2C,0x33,0x2A,0x05,0x3C,0x2F,0x36,0x36
    };
    
    size_t url_len = sizeof(enc_url);
    char dec_url[url_len + 1];
    
    // Giải mã XOR tại runtime
    for (size_t i = 0; i < url_len; i++) {
        dec_url[i] = enc_url[i] ^ 0x5A;
    }
    dec_url[url_len] = '\0';
    
    return env->NewStringUTF(dec_url);
}