//
// arm64 has no "legacy" syscalls (open, stat64, chmod, rename, ...): only the *at() family
// exists. IOUniformer was written against 32-bit ARM, so on aarch64 this header remaps the
// missing __NR_* numbers to pseudo numbers and routes syscall() through va_legacy_syscall(),
// which translates them to the *at() equivalents.
//
// Include AFTER <unistd.h>/<sys/syscall.h>.
//
#ifndef VIRTUALAPP_LEGACYSYSCALL_H
#define VIRTUALAPP_LEGACYSYSCALL_H

#if defined(__aarch64__)

#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <errno.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <time.h>

// aliases that exist under another name on arm64
#ifndef __NR_fstatat64
#define __NR_fstatat64 __NR_newfstatat
#endif
#ifndef __NR_fstat64
#define __NR_fstat64 __NR_fstat
#endif
#ifndef __NR_statfs64
#define __NR_statfs64 __NR_statfs
#endif
#ifndef __NR_truncate64
#define __NR_truncate64 __NR_truncate
#endif

// pseudo numbers (outside the kernel's range) for calls that only exist as *at() variants
#define VA_NR_BASE 0x40000000L

enum {
    VA_PSEUDO_open = VA_NR_BASE + 1,
    VA_PSEUDO_chmod,
    VA_PSEUDO_chown,
    VA_PSEUDO_lchown,
    VA_PSEUDO_access,
    VA_PSEUDO_mknod,
    VA_PSEUDO_rename,
    VA_PSEUDO_unlink,
    VA_PSEUDO_rmdir,
    VA_PSEUDO_mkdir,
    VA_PSEUDO_symlink,
    VA_PSEUDO_link,
    VA_PSEUDO_readlink,
    VA_PSEUDO_stat64,
    VA_PSEUDO_lstat64,
    VA_PSEUDO_utimes,
};

#ifndef __NR_open
#define __NR_open VA_PSEUDO_open
#endif
#ifndef __NR_chmod
#define __NR_chmod VA_PSEUDO_chmod
#endif
#ifndef __NR_chown
#define __NR_chown VA_PSEUDO_chown
#endif
#ifndef __NR_lchown
#define __NR_lchown VA_PSEUDO_lchown
#endif
#ifndef __NR_access
#define __NR_access VA_PSEUDO_access
#endif
#ifndef __NR_mknod
#define __NR_mknod VA_PSEUDO_mknod
#endif
#ifndef __NR_rename
#define __NR_rename VA_PSEUDO_rename
#endif
#ifndef __NR_unlink
#define __NR_unlink VA_PSEUDO_unlink
#endif
#ifndef __NR_rmdir
#define __NR_rmdir VA_PSEUDO_rmdir
#endif
#ifndef __NR_mkdir
#define __NR_mkdir VA_PSEUDO_mkdir
#endif
#ifndef __NR_symlink
#define __NR_symlink VA_PSEUDO_symlink
#endif
#ifndef __NR_link
#define __NR_link VA_PSEUDO_link
#endif
#ifndef __NR_readlink
#define __NR_readlink VA_PSEUDO_readlink
#endif
#ifndef __NR_stat64
#define __NR_stat64 VA_PSEUDO_stat64
#endif
#ifndef __NR_lstat64
#define __NR_lstat64 VA_PSEUDO_lstat64
#endif
#ifndef __NR_utimes
#define __NR_utimes VA_PSEUDO_utimes
#endif

static inline long va_legacy_syscall(long nr, ...) {
    va_list ap;
    va_start(ap, nr);
    long a0 = va_arg(ap, long), a1 = va_arg(ap, long), a2 = va_arg(ap, long);
    long a3 = va_arg(ap, long), a4 = va_arg(ap, long), a5 = va_arg(ap, long);
    va_end(ap);

    if (nr < VA_NR_BASE) {
        return syscall(nr, a0, a1, a2, a3, a4, a5);
    }
    const long cwd = AT_FDCWD;
    switch (nr) {
        case VA_PSEUDO_open:     // open(path, flags, mode)
            return syscall(__NR_openat, cwd, a0, a1, a2);
        case VA_PSEUDO_chmod:    // chmod(path, mode)
            return syscall(__NR_fchmodat, cwd, a0, a1, 0L);
        case VA_PSEUDO_chown:    // chown(path, uid, gid)
            return syscall(__NR_fchownat, cwd, a0, a1, a2, 0L);
        case VA_PSEUDO_lchown:   // lchown(path, uid, gid)
            return syscall(__NR_fchownat, cwd, a0, a1, a2, (long) AT_SYMLINK_NOFOLLOW);
        case VA_PSEUDO_access:   // access(path, mode)
            return syscall(__NR_faccessat, cwd, a0, a1, 0L);
        case VA_PSEUDO_mknod:    // mknod(path, mode, dev)
            return syscall(__NR_mknodat, cwd, a0, a1, a2);
        case VA_PSEUDO_rename:   // rename(old, new)
            return syscall(__NR_renameat, cwd, a0, cwd, a1);
        case VA_PSEUDO_unlink:   // unlink(path)
            return syscall(__NR_unlinkat, cwd, a0, 0L);
        case VA_PSEUDO_rmdir:    // rmdir(path)
            return syscall(__NR_unlinkat, cwd, a0, (long) AT_REMOVEDIR);
        case VA_PSEUDO_mkdir:    // mkdir(path, mode)
            return syscall(__NR_mkdirat, cwd, a0, a1);
        case VA_PSEUDO_symlink:  // symlink(target, linkpath)
            return syscall(__NR_symlinkat, a0, cwd, a1);
        case VA_PSEUDO_link:     // link(old, new)
            return syscall(__NR_linkat, cwd, a0, cwd, a1, 0L);
        case VA_PSEUDO_readlink: // readlink(path, buf, size)
            return syscall(__NR_readlinkat, cwd, a0, a1, a2);
        case VA_PSEUDO_stat64:   // stat(path, buf)
            return syscall(__NR_newfstatat, cwd, a0, a1, 0L);
        case VA_PSEUDO_lstat64:  // lstat(path, buf)
            return syscall(__NR_newfstatat, cwd, a0, a1, (long) AT_SYMLINK_NOFOLLOW);
        case VA_PSEUDO_utimes: { // utimes(path, struct timeval[2])
            const struct timeval *tv = (const struct timeval *) a1;
            if (!tv) {
                return syscall(__NR_utimensat, cwd, a0, 0L, 0L);
            }
            struct timespec ts[2];
            for (int i = 0; i < 2; i++) {
                ts[i].tv_sec = tv[i].tv_sec;
                ts[i].tv_nsec = tv[i].tv_usec * 1000L;
            }
            return syscall(__NR_utimensat, cwd, a0, (long) ts, 0L);
        }
        default:
            errno = ENOSYS;
            return -1;
    }
}

// Route every syscall() in files that include this header through the translator.
#define syscall(...) va_legacy_syscall(__VA_ARGS__)

#endif // __aarch64__

#endif // VIRTUALAPP_LEGACYSYSCALL_H
