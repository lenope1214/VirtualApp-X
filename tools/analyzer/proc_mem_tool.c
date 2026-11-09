/**
 * proc_mem_tool.c
 * 
 * 시스템 파일 인터페이스(/proc/<pid>/mem 및 /proc/<pid>/maps)를 활용하여
 * 대상 프로세스의 가상 메모리 영역을 탐색, 검색, 읽기 및 쓰기 작업을 수행하는
 * 시스템 레벨 C/NDK 구현체.
 * 
 * 기능:
 * 1. /proc/<pid>/maps 파싱 및 유효 가상 메모리 영역(VMA) 탐색
 * 2. /proc/<pid>/mem 기반의 안전한 메모리 읽기/쓰기 (pread64, pwrite64)
 * 3. Linux Syscall(process_vm_readv, process_vm_writev) 대체 경로 제공
 * 4. SIGSEGV / SIGBUS 시그널 핸들러 및 sigsetjmp를 통한 비정상 메모리 접근 크래시 방지
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <setjmp.h>
#include <stdint.h>
#include <inttypes.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/uio.h>

#define MAX_LINE_LEN 1024
#define MAX_VMAS 4096

// 가상 메모리 영역 정보 구조체
typedef struct {
    uintptr_t start;
    uintptr_t end;
    char perms[5];      // "rwxp", "r--p" 등
    uint64_t offset;
    char dev[12];
    uint64_t inode;
    char pathname[256];
} vm_area_t;

// 프로세스 메모리 핸들
typedef struct {
    pid_t pid;
    int mem_fd;
    int is_writable;
    vm_area_t vmas[MAX_VMAS];
    size_t vma_count;
} proc_mem_t;

// 크래시 방지용 시그널 점프 버퍼
static sigjmp_buf g_segv_jmp_buf;
static volatile sig_atomic_t g_handling_segv = 0;

static void segv_signal_handler(int sig, siginfo_t *info, void *context) {
    (void)sig;
    (void)info;
    (void)context;
    if (g_handling_segv) {
        siglongjmp(g_segv_jmp_buf, 1);
    }
    // 기본 핸들러로 전달
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_protection(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = segv_signal_handler;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
}

/**
 * /proc/<pid>/maps 파일을 파싱하여 프로세스의 메모리 매핑 목록을 추출.
 */
int parse_proc_maps(pid_t pid, vm_area_t *out_vmas, size_t max_count, size_t *out_count) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);

    FILE *fp = fopen(maps_path, "r");
    if (!fp) {
        fprintf(stderr, "[ERROR] Failed to open %s: %s (errno=%d)\n", maps_path, strerror(errno), errno);
        return -1;
    }

    char line[MAX_LINE_LEN];
    size_t count = 0;

    while (fgets(line, sizeof(line), fp) && count < max_count) {
        vm_area_t *vma = &out_vmas[count];
        memset(vma, 0, sizeof(vm_area_t));

        int n = sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %4s %" SCNx64 " %s %" SCNu64 " %255[^\n]",
                       &vma->start, &vma->end, vma->perms, &vma->offset, vma->dev, &vma->inode, vma->pathname);

        if (n >= 4) {
            count++;
        }
    }

    fclose(fp);
    *out_count = count;
    return 0;
}

/**
 * 대상 프로세스의 메모리 조작 세션 초기화
 */
proc_mem_t *proc_mem_open(pid_t pid) {
    proc_mem_t *ctx = (proc_mem_t *)calloc(1, sizeof(proc_mem_t));
    if (!ctx) return NULL;

    ctx->pid = pid;
    install_crash_protection();

    // 1. 메모리 맵 파싱
    if (parse_proc_maps(pid, ctx->vmas, MAX_VMAS, &ctx->vma_count) != 0) {
        free(ctx);
        return NULL;
    }

    // 2. /proc/<pid>/mem 오픈 (읽기/쓰기 시도 -> 실패 시 읽기 전용)
    char mem_path[64];
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);

    ctx->mem_fd = open(mem_path, O_RDWR | O_LARGEFILE);
    if (ctx->mem_fd >= 0) {
        ctx->is_writable = 1;
    } else {
        ctx->mem_fd = open(mem_path, O_RDONLY | O_LARGEFILE);
        ctx->is_writable = 0;
        if (ctx->mem_fd < 0) {
            fprintf(stderr, "[WARN] Failed to open %s (errno=%d: %s). May require root/ptrace.\n",
                    mem_path, errno, strerror(errno));
        }
    }

    return ctx;
}

/**
 * 프로세스 가상 메모리 읽기 (pread64 사용 및 크래시 안전 가드)
 */
