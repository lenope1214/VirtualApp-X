#include <dlfcn.h>
#include <jni.h>

/* Calls the real dlopen() so the sandbox's exported dlopen hook is exercised.
 * Returns "ok" on success, otherwise the dlerror() text. */
JNIEXPORT jstring JNICALL
Java_io_vax_hello_HelloActivity_nativeDlopen(JNIEnv *env, jclass clazz, jstring jpath) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    void *handle = dlopen(path, RTLD_NOW);
    const char *res = handle ? "ok" : dlerror();
    jstring out = (*env)->NewStringUTF(env, res ? res : "unknown");
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return out;
}
