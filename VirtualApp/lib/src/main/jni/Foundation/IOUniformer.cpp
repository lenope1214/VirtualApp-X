//
// VirtualApp Native Project
//
#include <unistd.h>
#include <stdlib.h>
#include <fb/include/fb/ALog.h>
#include <Substrate/CydiaSubstrate.h>

#include "IOUniformer.h"
#include "SandboxFs.h"
#include "Path.h"
#include "SymbolFinder.h"
#include <sys/syscall.h>
#include "LegacySyscall.h"
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <link.h>
#include <sys/prctl.h>

bool iu_loaded = false;

static char s_so_path[512];
static char s_guest_pkg[256];
static int  s_api_level = 0;
static int  s_preview_api_level = 0;

static const char* va_so_path()   { return s_so_path[0]   ? s_so_path   : getenv("V_SO_PATH"); }
static const char* va_guest_pkg() { return s_guest_pkg[0]  ? s_guest_pkg : getenv("V_GUEST_PKG"); }

void IOUniformer::init_env_before_all() {
    if (iu_loaded)
        return;
    char *api_level_chars = getenv("V_API_LEVEL");
    char *preview_api_level_chars = getenv("V_PREVIEW_API_LEVEL");
    if (api_level_chars) {
        ALOGE("Enter init before all.");
        int api_level = atoi(api_level_chars);
        int preview_api_level;
        preview_api_level = atoi(preview_api_level_chars);
        char keep_env_name[25];
        char forbid_env_name[25];
        char replace_src_env_name[25];
        char replace_dst_env_name[25];
        int i = 0;
        while (true) {
            sprintf(keep_env_name, "V_KEEP_ITEM_%d", i);
            char *item = getenv(keep_env_name);
            if (!item) {
                break;
            }
            add_keep_item(item);
            i++;
        }
        i = 0;
        while (true) {
            sprintf(forbid_env_name, "V_FORBID_ITEM_%d", i);
            char *item = getenv(forbid_env_name);
            if (!item) {
                break;
            }
            add_forbidden_item(item);
            i++;
        }
        i = 0;
        while (true) {
            sprintf(replace_src_env_name, "V_REPLACE_ITEM_SRC_%d", i);
            char *item_src = getenv(replace_src_env_name);
            if (!item_src) {
                break;
            }
            sprintf(replace_dst_env_name, "V_REPLACE_ITEM_DST_%d", i);
            char *item_dst = getenv(replace_dst_env_name);
            add_replace_item(item_src, item_dst);
            i++;
        }
        startUniformer(getenv("V_SO_PATH"),api_level, preview_api_level);
        iu_loaded = true;
    }
}


static inline void
hook_function(void *handle, const char *symbol, void *new_func, void **old_func) {
    void *addr = dlsym(handle, symbol);
    if (addr == NULL) {
        return;
    }
    MSHookFunction(addr, new_func, old_func);
}


void onSoLoaded(const char *name, void *handle);

void IOUniformer::redirect(const char *orig_path, const char *new_path) {
    add_replace_item(orig_path, new_path);
}

const char *IOUniformer::query(const char *orig_path) {
    int res;
    return relocate_path(orig_path, &res);
}

void IOUniformer::whitelist(const char *_path) {
    add_keep_item(_path);
}

void IOUniformer::forbid(const char *_path) {
    add_forbidden_item(_path);
}


const char *IOUniformer::reverse(const char *_path) {
    return reverse_relocate_path(_path);
}


/* Sandbox transparency — proc filesystem filtering (3-A, 3-B, TracerPid) */

#ifndef __NR_memfd_create
#if defined(__arm__)
#define __NR_memfd_create 385
#elif defined(__aarch64__)
#define __NR_memfd_create 279
#elif defined(__i386__)
#define __NR_memfd_create 356
#elif defined(__x86_64__)
#define __NR_memfd_create 319
#endif
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

// Forward declaration — defined later by HOOK_DEF(long, syscall, ...)
extern "C" { extern long (*orig_syscall)(long, ...); }

static bool is_proc_path(const char *path, const char *leaf) {
    if (!path || !leaf) return false;
    char buf[80];
    snprintf(buf, sizeof(buf), "/proc/self/%s", leaf);
    if (strcmp(path, buf) == 0) return true;

    // Match /proc/<N>/<leaf> where N is any thread of our process
    if (strncmp(path, "/proc/", 6) != 0) return false;
    const char *p = path + 6;
    if (*p < '1' || *p > '9') return false;
    const char *slash = strchr(p, '/');
    if (!slash) return false;
    if (strcmp(slash + 1, leaf) != 0) return false;

    // Extract the numeric TID
    char tid_str[16];
    size_t tid_len = slash - p;
    if (tid_len >= sizeof(tid_str)) return false;
    memcpy(tid_str, p, tid_len);
    tid_str[tid_len] = '\0';

    // Check if this TID belongs to our process via /proc/self/task/<tid>
    snprintf(buf, sizeof(buf), "/proc/self/task/%s", tid_str);
    if (orig_syscall)
        return orig_syscall(__NR_faccessat, AT_FDCWD, buf, F_OK, 0) == 0;
    // Fallback: only match our PID before hooks are installed
    int tid = atoi(tid_str);
    return tid == getpid();
}

static int make_memfd(const char *tag) {
    return (int) syscall(__NR_memfd_create, tag, MFD_CLOEXEC);
}

