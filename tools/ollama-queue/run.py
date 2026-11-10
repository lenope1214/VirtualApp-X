#!/usr/bin/env python3
"""ollama.md 작업 큐 실행기.

ollama.md 안의 `## TASK <id> [pending]` 항목을 로컬 ollama에 보내고, 결과를 같은 파일에
`### RESULT` 로 덧붙인 뒤 상태를 [done] / [error] 로 바꾼다. 표준 라이브러리만 사용.
요청은 localhost(ollama)로만 나간다.

사용:
  python tools/ollama-queue/run.py --once                 # 한 번 처리하고 종료
  python tools/ollama-queue/run.py --interval 300         # 5분마다 반복
  python tools/ollama-queue/run.py --file X.md --model deepseek-r1:8b
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_FILE = os.path.join(ROOT, "ollama.md")
HEADER_RE = re.compile(r"(?m)^## TASK (\S+) \[(pending|done|error)\][ \t]*$")
THINK_RE = re.compile(r"<think>.*?</think>", re.S)


def parse(text):
    """[(id, status, start, end)] — end 는 다음 TASK 헤더(또는 EOF) 직전."""
    ms = list(HEADER_RE.finditer(text))
    out = []
    for i, m in enumerate(ms):
        end = ms[i + 1].start() if i + 1 < len(ms) else len(text)
        out.append((m.group(1), m.group(2), m.start(), end))
    return out


def prompt_of(block):
    """헤더 줄 이후 ~ 기존 RESULT 이전까지가 프롬프트."""
    body = block.split("\n", 1)[1] if "\n" in block else ""
    return body.split("### RESULT", 1)[0].strip()


def ask(host, model, prompt, timeout):
    req = urllib.request.Request(
        host.rstrip("/") + "/api/generate",
        data=json.dumps({"model": model, "prompt": prompt, "stream": False}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.load(r).get("response", "")
    return THINK_RE.sub("", resp).strip()


def write_atomic(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, path)


def process_one(path, task_id, args):
    """파일을 다시 읽어 해당 task 만 갱신 (처리 중 사용자가 편집한 내용 보존)."""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    cur = [t for t in parse(text) if t[0] == task_id and t[1] == "pending"]
    if not cur:
        return
    _, _, start, end = cur[0]
    prompt = prompt_of(text[start:end])
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    try:
        answer, status = ask(args.host, args.model, prompt, args.timeout), "done"
    except Exception as e:  # 연결 실패/타임아웃 등
        answer, status = "%s: %s" % (type(e).__name__, e), "error"
    # 응답 대기 중 파일이 바뀌었을 수 있으니 다시 읽고 위치 재계산
    with open(path, encoding="utf-8") as f:
        text = f.read()
    cur = [t for t in parse(text) if t[0] == task_id and t[1] == "pending"]
    if not cur:
        return
    _, _, start, end = cur[0]
    block = text[start:end]
    head, rest = block.split("\n", 1) if "\n" in block else (block, "")
    new_head = "## TASK %s [%s]" % (task_id, status)
    prompt_part = rest.split("### RESULT", 1)[0].rstrip()
    new_block = "%s\n%s\n\n### RESULT (%s, %s)\n%s\n\n" % (
        new_head, prompt_part, args.model, stamp, answer)
    write_atomic(path, text[:start] + new_block + text[end:])
    print("[%s] task %s -> %s" % (stamp, task_id, status), flush=True)


def run_once(args):
    if not os.path.isfile(args.file):
        print("파일 없음: %s" % args.file, file=sys.stderr)
        return 0
    with open(args.file, encoding="utf-8") as f:
        pending = [t[0] for t in parse(f.read()) if t[1] == "pending"]
    for tid in pending:
        process_one(args.file, tid, args)
    return len(pending)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", default=DEFAULT_FILE)
    ap.add_argument("--model", default="deepseek-r1:8b")
    ap.add_argument("--host", default="http://localhost:11434")
    ap.add_argument("--timeout", type=int, default=900, help="작업당 초")
    ap.add_argument("--interval", type=int, default=0, help="초; 0 이면 --once 와 동일")
    ap.add_argument("--once", action="store_true")
    args = ap.parse_args()

    lock = args.file + ".lock"
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(fd, str(os.getpid()).encode())
        os.close(fd)
    except FileExistsError:
        print("이미 실행 중이거나 stale lock: %s (프로세스 없으면 삭제)" % lock, file=sys.stderr)
        return 1
    try:
        if args.once or args.interval <= 0:
            run_once(args)
        else:
            while True:
                run_once(args)
                time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            os.remove(lock)
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
