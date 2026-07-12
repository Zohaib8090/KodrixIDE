#include <jni.h>
#include <string>
#include <unistd.h>
#include <pty.h>
#include <sys/wait.h>
#include <android/log.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <string.h>
#include <git2.h>
#include <vector>
#include <stdarg.h>

#define TAG "GitBridge"

static int cred_acquire_cb(git_credential **out,
                            const char *url,
                            const char *username,
                            unsigned int allowed,
                            void *payload) {
    const char **creds = (const char **)payload;
    // creds[0] is username, creds[1] is token/password
    return git_credential_userpass_plaintext_new(out, creds[0], creds[1]);
}

static int cert_check_cb(git_cert *cert, int valid, const char *host, void *payload) {
    // 0 means success. We only return 0 if libgit2 (or the backend) marked the cert as valid.
    // Returning -1 rejects the certificate and prevents MITM attacks.
    return valid ? 0 : -1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kodrix_zohaib_bridge_GitBridge_cloneRepo(
        JNIEnv *env,
        jobject,
        jstring url,
        jstring localPath,
        jstring username,
        jstring token) {

    git_libgit2_init();

    const char *c_url = env->GetStringUTFChars(url, nullptr);
    const char *c_path = env->GetStringUTFChars(localPath, nullptr);
    const char *c_user = env->GetStringUTFChars(username, nullptr);
    const char *c_token = env->GetStringUTFChars(token, nullptr);

    const char *creds[2] = {c_user, c_token};

    git_clone_options opts = GIT_CLONE_OPTIONS_INIT;
    opts.fetch_opts.callbacks.credentials = cred_acquire_cb;
    opts.fetch_opts.callbacks.certificate_check = cert_check_cb;
    opts.fetch_opts.callbacks.payload = creds;

    git_repository *repo = nullptr;
    int error = git_clone(&repo, c_url, c_path, &opts);

    std::string result;
    if (error < 0) {
        const git_error *e = git_error_last();
        result = std::string("ERROR: ") + (e ? e->message : "unknown");
        __android_log_print(ANDROID_LOG_ERROR, TAG, "libgit2 clone failed: %s", result.c_str());
    } else {
        result = "SUCCESS";
        git_repository_free(repo);
    }

    env->ReleaseStringUTFChars(url, c_url);
    env->ReleaseStringUTFChars(localPath, c_path);
    env->ReleaseStringUTFChars(username, c_user);
    env->ReleaseStringUTFChars(token, c_token);

    git_libgit2_shutdown();
    return env->NewStringUTF(result.c_str());
}

#define LOG_TAG "Kodrix-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Hooking to bypass Android 10+ W^X for Git helpers
// These will be used when LD_PRELOADed into git or node
extern "C" {

typedef int (*execve_t)(const char *, char *const [], char *const []);
typedef int (*access_t)(const char *, int);
typedef int (*open_t)(const char *, int, ...);
typedef int (*stat_t)(const char *, struct stat *);
typedef int (*lstat_t)(const char *, struct stat *);
typedef int (*xstat_t)(int, const char *, struct stat *);

static execve_t real_execve = nullptr;
static access_t real_access = nullptr;
static open_t real_open = nullptr;
static stat_t real_stat = nullptr;
static lstat_t real_lstat = nullptr;
static xstat_t real_xstat = nullptr;
static xstat_t real_lxstat = nullptr;

__attribute__((constructor))
static void init() {
    LOGI("Native hooks library loaded!");
}

// Reads the active node version string from filesDir/active_node_version
static bool get_active_node_path(char* out_bin, size_t bin_size, char* out_lib, size_t lib_size) {
    const char* files_dir = getenv("APP_FILES_DIR");
    if (!files_dir) return false;

    char version_file[512];
    snprintf(version_file, sizeof(version_file), "%s/active_node_version", files_dir);

    FILE* fp = fopen(version_file, "r");
    if (!fp) return false;

    char version[64] = {0};
    fgets(version, sizeof(version), fp);
    fclose(fp);

    // Trim trailing newline
    size_t len = strlen(version);
    if (len > 0 && version[len-1] == '\n') version[len-1] = '\0';
    if (strlen(version) == 0) return false;

    snprintf(out_bin, bin_size, "%s/versions/node/%s/bin/node", files_dir, version);
    snprintf(out_lib, lib_size, "%s/versions/node/%s/lib", files_dir, version);

    // Check if the binary actually exists
    struct stat st;
    return stat(out_bin, &st) == 0;
}

static const char* resolve_helper(const char* path) {
    if (!path) return nullptr;
    if (strstr(path, "git-remote-https")) return "libgit_remote_http_bin.so";
    if (strstr(path, "git-remote-http")) return "libgit_remote_http_bin.so";
    if (strstr(path, "git-remote-ftp")) return "libgit_remote_ftp_bin.so";
    if (strstr(path, "git-remote-ftps")) return "libgit_remote_ftps_bin.so";

    // Git binary for subshells
    const char* last_slash = strrchr(path, '/');
    const char* basename = last_slash ? last_slash + 1 : path;
    if (strcmp(basename, "git") == 0) return "libgit_bin.so";

    return nullptr;
}

static const char* do_redirect(const char* pathname, char* buffer, size_t size) {
    if (!pathname) return nullptr;

    // 1. Redirect OpenSSL requests to our isolated versions
    if (strstr(pathname, "libcrypto.so") || strstr(pathname, "libssl.so")) {
        const char* lib_dir = getenv("LD_LIBRARY_PATH");
        if (lib_dir) {
            const char* target = strstr(pathname, "libcrypto.so") ? "libcrypt3.so" : "libsl3.so";
            char first_dir[512];
            if (sscanf(lib_dir, "%511[^:]", first_dir) == 1) {
                snprintf(buffer, size, "%s/%s", first_dir, target);
                LOGI("Redirecting (OpenSSL): %s -> %s", pathname, buffer);
                return buffer;
            }
        }
    }

    // 2. Redirect patched Termux paths to actual app home
    if (strstr(pathname, "/data/local/tmp/cterm")) {
        const char* app_home = getenv("HOME");
        if (app_home) {
            // /data/local/tmp/cterm is 21 chars
            const char* relative = pathname + 21;
            if (strncmp(relative, "/files", 6) == 0) {
                relative += 6;
            }
            snprintf(buffer, size, "%s%s", app_home, relative);
            LOGI("Redirecting (Termux): %s -> %s", pathname, buffer);
            return buffer;
        }
    }

    // 3. Redirect Termux sandbox paths to avoid EACCES permission warning on gitattributes
    if (strstr(pathname, "com.termux")) {
        const char* app_files = getenv("APP_FILES_DIR");
        if (app_files) {
            const char* match = strstr(pathname, "/com.termux/files");
            if (match) {
                const char* relative = match + 17; // length of "/com.termux/files"
                snprintf(buffer, size, "%s%s", app_files, relative);
                LOGI("Redirecting (Termux sandbox leak): %s -> %s", pathname, buffer);
                return buffer;
            }
        }
    }

    return pathname;
}

int access(const char *path, int mode) {
    if (!real_access) real_access = (access_t)dlsym(RTLD_NEXT, "access");
    
    if (resolve_helper(path)) {
        return 0; // Pretend Git helpers exist
    }

    char buffer[1024];
    const char* final_path = do_redirect(path, buffer, sizeof(buffer));
    return real_access(final_path, mode);
}

int open(const char *pathname, int flags, ...) {
    if (!real_open) real_open = (open_t)dlsym(RTLD_NEXT, "open");
    
    char buffer[1024];
    const char* final_path = do_redirect(pathname, buffer, sizeof(buffer));

    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, mode_t);
        va_end(args);
    }
    return real_open(final_path, flags, mode);
}

