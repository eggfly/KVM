#include <jni.h>
#include <string>

#pragma clang diagnostic push
#pragma ide diagnostic ignored "OCUnusedGlobalDeclarationInspection"
extern "C" JNIEXPORT jstring JNICALL
Java_kvm_demo_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    //  main(2, arg);
    // main2();
    return env->NewStringUTF(hello.c_str());
}
#pragma clang diagnostic pop
