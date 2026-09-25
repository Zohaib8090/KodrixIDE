// libkodrix_exec.so: LD_PRELOAD shim that lets downloaded (Termux) binaries run on
// targetSdk >= 29. SELinux forbids execve() of files in the app's data dir, but the
// system linker may still mmap them, so ELFs there are launched as
// `/system/bin/linker64 <elf> args...`. Also remaps Termux's baked-in prefix
// (/data/data/com.termux/files) to $KODRIX_ROOT and answers /proc/self/exe with the
// real binary instead of the linker.
//
// Environment:
//   KODRIX_USR        replacement for /data/data/com.termux/files/usr (checked first); a
//                     downloaded runtime's install dir, whose bin/ lib/ mirror Termux's usr/
//   KODRIX_ROOT       replacement for /data/data/com.termux/files (fallback remap)
//   KODRIX_APP_DATA   ':'-separated app data dirs whose ELFs need the linker (optional)
//   KODRIX_EXE        set by this shim on rewritten execs; read by readlink(/proc/self/exe)
//   KODRIX_EXEC_DEBUG if set, log decisions to stderr
//
// Code between fork() and exec() must stay async-signal-safe: no malloc, only stack
// buffers, raw syscalls-level libc calls.

#undef _FORTIFY_SOURCE
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

extern char **environ;

#define TERMUX_ROOT "/data/data/com.termux/files"
#define TERMUX_ROOT_LEN (sizeof(TERMUX_ROOT) - 1)
#define TERMUX_USR TERMUX_ROOT "/usr"
#define TERMUX_USR_LEN (sizeof(TERMUX_USR) - 1)
#define MAX_ARGS 4096
#define MAX_ENVS 1024
#define HEADER_MAX 256

#if defined(__LP64__)
#define SYSTEM_LINKER "/system/bin/linker64"
#else
#define SYSTEM_LINKER "/system/bin/linker"
#endif

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*posix_spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                              const posix_spawnattr_t *, char *const[], char *const[]);
typedef int (*open_fn)(const char *, int, ...);
typedef int (*open2_fn)(const char *, int);
typedef int (*openat_fn)(int, const char *, int, ...);
typedef int (*openat2_fn)(int, const char *, int);
typedef FILE *(*fopen_fn)(const char *, const char *);
typedef int (*stat_fn)(const char *, struct stat *);
typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
typedef int (*access_fn)(const char *, int);
typedef int (*faccessat_fn)(int, const char *, int, int);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
typedef ssize_t (*readlink_chk_fn)(const char *, char *, size_t, size_t);
typedef ssize_t (*readlinkat_chk_fn)(int, const char *, char *, size_t, size_t);
typedef DIR *(*opendir_fn)(const char *);
typedef char *(*realpath_fn)(const char *, char *);
typedef int (*stat64_fn)(const char *, struct stat64 *);
typedef int (*fstatat64_fn)(int, const char *, struct stat64 *, int);

static execve_fn real_execve;
static posix_spawn_fn real_posix_spawn;
static open_fn real_open;
static open2_fn real_open_2;
static openat_fn real_openat;
static openat2_fn real_openat_2;
static fopen_fn real_fopen;
static stat_fn real_stat;
static stat_fn real_lstat;
static fstatat_fn real_fstatat;
static access_fn real_access;
static faccessat_fn real_faccessat;
static readlink_fn real_readlink;
static readlinkat_fn real_readlinkat;
static readlink_chk_fn real_readlink_chk;
static readlinkat_chk_fn real_readlinkat_chk;
static opendir_fn real_opendir;
static realpath_fn real_realpath;
// Large-file variants: programs built with _FILE_OFFSET_BITS=64 (and glibc hosts) call
// these names instead, bypassing the plain hooks.
static open_fn real_open64;
static openat_fn real_openat64;
static fopen_fn real_fopen64;
static stat64_fn real_stat64;
static stat64_fn real_lstat64;
static fstatat64_fn real_fstatat64;
static volatile int resolved;
static void kodrix_exec_resolve(void);
#define ENSURE() do { if (!resolved) kodrix_exec_resolve(); } while (0)