static bool should_hide_maps_line(const char *line) {
    if (strstr(line, "libsubstrate")) return true;
    if (strstr(line, "CydiaSubstrate")) return true;
    if (strstr(line, "libva-native")) return true;
    if (strstr(line, "libva++")) return true;
    if (strstr(line, "frida")) return true;
    if (strstr(line, "xposed")) return true;
    if (strstr(line, "magisk")) return true;
    const char *so_path = va_so_path();
    if (so_path && strstr(line, so_path)) return true;
    if (strstr(line, "io.vax.host")) {
        const char *guest = va_guest_pkg();
        if (guest && strstr(line, guest)) return false;
        return true;
    }
    return false;
}

static void sanitize_maps_path(char *line, size_t maxlen) {
    const char *va_marker = "io.vax.host/virtual/data/";
    const size_t marker_len = 24;
    char *pos = strstr(line, va_marker);
    if (!pos) return;

    const char *after = pos + marker_len;
    char *scan = pos - 1;
    while (scan >= line && *scan != ' ' && *scan != '\t') scan--;
    char *path_start = scan + 1;

    size_t prefix_len = (size_t)(path_start - line);
    size_t tail_len = strlen(after);
    if (prefix_len + 6 + tail_len + 1 > maxlen) return;

    memmove(path_start + 6, after, tail_len + 1);
    memcpy(path_start, "/data/", 6);
}

static int open_filtered_maps() {
    int rfd = (int) syscall(__NR_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY, 0);
    if (rfd < 0) return rfd;
    int mfd = make_memfd("m");
    if (mfd < 0) { close(rfd); return -1; }
    FILE *fp = fdopen(rfd, "r");
    if (!fp) { close(rfd); close(mfd); return -1; }
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (!should_hide_maps_line(line)) {
            sanitize_maps_path(line, sizeof(line));
            write(mfd, line, strlen(line));
        }
    }
    fclose(fp);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static int open_guest_cmdline() {
    const char *guest = va_guest_pkg();
    if (!guest) return -1;
    int mfd = make_memfd("c");
    if (mfd < 0) return -1;
    write(mfd, guest, strlen(guest) + 1);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static int open_filtered_status() {
    int rfd = (int) syscall(__NR_openat, AT_FDCWD, "/proc/self/status", O_RDONLY, 0);
    if (rfd < 0) return rfd;
    int mfd = make_memfd("s");
    if (mfd < 0) { close(rfd); return -1; }
    FILE *fp = fdopen(rfd, "r");
    if (!fp) { close(rfd); close(mfd); return -1; }
    const char *guest = va_guest_pkg();
    char line[256];
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0)
            write(mfd, "TracerPid:\t0\n", 13);
        else if (guest && strncmp(line, "Name:", 5) == 0) {
            char buf[64];
            snprintf(buf, sizeof(buf), "Name:\t%.15s\n", guest);
            write(mfd, buf, strlen(buf));
        } else
            write(mfd, line, strlen(line));
    }
    fclose(fp);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static int open_guest_comm(const char *path) {
    const char *guest = va_guest_pkg();
    if (!guest) return -1;
    // Read the REAL comm for whichever tid was requested. Returning the guest
    // package for EVERY thread is itself a sandbox fingerprint: a real ART app
    // has diverse thread names (Jit thread pool, FinalizerDaemon, Binder:*,
    // RenderThread, UnityMain, mono, ...). So we only rewrite names that leak
    // the VA host identity and pass legitimate thread names through unchanged.
    int rfd = (int) syscall(__NR_openat, AT_FDCWD, path, O_RDONLY, 0);
    if (rfd < 0) return rfd;
    char real[64];
    ssize_t n = read(rfd, real, sizeof(real) - 1);
    close(rfd);
    if (n < 0) n = 0;
    real[n] = '\0';
    // Trim trailing newline(s) for the leak check only.
    size_t ln = (size_t) n;
    while (ln > 0 && (real[ln - 1] == '\n' || real[ln - 1] == '\r')) ln--;
    char namebuf[64];
    memcpy(namebuf, real, ln);
    namebuf[ln] = '\0';

    int mfd = make_memfd("k");
    if (mfd < 0) return -1;
    if (strstr(namebuf, "io.vax") || strstr(namebuf, "vax.host") ||
        strstr(namebuf, "virtual") || strstr(namebuf, "lody") ||
        strstr(namebuf, "va++")) {
        char buf[17];
        snprintf(buf, sizeof(buf), "%.15s\n", guest);
        write(mfd, buf, strlen(buf));
    } else {
        if (n > 0) write(mfd, real, (size_t) n);
    }
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static bool should_hide_mount_line(const char *line) {
    if (strstr(line, "io.vax.host")) {
        const char *guest = va_guest_pkg();
        if (guest && strstr(line, guest)) return false;
        return true;
    }
    if (strstr(line, "/virtual/")) return true;
    return false;
}

static int open_filtered_mountinfo(const char *leaf) {
    char real_path[64];
    snprintf(real_path, sizeof(real_path), "/proc/self/%s", leaf);
    int rfd = (int) syscall(__NR_openat, AT_FDCWD, real_path, O_RDONLY, 0);
    if (rfd < 0) return rfd;
    int mfd = make_memfd("mi");
    if (mfd < 0) { close(rfd); return -1; }
    FILE *fp = fdopen(rfd, "r");
    if (!fp) { close(rfd); close(mfd); return -1; }
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (!should_hide_mount_line(line))
            write(mfd, line, strlen(line));
    }
    fclose(fp);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static bool should_hide_env_entry(const char *entry) {
    if (entry[0] == 'V' && entry[1] == '_') return true;
    if (strncmp(entry, "LD_PRELOAD=", 11) == 0) return true;
    return false;
}

static int open_filtered_environ() {
    int rfd = (int) syscall(__NR_openat, AT_FDCWD, "/proc/self/environ", O_RDONLY, 0);
    if (rfd < 0) return rfd;
    int mfd = make_memfd("e");
    if (mfd < 0) { close(rfd); return -1; }
    size_t cap = 16384;
    char *buf = (char *) malloc(cap);
    if (!buf) { close(rfd); close(mfd); return -1; }
    ssize_t total = 0;
    ssize_t n;
    while ((n = read(rfd, buf + total, cap - total)) > 0) {
        total += n;
        if ((size_t)total >= cap) {
            cap *= 2;
            char *tmp = (char *) realloc(buf, cap);
            if (!tmp) break;
            buf = tmp;
        }
    }
    close(rfd);
    const char *p = buf;
    const char *end = buf + total;
    while (p < end) {
        size_t len = strnlen(p, end - p);
        if (len > 0 && !should_hide_env_entry(p))
            write(mfd, p, len + 1);
        p += len + 1;
    }
    free(buf);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static int maybe_intercept_proc(const char *path) {
    if (!path) return -1;
    if (is_proc_path(path, "maps"))    return open_filtered_maps();
    if (is_proc_path(path, "cmdline")) {
        int fd = open_guest_cmdline();
        if (fd >= 0) return fd;
    }
    if (is_proc_path(path, "status"))  return open_filtered_status();
    if (is_proc_path(path, "comm")) {
        int fd = open_guest_comm(path);
        if (fd >= 0) return fd;
    }
    if (is_proc_path(path, "mountinfo")) return open_filtered_mountinfo("mountinfo");
    if (is_proc_path(path, "mounts"))    return open_filtered_mountinfo("mounts");
    if (is_proc_path(path, "environ"))   return open_filtered_environ();
    return -1;
}

__BEGIN_DECLS

#define FREE(ptr, org_ptr) { if ((void*) ptr != NULL && (void*) ptr != (void*) org_ptr) { free((void*) ptr); } }

// int faccessat(int dirfd, const char *pathname, int mode, int flags);
HOOK_DEF(int, faccessat, int dirfd, const char *pathname, int mode, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_faccessat, dirfd, redirect_path, mode, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags);
HOOK_DEF(int, fchmodat, int dirfd, const char *pathname, mode_t mode, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_fchmodat, dirfd, redirect_path, mode, flags);
    FREE(redirect_path, pathname);
    return ret;
}
// int fchmod(const char *pathname, mode_t mode);
HOOK_DEF(int, fchmod, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_chmod, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


// int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat, int dirfd, const char *pathname, struct stat *buf, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_fstatat64, dirfd, redirect_path, buf, flags);
    FREE(redirect_path, pathname);
    return ret;
}

// int fstatat64(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat64, int dirfd, const char *pathname, struct stat *buf, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_fstatat64, dirfd, redirect_path, buf, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int fstat(const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_fstat64, redirect_path, buf);
    FREE(redirect_path, pathname);
    return ret;
}


