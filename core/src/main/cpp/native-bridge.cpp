#include <jni.h>
#include <android/log.h>
#include <string.h>

#define  LOG_TAG    "NativeBridge"
// #define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
// #define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)


inline jobject
callNonVirtualMethod(JNIEnv *env, jobject obj, jchar returnType, jclass classOfMethod,
                     jmethodID method, jvalue *argsArray);

void parseMethodTypes(const char *str, char *types);

template<typename ...T>
inline jobject construct(JNIEnv *env, const char *className, const char *sig, T... params) {
    jclass cls = env->FindClass(className);
    if (!cls)
        return NULL;

    jmethodID ctor = env->GetMethodID(cls, "<init>", sig);
    if (!ctor) {
        env->DeleteLocalRef(cls);
        return NULL;
    }

    jobject result = env->NewObject(cls, ctor, params...);
    env->DeleteLocalRef(cls);
    return result;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_kvm_core_NativeBridge_callNonVirtualMethod(JNIEnv *env, jclass __unused type,
                                                          jobject obj,
                                                          jstring classNameOfMethod,
                                                          jstring methodName,
                                                          jstring methodSignature, jchar returnType,
                                                          jobjectArray invokeArgs) {
    LOGD("callNonVirtualMethod");
    const char *classNameOfMethodStr = env->GetStringUTFChars(classNameOfMethod, 0);
    const char *methodNameStr = env->GetStringUTFChars(methodName, 0);
    const char *methodSignatureStr = env->GetStringUTFChars(methodSignature, 0);

    jclass classOfMethod = env->FindClass(classNameOfMethodStr);
    jmethodID method = env->GetMethodID(classOfMethod, methodNameStr, methodSignatureStr);
    int argsCount = env->GetArrayLength(invokeArgs);
    jvalue argsArray[argsCount];

    char myTypes[argsCount + 1];
    myTypes[argsCount] = '\0';
    parseMethodTypes(methodSignatureStr, myTypes);
    jobject paramRefsForDelete[argsCount];

    for (int i = 0; i < argsCount; i++) {
        jobject item = env->GetObjectArrayElement(invokeArgs, i);
        paramRefsForDelete[i] = item;
        jvalue value = {};
        char myType = myTypes[i];
        if (myType == 'Z') {
            jclass cls = env->FindClass("java/lang/Boolean");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "booleanValue", "()Z");
                value.z = env->CallBooleanMethod(item, methodId);
            }
        } else if (myType == 'B') {
            jclass cls = env->FindClass("java/lang/Byte");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "byteValue", "()B");
                value.b = env->CallByteMethod(item, methodId);
            }
        } else if (myType == 'C') {
            jclass cls = env->FindClass("java/lang/Character");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "charValue", "()C");
                value.c = env->CallCharMethod(item, methodId);
            }
        } else if (myType == 'S') {
            jclass cls = env->FindClass("java/lang/Short");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "shortValue", "()S");
                value.s = env->CallShortMethod(item, methodId);
            }
        } else if (myType == 'I') {
            jclass cls = env->FindClass("java/lang/Integer");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "intValue", "()I");
                value.i = env->CallIntMethod(item, methodId);
            }
        } else if (myType == 'J') {
            jclass cls = env->FindClass("java/lang/Long");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "longValue", "()J");
                value.j = env->CallLongMethod(item, methodId);
            }
        } else if (myType == 'F') {
            jclass cls = env->FindClass("java/lang/Float");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "floatValue", "()F");
                value.f = env->CallFloatMethod(item, methodId);
            }
        } else if (myType == 'D') {
            jclass cls = env->FindClass("java/lang/Double");
            if (env->IsInstanceOf(item, cls)) {
                jmethodID methodId = env->GetMethodID(cls, "doubleValue", "()D");
                value.d = env->CallDoubleMethod(item, methodId);
            }
        } else {
            value.l = item;
        }
        argsArray[i] = value;
    }

    jobject returnObj = callNonVirtualMethod(env, obj, returnType, classOfMethod, method,
                                             argsArray);

    for (int i = 0; i < argsCount; i++) {
        env->DeleteLocalRef(paramRefsForDelete[i]);
    }
    env->DeleteLocalRef(classOfMethod);

    env->ReleaseStringUTFChars(classNameOfMethod, classNameOfMethodStr);
    env->ReleaseStringUTFChars(methodName, methodNameStr);
    env->ReleaseStringUTFChars(methodSignature, methodSignatureStr);
    return returnObj;
}

void parseMethodTypes(const char *methodSignatureStr, char *myTypes) {
    const char *param = methodSignatureStr + 1; // remove first '('
    const char *ptr = strstr(param, ")");
    size_t length = ptr - param;
    int pos = 0;
    bool inClass = false;
    for (int i = 0; i < length; i++) {
        char ch = param[i];
        switch (ch) {
            case 'L': // start mark of class
                inClass = true;
                break;
            case 'Z': // boolean
                if (!inClass) {
                    myTypes[pos++] = 'Z';
                }
                break;
            case 'B': // byte
                if (!inClass) {
                    myTypes[pos++] = 'B';
                }
                break;
            case 'C': // char
                if (!inClass) {
                    myTypes[pos++] = 'C';
                }
                break;
            case 'S': // short
                if (!inClass) {
                    myTypes[pos++] = 'S';
                }
                break;
            case 'I': // int
                if (!inClass) {
                    myTypes[pos++] = 'I';
                }
                break;
            case 'J': // long
                if (!inClass) {
                    myTypes[pos++] = 'J';
                }
                break;
            case 'F': // float
                if (!inClass) {
                    myTypes[pos++] = 'F';
                }
                break;
            case 'D': // double
                if (!inClass) {
                    myTypes[pos++] = 'D';
                }
                break;
            case ';': // end mark of class
                inClass = false;
                myTypes[pos++] = 'L';
                break;
            default:
                break;
        }
    }
}

inline jobject callNonVirtualMethod(JNIEnv *env, jobject obj, jchar returnType,
                                    jclass classOfMethod, jmethodID method,
                                    jvalue *argsArray) {
    jobject returnObj = NULL;
    switch (returnType) {
        case 'V': { // void
            env->CallNonvirtualVoidMethodA(obj, classOfMethod, method, argsArray);
            break;
        }
        case 'Z': { // boolean
            jboolean r = env->CallNonvirtualBooleanMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Boolean", "(Z)V", r);
            break;
        }
        case 'B': { // byte
            jbyte r = env->CallNonvirtualByteMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Byte", "(B)V", r);
            break;
        }
        case 'C': { // char
            jbyte r = env->CallNonvirtualByteMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Character", "(C)V", r);
            break;
        }
        case 'S': { // short
            jshort r = env->CallNonvirtualShortMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Short", "(S)V", r);
            break;
        }
        case 'I': { // int
            jint r = env->CallNonvirtualIntMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Integer", "(I)V", r);
            break;
        }
        case 'J': { // long
            jlong r = env->CallNonvirtualLongMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Long", "(J)V", r);
            break;
        }
        case 'F': { // float
            jfloat r = env->CallNonvirtualFloatMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Float", "(F)V", r);
            break;
        }
        case 'D': { // double
            jdouble r = env->CallNonvirtualDoubleMethodA(obj, classOfMethod, method, argsArray);
            returnObj = construct(env, "java/lang/Double", "(D)V", r);
            break;
        }
        case '[':   // array
        case 'L': { // object
            returnObj = env->CallNonvirtualObjectMethodA(obj, classOfMethod, method, argsArray);
            break;
        }
        default:
            break;
    }
    return returnObj;
}