static int needs_mode(int flags) {
    return (flags & O_CREAT) == O_CREAT || (flags & O_TMPFILE) == O_TMPFILE;
}

// ── helpers (async-signal-safe) ──────────────────────────────────────────────

static int debug_enabled(void) { return getenv("KODRIX_EXEC_DEBUG") != NULL; }

// Every line goes to $KODRIX_LOG (append) when set, so failures are visible from the
// Settings > "View exec log" screen even when nobody is watching logcat/stderr live.
// Falls back to stderr (fd 2, usually the terminal itself) when KODRIX_LOG is unset.
#define LOG_MAX_BYTES (1024 * 1024)

static void log_write(const char *a, const char *b, const char *c, const char *d) {
    const char *parts[] = {"kodrix-exec: ", a, b ? b : "", c ? c : "", d ? d : "", "\n"};
    const char *log_path = getenv("KODRIX_LOG");
    int fd = -1;
    int opened = 0;
    if (log_path && log_path[0] && real_open) {
        int lf = real_open(log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (lf >= 0) {
            fd = lf;
            opened = 1;
            // Every runtime process can write here, so keep the file bounded.
            struct stat st;
            if (fstat(lf, &st) == 0 && st.st_size > LOG_MAX_BYTES) (void)!ftruncate(lf, 0);
        }
    }
    // Without a log file, only write to stderr in debug mode: this library runs inside
    // every downloaded runtime, and stray lines would corrupt program output (e.g. LSP
    // stdio, `node -v`).
    if (fd < 0) {
        if (!debug_enabled()) return;
        fd = 2;
    }
    for (size_t i = 0; i < sizeof(parts) / sizeof(parts[0]); i++) {
        size_t n = strlen(parts[i]);
        if (n) (void)!write(fd, parts[i], n);
    }
    if (opened) close(fd);
}

// Verbose trace of rewrite decisions — only when KODRIX_EXEC_DEBUG is set.
static void dbg(const char *a, const char *b, const char *c) {
    if (!debug_enabled()) return;
    log_write(a, b, c, NULL);
}

// Unconditional: a rewritten exec/spawn that actually failed. errno is read by the
// caller immediately after the failing call, before anything else can clobber it.
//
// Uses plain strerror() rather than strerror_r(): the latter has two incompatible
// signatures (POSIX returns int, GNU returns char*) selected by feature-test macros,
// which makes it a portability trap between glibc and bionic. strerror() is identical
// on both and not reentrant, but nothing else in this file's exec-time path touches
// its static buffer, so that's not a concern here.
static void log_exec_failure(const char *what, const char *path, int err) {
    log_write(what, " failed for ", path, strerror(err));
}

static size_t copy_str(char *dst, size_t cap, const char *src) {
    size_t n = strlen(src);
    if (n >= cap) return (size_t)-1;
    memcpy(dst, src, n + 1);
    return n;
}

static const char *replace_prefix(const char *path, size_t plen, const char *repl, char *buf, size_t cap) {
    size_t rl = strlen(repl), tl = strlen(path + plen);
    if (rl + tl + 1 > cap) return path;
    memcpy(buf, repl, rl);
    memcpy(buf + rl, path + plen, tl + 1);
    return buf;
}

// Maps /data/data/com.termux/files/usr[/...] to $KODRIX_USR[/...] when set, otherwise
// /data/data/com.termux/files[/...] to $KODRIX_ROOT[/...]. Returns path unchanged when
// it isn't a Termux path or the result wouldn't fit.
static const char *redirect(const char *path, char *buf, size_t cap) {
    if (path == NULL || strncmp(path, TERMUX_ROOT, TERMUX_ROOT_LEN) != 0) return path;
    char next = path[TERMUX_ROOT_LEN];
    if (next != '\0' && next != '/') return path;
    const char *usr = getenv("KODRIX_USR");
    if (usr && usr[0] && strncmp(path, TERMUX_USR, TERMUX_USR_LEN) == 0 &&
        (path[TERMUX_USR_LEN] == '\0' || path[TERMUX_USR_LEN] == '/')) {
        return replace_prefix(path, TERMUX_USR_LEN, usr, buf, cap);
    }
    const char *root = getenv("KODRIX_ROOT");
    if (root == NULL || root[0] == '\0') return path;
    return replace_prefix(path, TERMUX_ROOT_LEN, root, buf, cap);
}

static int has_dir_prefix(const char *path, const char *dir, size_t dlen) {
    if (dlen == 0 || strncmp(path, dir, dlen) != 0) return 0;
    return path[dlen] == '/' || path[dlen] == '\0';
}

static int under_app_data(const char *abs) {
    const char *list = getenv("KODRIX_APP_DATA");
    if (list == NULL || list[0] == '\0') {
        return strncmp(abs, "/data/data/", 11) == 0 || strncmp(abs, "/data/user/", 11) == 0;
    }
    const char *start = list;
    for (;;) {
        const char *end = strchr(start, ':');
        size_t len = end ? (size_t)(end - start) : strlen(start);
        if (has_dir_prefix(abs, start, len)) return 1;
        if (!end) return 0;
        start = end + 1;
    }
}

static int make_absolute(const char *path, char *out, size_t cap) {
    if (path[0] == '/') return copy_str(out, cap, path) == (size_t)-1 ? -1 : 0;
    if (getcwd(out, cap) == NULL) return -1;
    size_t n = strlen(out);
    size_t pl = strlen(path);
    if (n + 1 + pl + 1 > cap) return -1;
    out[n] = '/';
    memcpy(out + n + 1, path, pl + 1);
    return 0;
}

enum kind { KIND_OTHER, KIND_ELF, KIND_SCRIPT };

static enum kind read_header(const char *path, char *hdr, size_t cap) {
    int fd = real_open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return KIND_OTHER;
    ssize_t n = read(fd, hdr, cap - 1);
    close(fd);
    if (n < 4) return KIND_OTHER;
    hdr[n] = '\0';
    if (hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') return KIND_ELF;
    if (hdr[0] == '#' && hdr[1] == '!') return KIND_SCRIPT;
    return KIND_OTHER;
}

// Linux shebang semantics: "#!interp [one optional arg]\n".
static int parse_shebang(char *hdr, char **interp, char **arg) {
    char *p = hdr + 2;
    char *nl = strchr(p, '\n');
    if (!nl) return -1;
    *nl = '\0';
    while (*p == ' ' || *p == '\t') p++;
    if (*p == '\0') return -1;
    *interp = p;
    while (*p && *p != ' ' && *p != '\t') p++;
    *arg = NULL;
    if (*p) {
        *p++ = '\0';
        while (*p == ' ' || *p == '\t') p++;
        char *e = p + strlen(p);
        while (e > p && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r')) *--e = '\0';
        if (*p) *arg = p;
    }
    return 0;
}

// Shebang interpreters: Termux prefix → $KODRIX_ROOT; "/usr/bin/x" and "/bin/x" (absent on
// Android) → $KODRIX_ROOT/usr/bin/x if installed, else /system/bin/x.
static int join3(char *out, size_t cap, const char *a, const char *b, const char *c) {
    size_t la = strlen(a), lb = strlen(b), lc = strlen(c);
    if (la + lb + lc + 1 > cap) return -1;
    memcpy(out, a, la);
    memcpy(out + la, b, lb);
    memcpy(out + la + lb, c, lc + 1);
    return 0;
}

static const char *remap_interp(const char *interp, char *buf, size_t cap) {
    const char *r = redirect(interp, buf, cap);
    if (r != interp) return r;
    const char *name = NULL;
    if (strncmp(interp, "/usr/bin/", 9) == 0) name = interp + 9;
    else if (strncmp(interp, "/bin/", 5) == 0) name = interp + 5;
    if (name == NULL || name[0] == '\0' || strchr(name, '/')) return interp;
    const char *root = getenv("KODRIX_ROOT");
    if (root && join3(buf, cap, root, "/usr/bin/", name) == 0 && real_access(buf, X_OK) == 0) return buf;
    if (join3(buf, cap, "/system/bin/", name, "") == 0) return buf;
    return interp;
}

// ── exec planning ────────────────────────────────────────────────────────────

struct plan {
    const char *path;
    char *argv[MAX_ARGS + 4];
    char *envp[MAX_ENVS + 12];
    char redir[PATH_MAX];
    char abs[PATH_MAX];
    char hdr[HEADER_MAX];
    char interp_redir[PATH_MAX];
    char exe_env[PATH_MAX + 16];
};

static int env_key_is(const char *entry, const char *key) {
    size_t kl = strlen(key);
    return strncmp(entry, key, kl) == 0 && entry[kl] == '=';
}

static int env_has(char *const envp[], const char *key) {
    for (size_t i = 0; envp && envp[i]; i++)
        if (env_key_is(envp[i], key)) return 1;
    return 0;
}

// Copies envp, dropping any stale KODRIX_EXE, re-injecting our control vars if a caller
// built a fresh env without them, and setting KODRIX_EXE when exe != NULL.
static int build_env(struct plan *pl, char *const envp[], const char *exe) {
    static const char *const keep[] = {"LD_PRELOAD", "KODRIX_USR", "KODRIX_ROOT", "KODRIX_APP_DATA",
                                       "KODRIX_LOG", "KODRIX_EXEC_DEBUG"};
    size_t n = 0;
    for (size_t i = 0; envp && envp[i]; i++) {
        if (env_key_is(envp[i], "KODRIX_EXE")) continue;
        if (n >= MAX_ENVS) return -1;
        pl->envp[n++] = envp[i];
    }
    for (size_t k = 0; k < sizeof(keep) / sizeof(keep[0]); k++) {
        if (env_has(envp, keep[k])) continue;
        for (size_t i = 0; environ && environ[i]; i++) {
            if (env_key_is(environ[i], keep[k])) {
                pl->envp[n++] = environ[i];
                break;
            }
        }
    }
    if (exe) {
        size_t kl = strlen("KODRIX_EXE=");
        size_t el = strlen(exe);
        if (kl + el + 1 > sizeof(pl->exe_env)) return -1;
        memcpy(pl->exe_env, "KODRIX_EXE=", kl);
        memcpy(pl->exe_env + kl, exe, el + 1);
        pl->envp[n++] = pl->exe_env;
    }
    pl->envp[n] = NULL;
    return 0;
}

// Returns 0 with pl filled in, or -1 to fall back to the untouched call.
static int build_plan(const char *path, char *const argv[], char *const envp[], struct plan *pl) {
    if (path == NULL || argv == NULL) return -1;
    const char *p = redirect(path, pl->redir, sizeof(pl->redir));
    if (make_absolute(p, pl->abs, sizeof(pl->abs)) != 0) return -1;

    size_t argc = 0;
    while (argv[argc]) {
        if (++argc > MAX_ARGS) return -1;
    }

    const char *exe = NULL;
    size_t o = 0;
    enum kind k = under_app_data(pl->abs) ? read_header(pl->abs, pl->hdr, sizeof(pl->hdr)) : KIND_OTHER;

    if (k == KIND_ELF) {
        pl->path = SYSTEM_LINKER;
        pl->argv[o++] = (char *)SYSTEM_LINKER;
        pl->argv[o++] = pl->abs;
        exe = pl->abs;
        dbg("elf via linker: ", pl->abs, NULL);
    } else if (k == KIND_SCRIPT) {
        char *interp, *arg;
        if (parse_shebang(pl->hdr, &interp, &arg) != 0) return -1;
        const char *ip = remap_interp(interp, pl->interp_redir, sizeof(pl->interp_redir));
        if (ip != pl->interp_redir && copy_str(pl->interp_redir, sizeof(pl->interp_redir), ip) == (size_t)-1)
            return -1;
        char ihdr[8];
        int interp_is_app_elf = under_app_data(pl->interp_redir) &&
                                read_header(pl->interp_redir, ihdr, sizeof(ihdr)) == KIND_ELF;
        if (interp_is_app_elf) {
            pl->path = SYSTEM_LINKER;
            pl->argv[o++] = (char *)SYSTEM_LINKER;
            exe = pl->interp_redir;
        } else {
            pl->path = pl->interp_redir;
        }
        pl->argv[o++] = pl->interp_redir;
        if (arg) pl->argv[o++] = arg;
        pl->argv[o++] = pl->abs;
        dbg("script ", pl->abs, interp_is_app_elf ? " via linker" : " via system interpreter");
    } else {
        // Not ours to rewrite, but still apply the path remap and env hygiene.
        pl->path = p;
        pl->argv[o++] = argv[0];
    }

    for (size_t i = 1; i < argc; i++) pl->argv[o++] = argv[i];
    pl->argv[o] = NULL;
    return build_env(pl, envp ? envp : environ, exe);
}

// ── exec hooks ───────────────────────────────────────────────────────────────

int execve(const char *path, char *const argv[], char *const envp[]) {
    ENSURE();
    struct plan pl;
    if (build_plan(path, argv, envp, &pl) != 0) return real_execve(path, argv, envp);
    int r = real_execve(pl.path, pl.argv, pl.envp);
    // Only reached on failure — a successful execve() never returns. ENOENT is the
    // routine "not in this PATH dir, try the next one" outcome that PATH-searching
    // callers (our own execvpe/posix_spawnp below, and glibc/bionic's internal PATH
    // resolution inside posix_spawn) generate on every miss; logging each one would
    // bury real failures (EACCES, ENOEXEC, ...) in noise from normal command lookup.
    if (errno != ENOENT) log_exec_failure("execve", pl.path, errno);
    return r;
}

int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (file == NULL || file[0] == '\0') {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) return execve(file, argv, envp);
    const char *path = getenv("PATH");
    if (path == NULL) path = "/system/bin:/system/xbin";
    char cand[PATH_MAX];
    size_t fl = strlen(file);
    int saw_eacces = 0;
    for (const char *s = path;;) {
        const char *e = strchr(s, ':');
        size_t dl = e ? (size_t)(e - s) : strlen(s);
        if (dl == 0) { cand[0] = '.'; dl = 1; } else if (dl < sizeof(cand)) { memcpy(cand, s, dl); }
        if (dl + 1 + fl + 1 <= sizeof(cand)) {
            cand[dl] = '/';
            memcpy(cand + dl + 1, file, fl + 1);
            execve(cand, argv, envp);
            if (errno == EACCES) saw_eacces = 1;
            else if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
        if (!e) break;
        s = e + 1;
    }
    // A real EACCES on some candidate was already logged by execve() above (not
    // suppressed, unlike ENOENT); only the "checked every PATH dir, found nothing"
    // case needs its own summary line here.
    if (!saw_eacces) log_write("execvpe", ": command not found: ", file, NULL);
    errno = saw_eacces ? EACCES : ENOENT;
    return -1;
}

int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    ENSURE();
    struct plan pl;
    if (build_plan(path, argv, envp, &pl) != 0) return real_posix_spawn(pid, path, fa, attr, argv, envp);
    int r = real_posix_spawn(pid, pl.path, fa, attr, pl.argv, pl.envp);
    if (r != 0) log_exec_failure("posix_spawn", pl.path, r); // posix_spawn returns errno directly, not via errno
    return r;
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    ENSURE();
    if (file == NULL || file[0] == '\0') return ENOENT;
    if (strchr(file, '/')) return posix_spawn(pid, file, fa, attr, argv, envp);
    const char *path = getenv("PATH");
    if (path == NULL) path = "/system/bin:/system/xbin";
    char cand[PATH_MAX];
    size_t fl = strlen(file);
    for (const char *s = path;;) {
        const char *e = strchr(s, ':');
        size_t dl = e ? (size_t)(e - s) : strlen(s);
        if (dl == 0) { cand[0] = '.'; dl = 1; } else if (dl < sizeof(cand)) { memcpy(cand, s, dl); }
        if (dl + 1 + fl + 1 <= sizeof(cand)) {
            cand[dl] = '/';
            memcpy(cand + dl + 1, file, fl + 1);
            if (real_access(cand, X_OK) == 0) return posix_spawn(pid, cand, fa, attr, argv, envp);
        }
        if (!e) break;
        s = e + 1;
    }
    log_write("posix_spawnp", ": command not found: ", file, NULL);
    return ENOENT;
}

// ── path remap hooks ─────────────────────────────────────────────────────────

int open(const char *path, int flags, ...) {
    ENSURE();
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATH_MAX];
    return real_open(redirect(path, buf, sizeof(buf)), flags, mode);
}

int __open_2(const char *path, int flags) {
    ENSURE();
    char buf[PATH_MAX];
    const char *p = redirect(path, buf, sizeof(buf));
    return real_open_2 ? real_open_2(p, flags) : real_open(p, flags, 0);
}

int openat(int dirfd, const char *path, int flags, ...) {
    ENSURE();
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATH_MAX];
    return real_openat(dirfd, redirect(path, buf, sizeof(buf)), flags, mode);
}