// int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev);
HOOK_DEF(int, mknodat, int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_mknodat, dirfd, redirect_path, mode, dev);
    FREE(redirect_path, pathname);
    return ret;
}
// int mknod(const char *pathname, mode_t mode, dev_t dev);
HOOK_DEF(int, mknod, const char *pathname, mode_t mode, dev_t dev) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_mknod, redirect_path, mode, dev);
    FREE(redirect_path, pathname);
    return ret;
}


// int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags);
HOOK_DEF(int, utimensat, int dirfd, const char *pathname, const struct timespec times[2],
         int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_utimensat, dirfd, redirect_path, times, flags);
    FREE(redirect_path, pathname);
    return ret;
}


// int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags);
HOOK_DEF(int, fchownat, int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_fchownat, dirfd, redirect_path, owner, group, flags);
    FREE(redirect_path, pathname);
    return ret;
}

// int chroot(const char *pathname);
HOOK_DEF(int, chroot, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_chroot, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


// int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, renameat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_renameat, olddirfd, redirect_path_old, newdirfd, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}
// int rename(const char *oldpath, const char *newpath);
HOOK_DEF(int, rename, const char *oldpath, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_rename, redirect_path_old, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int unlinkat(int dirfd, const char *pathname, int flags);
HOOK_DEF(int, unlinkat, int dirfd, const char *pathname, int flags) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_unlinkat, dirfd, redirect_path, flags);
    FREE(redirect_path, pathname);
    return ret;
}
// int unlink(const char *pathname);
HOOK_DEF(int, unlink, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_unlink, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


// int symlinkat(const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, symlinkat, const char *oldpath, int newdirfd, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_symlinkat, redirect_path_old, newdirfd, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}
// int symlink(const char *oldpath, const char *newpath);
HOOK_DEF(int, symlink, const char *oldpath, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_symlink, redirect_path_old, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags);
HOOK_DEF(int, linkat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
         int flags) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_linkat, olddirfd, redirect_path_old, newdirfd, redirect_path_new, flags);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}
// int link(const char *oldpath, const char *newpath);
HOOK_DEF(int, link, const char *oldpath, const char *newpath) {
    int res_old;
    int res_new;
    const char *redirect_path_old = relocate_path(oldpath, &res_old);
    const char *redirect_path_new = relocate_path(newpath, &res_new);
    int ret = syscall(__NR_link, redirect_path_old, redirect_path_new);
    FREE(redirect_path_old, oldpath);
    FREE(redirect_path_new, newpath);
    return ret;
}


// int utimes(const char *filename, const struct timeval *tvp);
HOOK_DEF(int, utimes, const char *pathname, const struct timeval *tvp) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_utimes, redirect_path, tvp);
    FREE(redirect_path, pathname);
    return ret;
}