int stat(const char *pathname, struct stat *statbuf) {
    if (!real_stat) real_stat = (stat_t)dlsym(RTLD_NEXT, "stat");
    char buffer[1024];
    const char* final_path = do_redirect(pathname, buffer, sizeof(buffer));
    return real_stat(final_path, statbuf);
}

int lstat(const char *pathname, struct stat *statbuf) {
    if (!real_lstat) real_lstat = (lstat_t)dlsym(RTLD_NEXT, "lstat");
    char buffer[1024];
    const char* final_path = do_redirect(pathname, buffer, sizeof(buffer));
    return real_lstat(final_path, statbuf);
}

// Bionic often uses these internal versions
int __xstat(int ver, const char *pathname, struct stat *statbuf) {
    if (!real_xstat) real_xstat = (xstat_t)dlsym(RTLD_NEXT, "__xstat");
    char buffer[1024];
    const char* final_path = do_redirect(pathname, buffer, sizeof(buffer));
    if (real_xstat) return real_xstat(ver, final_path, statbuf);
    return -1;
}

int __lxstat(int ver, const char *pathname, struct stat *statbuf) {
    if (!real_lxstat) real_lxstat = (xstat_t)dlsym(RTLD_NEXT, "__lxstat");
    char buffer[1024];
    const char* final_path = do_redirect(pathname, buffer, sizeof(buffer));
    if (real_lxstat) return real_lxstat(ver, final_path, statbuf);
    return -1;
}