int __openat_2(int dirfd, const char *path, int flags) {
    ENSURE();
    char buf[PATH_MAX];
    const char *p = redirect(path, buf, sizeof(buf));
    return real_openat_2 ? real_openat_2(dirfd, p, flags) : real_openat(dirfd, p, flags, 0);
}

FILE *fopen(const char *path, const char *mode) {
    ENSURE();
    char buf[PATH_MAX];
    return real_fopen(redirect(path, buf, sizeof(buf)), mode);
}

int stat(const char *path, struct stat *st) {
    ENSURE();
    char buf[PATH_MAX];
    return real_stat(redirect(path, buf, sizeof(buf)), st);
}

int lstat(const char *path, struct stat *st) {
    ENSURE();
    char buf[PATH_MAX];
    return real_lstat(redirect(path, buf, sizeof(buf)), st);
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    ENSURE();
    char buf[PATH_MAX];
    return real_fstatat(dirfd, redirect(path, buf, sizeof(buf)), st, flags);
}

int access(const char *path, int mode) {
    ENSURE();
    char buf[PATH_MAX];
    return real_access(redirect(path, buf, sizeof(buf)), mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    ENSURE();
    char buf[PATH_MAX];
    return real_faccessat(dirfd, redirect(path, buf, sizeof(buf)), mode, flags);
}

int open64(const char *path, int flags, ...) {
    ENSURE();
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATH_MAX];
    open_fn f = real_open64 ? real_open64 : real_open;
    return f(redirect(path, buf, sizeof(buf)), flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...) {
    ENSURE();
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATH_MAX];
    openat_fn f = real_openat64 ? real_openat64 : real_openat;
    return f(dirfd, redirect(path, buf, sizeof(buf)), flags, mode);
}