// int access(const char *pathname, int mode);
HOOK_DEF(int, access, const char *pathname, int mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_access, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


// int chmod(const char *path, mode_t mode);
HOOK_DEF(int, chmod, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_chmod, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


// int chown(const char *path, uid_t owner, gid_t group);
HOOK_DEF(int, chown, const char *pathname, uid_t owner, gid_t group) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_chown, redirect_path, owner, group);
    FREE(redirect_path, pathname);
    return ret;
}


// int lstat(const char *path, struct stat *buf);
HOOK_DEF(int, lstat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_lstat64, redirect_path, buf);
    FREE(redirect_path, pathname);
    return ret;
}


// int stat(const char *path, struct stat *buf);
HOOK_DEF(int, stat, const char *pathname, struct stat *buf) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_stat64, redirect_path, buf);
    FREE(redirect_path, pathname);
    return ret;
}


// int mkdirat(int dirfd, const char *pathname, mode_t mode);
HOOK_DEF(int, mkdirat, int dirfd, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_mkdirat, dirfd, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}
// int mkdir(const char *pathname, mode_t mode);
HOOK_DEF(int, mkdir, const char *pathname, mode_t mode) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_mkdir, redirect_path, mode);
    FREE(redirect_path, pathname);
    return ret;
}


// int rmdir(const char *pathname);
HOOK_DEF(int, rmdir, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_rmdir, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}


static void sanitize_readlink_buf(char *buf, ssize_t *ret, size_t bufsiz) {
    if (*ret <= 0 || (size_t)*ret >= bufsiz) return;
    char saved = buf[*ret];
    buf[*ret] = '\0';
    const char *va_marker = "io.vax.host/virtual/data/";
    char *pos = strstr(buf, va_marker);
    if (!pos) { buf[*ret] = saved; return; }
    const char *after = pos + 24;
    size_t tail_len = strlen(after);
    memmove(buf + 6, after, tail_len);
    memcpy(buf, "/data/", 6);
    *ret = (ssize_t)(6 + tail_len);
}

// int readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
HOOK_DEF(int, readlinkat, int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_readlinkat, dirfd, redirect_path, buf, bufsiz);
    FREE(redirect_path, pathname);
    ssize_t sret = (ssize_t) ret;
    sanitize_readlink_buf(buf, &sret, bufsiz);
    return (int) sret;
}
// ssize_t readlink(const char *path, char *buf, size_t bufsiz);
HOOK_DEF(ssize_t, readlink, const char *pathname, char *buf, size_t bufsiz) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    ssize_t ret = syscall(__NR_readlink, redirect_path, buf, bufsiz);
    FREE(redirect_path, pathname);
    sanitize_readlink_buf(buf, &ret, bufsiz);
    return ret;
}




// int dladdr(const void *addr, Dl_info *info);
HOOK_DEF(int, dladdr, const void *addr, Dl_info *info) {
    int ret = orig_dladdr(addr, info);
    // [DIAG anti-hook] Log every dladdr query so we can see whether the packer
    // validates its dlsym-resolved function pointers against library ranges.
    ALOGD("VAX-DIAG dladdr query addr=%p ret=%d fname=%s fbase=%p sname=%s",
          addr, ret,
          (ret && info && info->dli_fname) ? info->dli_fname : "(null)",
          (ret && info) ? info->dli_fbase : (void *)0,
          (ret && info && info->dli_sname) ? info->dli_sname : "(null)");
    if (ret && info && info->dli_fname) {
        static __thread char sanitized[512];
        const char *fname = info->dli_fname;
        const char *vd = strstr(fname, "io.vax.host/virtual/data/");
        if (vd) {
            snprintf(sanitized, sizeof(sanitized), "/data/%s", vd + 24);
            ALOGD("VAX-IO dladdr sanitize: %s -> %s", fname, sanitized);
            info->dli_fname = sanitized;
        } else if (strstr(fname, "io.vax.host")) {
            const char *guest = va_guest_pkg();
            if (guest && strstr(fname, guest)) {
                snprintf(sanitized, sizeof(sanitized), "/data/app/%s/lib/arm64/%s",
                         guest, info->dli_sname ? strrchr(fname, '/') + 1 : "lib.so");
                ALOGD("VAX-IO dladdr sanitize host: %s -> %s", fname, sanitized);
                info->dli_fname = sanitized;
            } else {
                info->dli_fname = "libc.so";
            }
        } else if (strstr(fname, "libva++") || strstr(fname, "libva-native") ||
                   strstr(fname, "libsubstrate") || strstr(fname, "CydiaSubstrate")) {
            info->dli_fname = "libc.so";
        }
    }
    return ret;
}

// dl_iterate_phdr hook — filter VA host libraries from enumeration
struct phdr_wrap_ctx {
    int (*real_cb)(struct dl_phdr_info *, size_t, void *);
    void *real_data;
};

static int phdr_filter_cb(struct dl_phdr_info *info, size_t size, void *data) {
    struct phdr_wrap_ctx *ctx = (struct phdr_wrap_ctx *)data;
    if (info->dlpi_name && info->dlpi_name[0]) {
        if (strstr(info->dlpi_name, "io.vax.host")) {
            const char *vd = strstr(info->dlpi_name, "io.vax.host/virtual/data/");
            if (vd) {
                static __thread char clean_name[512];
                snprintf(clean_name, sizeof(clean_name), "/data/%s", vd + 24);
                struct dl_phdr_info patched = *info;
                patched.dlpi_name = clean_name;
                return ctx->real_cb(&patched, size, ctx->real_data);
            }
            return 0;
        }
        if (strstr(info->dlpi_name, "libva++") ||
            strstr(info->dlpi_name, "libva-native") ||
            strstr(info->dlpi_name, "libsubstrate") ||
            strstr(info->dlpi_name, "CydiaSubstrate")) {
            return 0;
        }
    }
    return ctx->real_cb(info, size, ctx->real_data);
}