int execve(const char *filename, char *const argv[], char *const envp[]) {
    if (!real_execve) real_execve = (execve_t)dlsym(RTLD_NEXT, "execve");

    // Phase 3: Intercept 'node' execution to use the active dynamic version
    const char* last_slash = filename ? strrchr(filename, '/') : nullptr;
    const char* bn = last_slash ? last_slash + 1 : filename;
    if (bn && strcmp(bn, "node") == 0) {
        static char active_node_bin[512];
        static char active_node_lib[512];
        if (get_active_node_path(active_node_bin, sizeof(active_node_bin),
                                 active_node_lib, sizeof(active_node_lib))) {
            // Determine system linker (arm64 vs arm32)
            const char* linker = access("/system/bin/linker64", F_OK) == 0
                ? "/system/bin/linker64" : "/system/bin/linker";

            // Build new argv: [linker, node_binary, original_args...]
            int argc = 0;
            while (argv[argc]) argc++;
            std::vector<const char*> new_argv;
            new_argv.push_back(linker);
            new_argv.push_back(active_node_bin);
            for (int i = 1; i < argc; i++) new_argv.push_back(argv[i]);
            new_argv.push_back(nullptr);

            // Inject versioned lib dir into LD_LIBRARY_PATH
            const char* app_lib_dir = getenv("APP_LIB_DIR") ?: "";
            const char* old_ldpath = getenv("LD_LIBRARY_PATH") ?: "";
            static char new_ldpath[1024];
            snprintf(new_ldpath, sizeof(new_ldpath), "%s:%s:%s",
                     active_node_lib, app_lib_dir, old_ldpath);

            // Build updated envp with the new LD_LIBRARY_PATH
            std::vector<const char*> new_envp;
            static char ldpath_entry[1024];
            snprintf(ldpath_entry, sizeof(ldpath_entry), "LD_LIBRARY_PATH=%s", new_ldpath);
            bool found = false;
            for (int i = 0; envp[i]; i++) {
                if (strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) {
                    new_envp.push_back(ldpath_entry);
                    found = true;
                } else {
                    new_envp.push_back(envp[i]);
                }
            }
            if (!found) new_envp.push_back(ldpath_entry);
            new_envp.push_back(nullptr);

            LOGI("[NodeHook] Redirecting node -> %s via %s", active_node_bin, linker);
            return real_execve(linker,
                const_cast<char* const*>(new_argv.data()),
                const_cast<char* const*>(new_envp.data()));
        }
    }

    const char* helper = resolve_helper(filename);
    if (helper) {
        const char* lib_dir = getenv("APP_LIB_DIR");
        if (lib_dir) {
            static char actual_path[1024];
            snprintf(actual_path, sizeof(actual_path), "%s/%s", lib_dir, helper);
            LOGI("Redirecting execve: %s -> %s", filename, actual_path);
            return real_execve(actual_path, argv, envp);
        }
    }

    char buffer[1024];
    const char* final_path = do_redirect(filename, buffer, sizeof(buffer));
    return real_execve(final_path, argv, envp);
}