ssize_t proc_mem_read(proc_mem_t *ctx, uintptr_t addr, void *buf, size_t size) {
    if (!ctx || !buf || size == 0) return -1;

    // 방법 1: /proc/<pid>/mem 파일 디스크립터가 유효한 경우
    if (ctx->mem_fd >= 0) {
        ssize_t n = pread64(ctx->mem_fd, buf, size, (off64_t)addr);
        if (n > 0) return n;
    }

    // 방법 2: process_vm_readv 시스템 콜 (Linux 3.2+ / Android 커널 기본 지원)
    struct iovec local_iov = { .iov_base = buf, .iov_len = size };
    struct iovec remote_iov = { .iov_base = (void *)addr, .iov_len = size };

    ssize_t nread = process_vm_readv(ctx->pid, &local_iov, 1, &remote_iov, 1, 0);
    if (nread >= 0) {
        return nread;
    }

    // 방법 3: 자기 자신(self) 프로세스일 경우 직접 메모리 복사 (시그널 보호 포함)
    if (ctx->pid == getpid()) {
        g_handling_segv = 1;
        if (sigsetjmp(g_segv_jmp_buf, 1) == 0) {
            memcpy(buf, (const void *)addr, size);
            g_handling_segv = 0;
            return (ssize_t)size;
        } else {
            g_handling_segv = 0;
            fprintf(stderr, "[SAFE_GUARD] Recovered from SIGSEGV while reading addr %p\n", (void *)addr);
            return -1;
        }
    }

    return -1;
}

/**
 * 프로세스 가상 메모리 쓰기 (pwrite64 또는 process_vm_writev)
 */
ssize_t proc_mem_write(proc_mem_t *ctx, uintptr_t addr, const void *buf, size_t size) {
    if (!ctx || !buf || size == 0) return -1;

    // 방법 1: /proc/<pid>/mem pwrite64
    if (ctx->mem_fd >= 0 && ctx->is_writable) {
        ssize_t n = pwrite64(ctx->mem_fd, buf, size, (off64_t)addr);
        if (n > 0) return n;
    }

    // 방법 2: process_vm_writev 시스템 콜
    struct iovec local_iov = { .iov_base = (void *)buf, .iov_len = size };
    struct iovec remote_iov = { .iov_base = (void *)addr, .iov_len = size };

    ssize_t nwritten = process_vm_writev(ctx->pid, &local_iov, 1, &remote_iov, 1, 0);
    if (nwritten >= 0) {
        return nwritten;
    }

    // 방법 3: 자기 자신 프로세스인 경우 mprotect 후 복사
    if (ctx->pid == getpid()) {
        uintptr_t page_size = sysconf(_SC_PAGESIZE);
        uintptr_t page_start = addr & ~(page_size - 1);
        size_t protect_len = ((addr + size - 1) & ~(page_size - 1)) - page_start + page_size;

        if (mprotect((void *)page_start, protect_len, PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
            g_handling_segv = 1;
            if (sigsetjmp(g_segv_jmp_buf, 1) == 0) {
                memcpy((void *)addr, buf, size);
                g_handling_segv = 0;
                return (ssize_t)size;
            } else {
                g_handling_segv = 0;
                fprintf(stderr, "[SAFE_GUARD] Recovered from SIGSEGV while writing addr %p\n", (void *)addr);
                return -1;
            }
        }
    }

    return -1;
}

/**
 * 특정 모듈 또는 VMA 영역 내에서 시그니처 바이트 패턴 탐색
 */
uintptr_t proc_mem_scan(proc_mem_t *ctx, const char *module_name, const uint8_t *pattern, const char *mask, size_t pattern_len) {
    if (!ctx || !pattern || !mask || pattern_len == 0) return 0;

    for (size_t i = 0; i < ctx->vma_count; i++) {
        vm_area_t *vma = &ctx->vmas[i];

        // 읽기 권한이 없는 영역은 건너뜀
        if (vma->perms[0] != 'r') continue;

        // 특정 모듈 필터링이 있을 경우 확인
        if (module_name && strlen(module_name) > 0) {
            if (strstr(vma->pathname, module_name) == NULL) {
                continue;
            }
        }

        size_t vma_size = vma->end - vma->start;
        if (vma_size < pattern_len) continue;

        // 블록 단위(32KB)로 안전하게 버퍼 읽기 수행
        size_t chunk_size = 32 * 1024;
        uint8_t chunk_buf[32 * 1024 + 256];

        for (uintptr_t cur = vma->start; cur < vma->end - pattern_len; cur += (chunk_size - pattern_len)) {
            size_t bytes_to_read = (cur + chunk_size <= vma->end) ? chunk_size : (vma->end - cur);
            ssize_t read_bytes = proc_mem_read(ctx, cur, chunk_buf, bytes_to_read);
            if (read_bytes <= 0) break;

            for (size_t offset = 0; offset <= (size_t)(read_bytes - pattern_len); offset++) {
                int found = 1;
                for (size_t k = 0; k < pattern_len; k++) {
                    if (mask[k] != '?' && chunk_buf[offset + k] != pattern[k]) {
                        found = 0;
                        break;
                    }
                }
                if (found) {
                    uintptr_t match_addr = cur + offset;
                    printf("[SCAN] Pattern matched at %p (VMA: %s, Perms: %s)\n",
                           (void *)match_addr, vma->pathname, vma->perms);
                    return match_addr;
                }
            }
        }
    }

    return 0;
}

void proc_mem_close(proc_mem_t *ctx) {
    if (!ctx) return;
    if (ctx->mem_fd >= 0) {
        close(ctx->mem_fd);
    }
    free(ctx);
}