HOOK_DEF(int, dl_iterate_phdr,
         int (*callback)(struct dl_phdr_info *, size_t, void *), void *data) {
    // [DIAG anti-hook] The packer uses dl_iterate_phdr to map library address
    // ranges; logging each call reveals whether it scans for injected hooks.
    ALOGD("VAX-DIAG dl_iterate_phdr called (cb=%p)", (void *)callback);
    struct phdr_wrap_ctx ctx = { callback, data };
    return orig_dl_iterate_phdr(phdr_filter_cb, &ctx);
}

// syscall hook — intercept proc-file access via indirect syscalls
static __thread int s_in_syscall_hook;

HOOK_DEF(long, syscall, long number, ...) {
    if (s_in_syscall_hook) {
        va_list args;
        va_start(args, number);
        long a1 = va_arg(args, long);
        long a2 = va_arg(args, long);
        long a3 = va_arg(args, long);
        long a4 = va_arg(args, long);
        long a5 = va_arg(args, long);
        long a6 = va_arg(args, long);
        va_end(args);
        return orig_syscall(number, a1, a2, a3, a4, a5, a6);
    }
    s_in_syscall_hook = 1;
    long result;

    va_list args;
    va_start(args, number);

    if (number == __NR_openat) {
        int dfd = va_arg(args, int);
        const char *path = va_arg(args, const char *);
        int flags = va_arg(args, int);
        va_end(args);
        if (path) {
            int pfd = maybe_intercept_proc(path);
            if (pfd >= 0) { s_in_syscall_hook = 0; return pfd; }
            int res;
            const char *rp = relocate_path(path, &res);
            result = orig_syscall(__NR_openat, dfd, rp, flags);
            FREE(rp, path);
        } else {
            result = orig_syscall(__NR_openat, dfd, path, flags);
        }
        s_in_syscall_hook = 0;
        return result;
    }

    if (number == __NR_readlinkat) {
        int dfd = va_arg(args, int);
        const char *path = va_arg(args, const char *);
        char *buf = va_arg(args, char *);
        size_t bufsiz = va_arg(args, size_t);
        va_end(args);
        int res;
        const char *rp = relocate_path(path, &res);
        result = orig_syscall(__NR_readlinkat, dfd, rp, buf, bufsiz);
        FREE(rp, path);
        ssize_t sr = (ssize_t)result;
        sanitize_readlink_buf(buf, &sr, bufsiz);
        s_in_syscall_hook = 0;
        return (long)sr;
    }

    long a1 = va_arg(args, long);
    long a2 = va_arg(args, long);
    long a3 = va_arg(args, long);
    long a4 = va_arg(args, long);
    long a5 = va_arg(args, long);
    long a6 = va_arg(args, long);
    va_end(args);
    if (number == __NR_prctl) {
        if (a1 == PR_GET_NAME) {
            const char *guest = va_guest_pkg();
            if (guest && a2) {
                char *out = (char *)a2;
                snprintf(out, 16, "%.15s", guest);
                s_in_syscall_hook = 0;
                return 0;
            }
        } else if (a1 == PR_SET_NAME) {
            // Force the REAL kernel thread name to the guest package so raw-syscall
            // readers of /proc/<tid>/comm see a clean name, never io.vax.host:p0.
            const char *guest = va_guest_pkg();
            if (guest) {
                char nm[16];
                snprintf(nm, sizeof(nm), "%.15s", guest);
                result = orig_syscall(__NR_prctl, PR_SET_NAME, (long)nm, 0, 0, 0);
            } else {
                result = orig_syscall(__NR_prctl, PR_SET_NAME, a2, a3, a4, a5);
            }
            s_in_syscall_hook = 0;
            return result;
        }
    }

    result = orig_syscall(number, a1, a2, a3, a4, a5, a6);
    s_in_syscall_hook = 0;
    return result;
}

HOOK_DEF(int, prctl, int option, unsigned long a2, unsigned long a3,
         unsigned long a4, unsigned long a5) {
    if (option == PR_GET_NAME) {
        const char *guest = va_guest_pkg();
        if (guest && a2) {
            char *out = (char *)a2;
            snprintf(out, 16, "%.15s", guest);
            return 0;
        }
    } else if (option == PR_SET_NAME) {
        const char *guest = va_guest_pkg();
        if (guest) {
            char nm[16];
            snprintf(nm, sizeof(nm), "%.15s", guest);
            return orig_prctl(PR_SET_NAME, (unsigned long)nm, 0, 0, 0);
        }
        return orig_prctl(option, a2, a3, a4, a5);
    }
    return orig_prctl(option, a2, a3, a4, a5);
}

// int truncate(const char *path, off_t length);
HOOK_DEF(int, truncate, const char *pathname, off_t length) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_truncate, redirect_path, length);
    FREE(redirect_path, pathname);
    return ret;
}

#define RETURN_IF_FORBID if(res == FORBID) return -1;

// int truncate64(const char *pathname, off_t length);
HOOK_DEF(int, truncate64, const char *pathname, off_t length) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_truncate64, redirect_path, length);
    FREE(redirect_path, pathname);
    return ret;
}


// int chdir(const char *path);
HOOK_DEF(int, chdir, const char *pathname) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    RETURN_IF_FORBID
    int ret = syscall(__NR_chdir, redirect_path);
    FREE(redirect_path, pathname);
    return ret;
}