// Global DNS Interceptor
typedef int (*getaddrinfo_t)(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res);
static getaddrinfo_t real_getaddrinfo = nullptr;

int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res) {
    if (!real_getaddrinfo) real_getaddrinfo = (getaddrinfo_t)dlsym(RTLD_NEXT, "getaddrinfo");
    
    if (node && (strcmp(node, "registry.npmjs.org") == 0 || strcmp(node, "github.com") == 0)) {
        LOGI("Intercepting DNS for %s", node);
    }
    
    return real_getaddrinfo(node, service, hints, res);
}

}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kodrix_zohaib_bridge_PtyBridge_createPty(JNIEnv *env, jobject thiz, jstring shell, jstring bin_path, jstring lib_path, jstring home_path, jint rows, jint cols) {
    int master_fd;
    pid_t pid;

    const char *shell_path_raw = env->GetStringUTFChars(shell, nullptr);
    const char *bin_dir_raw = env->GetStringUTFChars(bin_path, nullptr);
    const char *lib_dir_raw = env->GetStringUTFChars(lib_path, nullptr);
    const char *home_dir_raw = env->GetStringUTFChars(home_path, nullptr);

    std::string s_shell(shell_path_raw);
    std::string s_bin(bin_dir_raw);
    std::string s_lib(lib_dir_raw);
    std::string s_home(home_dir_raw);

    env->ReleaseStringUTFChars(shell, shell_path_raw);
    env->ReleaseStringUTFChars(bin_path, bin_dir_raw);
    env->ReleaseStringUTFChars(lib_path, lib_dir_raw);
    env->ReleaseStringUTFChars(home_path, home_dir_raw);

    pid = forkpty(&master_fd, nullptr, nullptr, nullptr);

    if (pid < 0) {
        LOGE("Failed to forkpty");
        return -1;
    }

    if (pid == 0) {
        // Child process
        chdir(s_home.c_str());
        
        // Set basic environment.
        std::string bin_subdir = s_bin + "/bin";
        std::string git_exec_dir = s_bin + "/usr/git-exec";
        std::string new_path = bin_subdir + ":" + git_exec_dir + ":/system/bin:/system/xbin:/vendor/bin";
        setenv("PATH", new_path.c_str(), 1);
        
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", s_home.c_str(), 1);
        setenv("APP_LIB_DIR", s_lib.c_str(), 1);
        setenv("GIT_EXEC_PATH", s_lib.c_str(), 1);
        setenv("APP_FILES_DIR", s_bin.c_str(), 1);
        
        std::string env_path = s_bin + "/init.sh";
        setenv("ENV", env_path.c_str(), 1);
        
        execl(s_shell.c_str(), s_shell.c_str(), "-i", "-l", nullptr);
        
        LOGE("Failed to exec shell: %s", s_shell.c_str());
        _exit(1);
    }

    LOGI("Spawned shell PID: %d, Master FD: %d", pid, master_fd);
    return master_fd;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kodrix_zohaib_bridge_PtyBridge_setWindowSize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    ws.ws_row = rows;
    ws.ws_col = cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}

static const JNINativeMethod pty_methods[] = {
    {"createPty", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)I", (void*)Java_com_kodrix_zohaib_bridge_PtyBridge_createPty},
    {"setWindowSize", "(III)V", (void*)Java_com_kodrix_zohaib_bridge_PtyBridge_setWindowSize}
};

static const JNINativeMethod git_methods[] = {
    {"cloneRepo", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", (void*)Java_com_kodrix_zohaib_bridge_GitBridge_cloneRepo}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass ptyClass = env->FindClass("com/kodrix/zohaib/bridge/PtyBridge");
    if (ptyClass) {
        env->RegisterNatives(ptyClass, pty_methods, sizeof(pty_methods) / sizeof(pty_methods[0]));
        env->DeleteLocalRef(ptyClass);
    }

    jclass gitClass = env->FindClass("com/kodrix/zohaib/bridge/GitBridge");
    if (gitClass) {
        env->RegisterNatives(gitClass, git_methods, sizeof(git_methods) / sizeof(git_methods[0]));
        env->DeleteLocalRef(gitClass);
    }

    return JNI_VERSION_1_6;
}
