#include <jni.h>
#include <string>

#include "include/dexinfo.h"
#include "dalvik/libdex/DexFile.h"

extern "C" JNIEXPORT jstring JNICALL
Java_eggfly_kvm_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    char *path = const_cast<char *>("/data/data/eggfly.kvm/classes.dex");
    char *arg[2] = {path, path};
    main(2, arg);
    // main2();
    return env->NewStringUTF(hello.c_str());
}