FILE *fopen64(const char *path, const char *mode) {
    ENSURE();
    char buf[PATH_MAX];
    fopen_fn f = real_fopen64 ? real_fopen64 : real_fopen;
    return f(redirect(path, buf, sizeof(buf)), mode);
}

int stat64(const char *path, struct stat64 *st) {
    ENSURE();
    char buf[PATH_MAX];
    return real_stat64(redirect(path, buf, sizeof(buf)), st);
}

int lstat64(const char *path, struct stat64 *st) {
    ENSURE();
    char buf[PATH_MAX];
    return real_lstat64(redirect(path, buf, sizeof(buf)), st);
}

int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags) {
    ENSURE();
    char buf[PATH_MAX];
    return real_fstatat64(dirfd, redirect(path, buf, sizeof(buf)), st, flags);
}

// Returns the length written for /proc/self/exe when KODRIX_EXE is set, else -2.
static ssize_t fake_self_exe(const char *path, char *out, size_t size) {
    if (path == NULL || strcmp(path, "/proc/self/exe") != 0) return -2;
    const char *exe = getenv("KODRIX_EXE");
    if (exe == NULL || exe[0] == '\0') return -2;
    size_t n = strlen(exe);
    if (n > size) n = size;
    memcpy(out, exe, n);
    return (ssize_t)n;
}

