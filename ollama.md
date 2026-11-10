# ollama 작업 큐

`tools/ollama-queue/run.py` 가 이 파일의 `[pending]` 작업을 로컬 ollama(deepseek-r1:8b)로 처리하고,
결과를 해당 작업 아래 `### RESULT` 로 덧붙이며 상태를 `[done]` / `[error]` 로 바꾼다.

## 형식
```
## TASK <id> [pending]
<ollama 에 보낼 프롬프트 — 자체 완결적으로 쓸 것. 모델은 이 파일 밖의 파일/대화를 못 본다>
```

## 실행
- 한 번: `python tools/ollama-queue/run.py --once`
- 주기적: `python tools/ollama-queue/run.py --interval 300`

## 넣어도 되는 것 / 안 되는 것
- 넣는 것: 로그·문서 요약, 보일러플레이트 초안, 번역, 권한 프롬프트나 도구 제약으로 지금 못 하는 단순 작업, 오래 걸리는 분석.
- 넣지 않는 것: Claude 가 안전상 거절한 작업(예: 타깃 앱의 무결성/환경탐지 우회). 큐는 우회 경로가 아니다.
- 결과는 8B 모델 출력이므로 사용 전에 검토한다. 코드는 자동 적용하지 않는다.

---

