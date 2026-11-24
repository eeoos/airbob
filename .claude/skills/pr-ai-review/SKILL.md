---
name: pr-ai-review
description: AI 코드 리뷰 절차. 코드가 바뀐 PR을 push하기 전에 Codex로 브랜치 diff를 리뷰하고, PR을 연 뒤 Claude 리뷰 댓글이 달리면 지적을 검증·판정·기록할 때 사용한다.
---

# AI 코드 리뷰 절차

리뷰어는 [리뷰 기준](../../../docs/development/code-review.md)을 따른다. 판정과 지표의 뜻은 [AI 리뷰 기록](../../../docs/development/ai-review-log.md)에 있고, 결과도 그 파일에 남긴다.

## A. PR 전: Codex 리뷰

1. **범위를 정한다.**
   - base는 PR이 향할 브랜치다. 보통 `origin/main`이다. 이전 PR 브랜치 위에 쌓았으면 그 브랜치다.
   - `git diff --stat <base>...HEAD`로 확인한다.
   - 문서(`docs/`, `*.md`)만 바뀌었으면 건너뛰고, PR 본문에 "문서만 바뀌어 생략"이라고 쓴다.
2. **Codex를 실행한다.**
   - Claude 샌드박스 밖에서 실행한다(`dangerouslyDisableSandbox`). 네트워크와 `~/.codex`가 필요하다.
   - 몇 분 걸리므로 백그라운드로 돌리고, 시작과 끝 시각을 적는다.

   ```
   mkdir -p build/review
   codex exec -s read-only --ephemeral -o build/review/codex-<head 8자리>.md "<지시문>"
   ```

   지시문:
   > 이 저장소의 `<base>...HEAD` 변경을 리뷰하라. `git log`와 `git diff <base>...HEAD`로 변경을 보고, 필요하면 저장소 파일을 읽어라. 파일은 수정하지 마라. 기준은 docs/development/code-review.md다. 변경 의도: <PR 요약 한두 줄>.

   - `codex exec review --commit`은 지시문과 함께 쓸 수 없다. 그래서 일반 `exec`를 쓴다.
   - 모델과 추론 수준은 `~/.codex/config.toml`을 따른다. 기록할 때 실제 값을 적는다.
3. **지적마다 검증하고 판정한다.**
   - 코드를 읽고, 가능하면 테스트나 메모리 입력으로 재현한다.
   - 판정: 고침, 보류, 넘김, 오탐, 사용자 결정
   - 계약(API, DB, 잠금 순서)을 바꾸는 지적이나 기존 규칙과 충돌하는 지적이면 멈추고 사용자에게 묻는다.
4. **고친다.**
   - 고침으로 판정한 것은 테스트와 함께 고쳐 커밋한다.
   - 수정이 로직(main 코드, 스크립트, 워크플로)을 건드리면 수정 커밋만 Codex로 다시 리뷰한다. 같은 명령에서 base를 수정 직전 커밋으로 둔다.
   - 문서나 테스트만 고쳤으면 다시 리뷰하지 않는다.
   - 다시 리뷰한 결과는 기록에 따로 한 줄로 남긴다.
5. **기록한다.**
   - `docs/development/ai-review-log.md`에 한 줄을 추가하고 브랜치에 커밋한다.
   - PR 본문에 아래 절을 넣는다.

   ```
   ## AI 리뷰
   | 단계 | 리뷰어 | 지적 | 고침 | 보류 | 넘김 | 오탐 |
   |---|---|---:|---:|---:|---:|---:|
   | PR 전 | Codex <모델>, <수준> | … | … | … | … | … |

   <details><summary>지적별 판정</summary>

   | # | 심각도 | 지적 | 판정 | 근거 |
   |---|---|---|---|---|
   </details>
   ```

## B. PR 후: Claude 리뷰

1. **실행을 기다린다.**
   - `claude-review.yml`은 PR을 열 때 돈다.
   - `gh run list --workflow claude-review.yml --branch <브랜치>`로 실행을 찾고, 끝날 때까지 기다린다.
2. **결과를 읽는다.**
   - 요약 댓글은 `<!-- airbob-claude-review -->`로 시작한다. `gh pr view <번호> --comments`로 읽는다. 다시 리뷰하면 요약 댓글이 하나 더 생기므로 가장 최근 것을 본다.
   - 인라인 댓글은 `gh api repos/{owner}/{repo}/pulls/<번호>/comments`로 읽는다.
   - 비용은 실행 로그의 비용 값이나 Console에서 확인한다.
3. **검증하고 고친다.** A의 3·4와 같다. 지적마다 Codex가 이미 낸 것인지 표시한다. 이 표시가 "2단계 추가 발견"의 근거가 된다.
4. **기록한다.**
   - PR 본문의 "AI 리뷰" 표에 PR 후 행을 추가한다(`gh pr edit <번호> --body-file <파일>`).
   - 기록 파일에 한 줄을 추가해 커밋하고 push한다.
5. **다시 리뷰가 필요하면** PR에 `claude-review` 라벨을 붙인다. 이미 붙어 있으면 뗐다가 다시 붙인다.