ssize_t readlink(const char *path, char *out, size_t size) {
    ENSURE();
    ssize_t r = fake_self_exe(path, out, size);
    if (r != -2) return r;
    char buf[PATH_MAX];
    return real_readlink(redirect(path, buf, sizeof(buf)), out, size);
}

ssize_t readlinkat(int dirfd, const char *path, char *out, size_t size) {
    ENSURE();
    ssize_t r = fake_self_exe(path, out, size);
    if (r != -2) return r;
    char buf[PATH_MAX];
    return real_readlinkat(dirfd, redirect(path, buf, sizeof(buf)), out, size);
}

ssize_t __readlink_chk(const char *path, char *out, size_t size, size_t out_size) {
    ENSURE();
    ssize_t r = fake_self_exe(path, out, size < out_size ? size : out_size);
    if (r != -2) return r;
    char buf[PATH_MAX];
    const char *p = redirect(path, buf, sizeof(buf));
    return real_readlink_chk ? real_readlink_chk(p, out, size, out_size) : real_readlink(p, out, size);
}

ssize_t __readlinkat_chk(int dirfd, const char *path, char *out, size_t size, size_t out_size) {
    ENSURE();
    ssize_t r = fake_self_exe(path, out, size < out_size ? size : out_size);
    if (r != -2) return r;
    char buf[PATH_MAX];
    const char *p = redirect(path, buf, sizeof(buf));
    return real_readlinkat_chk ? real_readlinkat_chk(dirfd, p, out, size, out_size)
                               : real_readlinkat(dirfd, p, out, size);
}