// int __openat(int fd, const char *pathname, int flags, int mode);
HOOK_DEF(int, __openat, int fd, const char *pathname, int flags, int mode) {
    int proc_fd = maybe_intercept_proc(pathname);
    if (proc_fd >= 0) return proc_fd;
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_openat, fd, redirect_path, flags, mode);
    FREE(redirect_path, pathname);
    return ret;
}
// Android 16 bionic does not export __openat (dlsym -> NULL), so the __openat hook above never
// installed and absolute-path opens were never redirected. Hook the exported openat/open
// wrappers instead; libcore's open path goes through them via the PLT.
HOOK_DEF(int, openat, int fd, const char *pathname, int flags, int mode) {
    int proc_fd = maybe_intercept_proc(pathname);
    if (proc_fd >= 0) {
        ALOGD("VAX-IO openat intercept: %s", pathname);
        return proc_fd;
    }
    if (pathname && (strstr(pathname, "proc") || strstr(pathname, "dokkaebi") ||
                     strstr(pathname, "io.vax") || strstr(pathname, "xshield") ||
                     strstr(pathname, "maps") || strstr(pathname, "attr"))) {
        ALOGD("VAX-IO openat probe: %s", pathname);
    }
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_openat, fd, redirect_path, flags, mode);
    FREE(redirect_path, pathname);
    return ret;
}
HOOK_DEF(int, open, const char *pathname, int flags, int mode) {
    int proc_fd = maybe_intercept_proc(pathname);
    if (proc_fd >= 0) return proc_fd;
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_openat, AT_FDCWD, redirect_path, flags, mode);
    FREE(redirect_path, pathname);
    return ret;
}
// int __open(const char *pathname, int flags, int mode);
HOOK_DEF(int, __open, const char *pathname, int flags, int mode) {
    int proc_fd = maybe_intercept_proc(pathname);
    if (proc_fd >= 0) return proc_fd;
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_open, redirect_path, flags, mode);
    FREE(redirect_path, pathname);
    return ret;
}

// int statfs(const char *path, struct statfs *buf);  (exported; statfs64 is an alias of it on LP64)
HOOK_DEF(int, statfs, __const char *__file, struct statfs *__buf) {
    int res;
    const char *redirect_path = relocate_path(__file, &res);
    int ret = syscall(__NR_statfs, redirect_path, __buf);
    FREE(redirect_path, __file);
    return ret;
}

// int lchown(const char *pathname, uid_t owner, gid_t group);
HOOK_DEF(int, lchown, const char *pathname, uid_t owner, gid_t group) {
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    int ret = syscall(__NR_lchown, redirect_path, owner, group);
    FREE(redirect_path, pathname);
    return ret;
}

int inline getArrayItemCount(char *const array[]) {
    int i;
    for (i = 0; array[i]; ++i);
    return i;
}


char **build_new_env(char *const envp[]) {
    char *provided_ld_preload = NULL;
    int provided_ld_preload_index = -1;
    int orig_envp_count = getArrayItemCount(envp);

    for (int i = 0; i < orig_envp_count; i++) {
        if (strstr(envp[i], "LD_PRELOAD")) {
            provided_ld_preload = envp[i];
            provided_ld_preload_index = i;
        }
    }
    static char ld_preload[600];
    const char *so_path = va_so_path();
    if (provided_ld_preload) {
        snprintf(ld_preload, sizeof(ld_preload), "LD_PRELOAD=%s:%s", so_path, provided_ld_preload + 11);
    } else {
        snprintf(ld_preload, sizeof(ld_preload), "LD_PRELOAD=%s", so_path);
    }
    static char env_so[600], env_pkg[300], env_api[30], env_papi[30];
    int va_count = 0;
    if (s_so_path[0]) { snprintf(env_so, sizeof(env_so), "V_SO_PATH=%s", s_so_path); va_count++; }
    if (s_guest_pkg[0]) { snprintf(env_pkg, sizeof(env_pkg), "V_GUEST_PKG=%s", s_guest_pkg); va_count++; }
    if (s_api_level) { snprintf(env_api, sizeof(env_api), "V_API_LEVEL=%d", s_api_level); va_count++; }
    if (s_preview_api_level) { snprintf(env_papi, sizeof(env_papi), "V_PREVIEW_API_LEVEL=%d", s_preview_api_level); va_count++; }
    int new_envp_count = orig_envp_count
                         + get_keep_item_count()
                         + get_forbidden_item_count()
                         + get_replace_item_count() * 2 + va_count + 2;
    if (provided_ld_preload) {
        new_envp_count--;
    }
    char **new_envp = (char **) malloc(new_envp_count * sizeof(char *));
    int cur = 0;
    new_envp[cur++] = ld_preload;
    for (int i = 0; i < orig_envp_count; ++i) {
        if (i != provided_ld_preload_index) {
            new_envp[cur++] = envp[i];
        }
    }
    if (s_so_path[0]) new_envp[cur++] = env_so;
    if (s_guest_pkg[0]) new_envp[cur++] = env_pkg;
    if (s_api_level) new_envp[cur++] = env_api;
    if (s_preview_api_level) new_envp[cur++] = env_papi;
    new_envp[cur] = NULL;
    return new_envp;
}

