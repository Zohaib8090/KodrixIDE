#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>

JNIEXPORT jint JNICALL
Java_com_kodrix_zohaib_platform_PlatformTerminal_nativeCreatePty(
    JNIEnv *env, jobject thiz,
    jstring shell, jstring homeDir, jint rows, jint cols)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);

    if (pid == 0) {
        const char *home = (*env)->GetStringUTFChars(env, homeDir, NULL);
        const char *sh = (*env)->GetStringUTFChars(env, shell, NULL);

        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", home, 1);
        setenv("SHELL", sh, 1);

        chdir(home);

        execlp(sh, sh, "--login", NULL);
        _exit(1);
    }

    if (masterFd != -1) {
        // Set non-blocking mode
        int flags = fcntl(masterFd, F_GETFL, 0);
        fcntl(masterFd, F_SETFL, flags | O_NONBLOCK);
    }

    return (jint)masterFd;
}

JNIEXPORT void JNICALL
Java_com_kodrix_zohaib_platform_PlatformTerminal_nativeSetWindowSize(
    JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_kodrix_zohaib_platform_PlatformTerminal_nativeRead(
    JNIEnv *env, jobject thiz, jint fd, jbyteArray buffer, jint len)
{
    if (fd < 0) return -1;

    char tmp[4096];
    size_t toRead = (len < 4096) ? (size_t)len : 4096;

    // Use select to check if data is available
    fd_set readfds;
    struct timeval timeout;
    FD_ZERO(&readfds);
    FD_SET(fd, &readfds);
    timeout.tv_sec = 0;
    timeout.tv_usec = 50000; // 50ms timeout

    int ready = select(fd + 1, &readfds, NULL, NULL, &timeout);
    if (ready <= 0) {
        return 0; // No data available or timeout
    }

    ssize_t n = read(fd, tmp, toRead);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)n, (jbyte*)tmp);
    } else if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0; // No data available
        }
        return -1; // Error
    }
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_kodrix_zohaib_platform_PlatformTerminal_nativeWrite(
    JNIEnv *env, jobject thiz, jint fd, jbyteArray data, jint len)
{
    if (fd < 0) return -1;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    ssize_t n = write(fd, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_kodrix_zohaib_platform_PlatformTerminal_nativeClose(
    JNIEnv *env, jobject thiz, jint fd)
{
    if (fd >= 0) {
        close(fd);
    }
}