DIR *opendir(const char *path) {
    ENSURE();
    char buf[PATH_MAX];
    return real_opendir(redirect(path, buf, sizeof(buf)));
}

char *realpath(const char *path, char *resolved) {
    ENSURE();
    char buf[PATH_MAX];
    return real_realpath(redirect(path, buf, sizeof(buf)), resolved);
}

// dlsym is not async-signal-safe, so resolution normally happens in the constructor,
// before any fork(). ENSURE() only covers hooks called by other libraries' constructors
// that run before ours.
static void kodrix_exec_resolve(void) {
    real_execve = (execve_fn)dlsym(RTLD_NEXT, "execve");
    real_posix_spawn = (posix_spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    real_open = (open_fn)dlsym(RTLD_NEXT, "open");
    real_open_2 = (open2_fn)dlsym(RTLD_NEXT, "__open_2");
    real_openat = (openat_fn)dlsym(RTLD_NEXT, "openat");
    real_openat_2 = (openat2_fn)dlsym(RTLD_NEXT, "__openat_2");
    real_fopen = (fopen_fn)dlsym(RTLD_NEXT, "fopen");
    real_stat = (stat_fn)dlsym(RTLD_NEXT, "stat");
    real_lstat = (stat_fn)dlsym(RTLD_NEXT, "lstat");
    real_fstatat = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    real_access = (access_fn)dlsym(RTLD_NEXT, "access");
    real_faccessat = (faccessat_fn)dlsym(RTLD_NEXT, "faccessat");
    real_readlink = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    real_readlinkat = (readlinkat_fn)dlsym(RTLD_NEXT, "readlinkat");
    real_readlink_chk = (readlink_chk_fn)dlsym(RTLD_NEXT, "__readlink_chk");
    real_readlinkat_chk = (readlinkat_chk_fn)dlsym(RTLD_NEXT, "__readlinkat_chk");
    real_opendir = (opendir_fn)dlsym(RTLD_NEXT, "opendir");
    real_realpath = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
    real_open64 = (open_fn)dlsym(RTLD_NEXT, "open64");
    real_openat64 = (openat_fn)dlsym(RTLD_NEXT, "openat64");
    real_fopen64 = (fopen_fn)dlsym(RTLD_NEXT, "fopen64");
    real_stat64 = (stat64_fn)dlsym(RTLD_NEXT, "stat64");
    real_lstat64 = (stat64_fn)dlsym(RTLD_NEXT, "lstat64");
    real_fstatat64 = (fstatat64_fn)dlsym(RTLD_NEXT, "fstatat64");
    resolved = 1;
}

__attribute__((constructor)) static void kodrix_exec_init(void) {
    if (!resolved) kodrix_exec_resolve();
    // Debug-only: logging every process start would grow the log without bound.
    if (!debug_enabled()) return;
    char pid[24];
    snprintf(pid, sizeof(pid), "%d", (int)getpid());
    const char *usr = getenv("KODRIX_USR");
    log_write("loaded in pid ", pid, usr && usr[0] ? ", KODRIX_USR=" : ", KODRIX_USR unset", usr && usr[0] ? usr : NULL);
}