// int (*origin_execve)(const char *pathname, char *const argv[], char *const envp[]);
HOOK_DEF(int, execve, const char *pathname, char *argv[], char *const envp[]) {
    /**
     * CANNOT LINK EXECUTABLE "/system/bin/cat": "/data/app/io.virtualapp-1/lib/arm/libva-native.so" is 32-bit instead of 64-bit.
     *
     * We will support 64Bit to adopt it.
     */
    // ALOGE("execve : %s", pathname); // any output can break exec. See bug: https://issuetracker.google.com/issues/109448553
    int res;
    const char *redirect_path = relocate_path(pathname, &res);
    char *ld = getenv("LD_PRELOAD");
    if (ld) {
        if (strstr(ld, "libNimsWrap.so") || strstr(ld, "stamina.so")) {
            int ret = syscall(__NR_execve, redirect_path, argv, envp);
            FREE(redirect_path, pathname);
            return ret;
        }
    }
    if (strstr(pathname, "dex2oat")) {
        char **new_envp = build_new_env(envp);
        int ret = syscall(__NR_execve, redirect_path, argv, new_envp);
        FREE(redirect_path, pathname);
        free(new_envp);
        return ret;
    }
    int ret = syscall(__NR_execve, redirect_path, argv, envp);
    FREE(redirect_path, pathname);
    return ret;
}


