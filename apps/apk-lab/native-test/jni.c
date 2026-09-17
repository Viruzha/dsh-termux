#include <jni.h>
#include <string.h>

JNIEXPORT jstring JNICALL
Java_com_example_apklab_MainActivity_nativeTag(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, "native aarch64 lib loaded");
}