HOOK_DEF(void*, dlopen, const char *filename, int flag) {
    int res;
    const char *redirect_path = relocate_path(filename, &res);
    void *ret = orig_dlopen(redirect_path, flag);
    onSoLoaded(filename, ret);
    ALOGD("dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, filename);
    return ret;
}

HOOK_DEF(void*, do_dlopen_V19, const char *filename, int flag, const void *extinfo) {
    int res;
    const char *redirect_path = relocate_path(filename, &res);
    void *ret = orig_do_dlopen_V19(redirect_path, flag, extinfo);
    onSoLoaded(filename, ret);
    ALOGD("do_dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, filename);
    return ret;
}

HOOK_DEF(void*, do_dlopen_V24, const char *name, int flags, const void *extinfo,
         void *caller_addr) {
    int res;
    const char *redirect_path = relocate_path(name, &res);
    void *ret = orig_do_dlopen_V24(redirect_path, flags, extinfo, caller_addr);
    onSoLoaded(name, ret);
    ALOGD("do_dlopen : %s, return : %p.", redirect_path, ret);
    FREE(redirect_path, name);
    return ret;
}



// Return our hook functions from dlsym so LIAPP sees clean prologues
// instead of Substrate trampolines at the start of hooked libc functions.
struct sym_redirect {
    const char *name;
    void *replacement;
};

static void *dlsym_find_redirect(const char *sym) {
    static const struct sym_redirect redirects[] = {
        {"openat",          (void *)new_openat},
        {"open",            (void *)new_open},
        {"readlink",        (void *)new_readlink},
        {"readlinkat",      (void *)new_readlinkat},
        {"faccessat",       (void *)new_faccessat},
        {"fstatat64",       (void *)new_fstatat64},
        {"dl_iterate_phdr", (void *)new_dl_iterate_phdr},
        {"dladdr",          (void *)new_dladdr},
        {"dlopen",          (void *)new_dlopen},
        {"prctl",           (void *)new_prctl},
        {"syscall",         (void *)new_syscall},
        {"chdir",           (void *)new_chdir},
        {"execve",          (void *)new_execve},
        {"statfs",          (void *)new_statfs},
        {"truncate",        (void *)new_truncate},
        {"truncate64",      (void *)new_truncate64},
        // Write-side / metadata path functions. These MUST be redirected when
        // resolved via dlsym too, otherwise a packer that pulls raw libc
        // pointers (to dodge PLT trampolines) writes/creates OUTSIDE the VA
        // redirect. In particular LIAPP/XShield mkdir()'s its decrypt
        // workspace and then writes the decrypted odex there; handing it the
        // real libc mkdir makes that dir land on the un-relocated path and the
        // unpack silently fails, so the protected activity class is never
        // injected (ClassNotFoundException at launch). All wrappers below
        // already exist and are PLT-hooked; the dlsym table was just missing
        // them.
        {"mkdir",           (void *)new_mkdir},
        {"mkdirat",         (void *)new_mkdirat},
        {"rmdir",           (void *)new_rmdir},
        {"stat",            (void *)new_stat},
        {"lstat",           (void *)new_lstat},
        {"fstatat",         (void *)new_fstatat},
        {"rename",          (void *)new_rename},
        {"renameat",        (void *)new_renameat},
        {"unlink",          (void *)new_unlink},
        {"unlinkat",        (void *)new_unlinkat},
        {"access",          (void *)new_access},
        {"chmod",           (void *)new_chmod},
        {"fchmodat",        (void *)new_fchmodat},
        {"chown",           (void *)new_chown},
        {"fchownat",        (void *)new_fchownat},
        {"lchown",          (void *)new_lchown},
        {"mknod",           (void *)new_mknod},
        {"mknodat",         (void *)new_mknodat},
        {"link",            (void *)new_link},
        {"linkat",          (void *)new_linkat},
        {"symlink",         (void *)new_symlink},
        {"symlinkat",       (void *)new_symlinkat},
        {"utimes",          (void *)new_utimes},
        {"utimensat",       (void *)new_utimensat},
        {"chroot",          (void *)new_chroot},
    };
    for (size_t i = 0; i < sizeof(redirects)/sizeof(redirects[0]); i++) {
        if (strcmp(sym, redirects[i].name) == 0)
            return redirects[i].replacement;
    }
    return NULL;
}

//void *dlsym(void *handle,const char *symbol)
HOOK_DEF(void*, dlsym, void *handle, char *symbol) {
    if (symbol && strcmp(symbol, "dlsym") == 0) {
        ALOGD("dlsym : dlsym -> redirect (self)");
        return (void *)new_dlsym;
    }
    void *redirect = dlsym_find_redirect(symbol);
    if (redirect) {
        ALOGD("dlsym : %s -> redirect", symbol);
        return redirect;
    }
    void *real = orig_dlsym(handle, symbol);
    if (symbol) {
        ALOGD("dlsym : %s -> passthrough %p", symbol, real);
    }
    return real;
}

// int kill(pid_t pid, int sig);
HOOK_DEF(int, kill, pid_t pid, int sig) {
    ALOGD(">>>>> kill >>> pid: %d, sig: %d.", pid, sig);
    int ret = syscall(__NR_kill, pid, sig);
    return ret;
}

HOOK_DEF(pid_t, vfork) {
    return fork();
}

__END_DECLS
// end IO DEF


void onSoLoaded(const char *name, void *handle) {
}

int findSymbol(const char *name, const char *libn,
               unsigned long *addr) {
    return find_name(getpid(), name, libn, addr);
}

void hook_dlopen(int api_level) {
    void *symbol = NULL;
    if (api_level > 23) {
        // linker64 on Android 7+ is stripped: the internal do_dlopen cannot be resolved by name
        // (no .symtab/.dynsym entry, so every lookup failed with "elf error"). Hook the exported
        // dlopen wrapper instead; it is resolvable with dlsym and covers direct dlopen() callers.
        symbol = dlsym(RTLD_DEFAULT, "dlopen");
        if (symbol != NULL) {
            MSHookFunction(symbol, (void *) new_dlopen, (void **) &orig_dlopen);
            ALOGD("VAX-IO dlopen hook installed on exported dlopen");
        }
    } else if (api_level >= 19) {
        if (findSymbol("__dl__Z9do_dlopenPKciPK17android_dlextinfo", "linker",
                       (unsigned long *) &symbol) == 0) {
            MSHookFunction(symbol, (void *) new_do_dlopen_V19,
                          (void **) &orig_do_dlopen_V19);
        }
    } else {
        if (findSymbol("__dl_dlopen", "linker",
                       (unsigned long *) &symbol) == 0) {
            MSHookFunction(symbol, (void *) new_dlopen, (void **) &orig_dlopen);
        }
    }
}


void IOUniformer::startUniformer(const char *so_path, int api_level, int preview_api_level) {
    char api_level_chars[5];
    setenv("V_SO_PATH", so_path, 1);
    sprintf(api_level_chars, "%i", api_level);
    setenv("V_API_LEVEL", api_level_chars, 1);
    sprintf(api_level_chars, "%i", preview_api_level);
    setenv("V_PREVIEW_API_LEVEL", api_level_chars, 1);

    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle) {
        HOOK_SYMBOL(handle, faccessat);
        HOOK_SYMBOL(handle, __openat);
        HOOK_SYMBOL(handle, openat);
        HOOK_SYMBOL(handle, open);
        HOOK_SYMBOL(handle, fchmodat);
        HOOK_SYMBOL(handle, fchownat);
        HOOK_SYMBOL(handle, renameat);
        HOOK_SYMBOL(handle, fstatat64);
        HOOK_SYMBOL(handle, statfs);
        HOOK_SYMBOL(handle, mkdirat);
        HOOK_SYMBOL(handle, mknodat);
        HOOK_SYMBOL(handle, truncate);
        HOOK_SYMBOL(handle, linkat);
        HOOK_SYMBOL(handle, readlinkat);
        HOOK_SYMBOL(handle, unlinkat);
        HOOK_SYMBOL(handle, symlinkat);
        HOOK_SYMBOL(handle, utimensat);
//        HOOK_SYMBOL(handle, __getdents64);
        HOOK_SYMBOL(handle, chdir);
        HOOK_SYMBOL(handle, execve);
        if (api_level <= 20) {
            HOOK_SYMBOL(handle, access);
            HOOK_SYMBOL(handle, __open);
            HOOK_SYMBOL(handle, stat);
            HOOK_SYMBOL(handle, lstat);
            HOOK_SYMBOL(handle, fstatat);
            HOOK_SYMBOL(handle, chmod);
            HOOK_SYMBOL(handle, chown);
            HOOK_SYMBOL(handle, rename);
            HOOK_SYMBOL(handle, rmdir);
            HOOK_SYMBOL(handle, mkdir);
            HOOK_SYMBOL(handle, mknod);
            HOOK_SYMBOL(handle, link);
            HOOK_SYMBOL(handle, unlink);
            HOOK_SYMBOL(handle, readlink);
            HOOK_SYMBOL(handle, symlink);
//            HOOK_SYMBOL(handle, getdents);
//            HOOK_SYMBOL(handle, execv);
        }
        HOOK_SYMBOL(handle, syscall);
        HOOK_SYMBOL(handle, prctl);
        dlclose(handle);
    }
    hook_dlopen(api_level);

    void *dl_handle = dlopen("libdl.so", RTLD_NOW);
    if (dl_handle) {
        HOOK_SYMBOL(dl_handle, dladdr);
        HOOK_SYMBOL(dl_handle, dl_iterate_phdr);
        HOOK_SYMBOL(dl_handle, dlsym);
        dlclose(dl_handle);
    }

    snprintf(s_so_path, sizeof(s_so_path), "%s", so_path);
    const char *_pkg = getenv("V_GUEST_PKG");
    if (_pkg) snprintf(s_guest_pkg, sizeof(s_guest_pkg), "%s", _pkg);
    s_api_level = api_level;
    s_preview_api_level = preview_api_level;

    unsetenv("LD_PRELOAD");
    unsetenv("V_SO_PATH");
    unsetenv("V_GUEST_PKG");
    unsetenv("V_API_LEVEL");
    unsetenv("V_PREVIEW_API_LEVEL");
}
