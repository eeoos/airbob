# Batch Fetch Size Harness Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the completed Hibernate batch-fetch-size experiment while keeping the N+1 reproduction profile and the remaining k6 benchmark suites intact.

**Architecture:** Delete the six tracked files that form the isolated batch-fetch-size harness and remove only its navigation entries from the k6 overview. Do not touch `application-nplus1-benchmark.yaml`, because `default_batch_fetch_size: 0` is still required to reproduce the recently viewed N+1 baseline.

**Tech Stack:** k6 JavaScript, Python analysis scripts, Bash, Markdown, Git.

## Global Constraints

- Delete only the tracked `load-test/k6/batch-fetch-size` harness files and their live k6 overview references.
- Preserve `src/main/resources/application-nplus1-benchmark.yaml` and its `spring.jpa.properties.hibernate.default_batch_fetch_size: 0` setting.
- Preserve the already-written k6 overview and all pre-existing dirty read-model edits.
- Keep `asg-scale-test.js` and `search-load-test.js` deleted.
- `load-test/k6/batch-fetch-size/core-get.js` currently has an uncommitted sorting improvement; deleting the entire harness intentionally supersedes that edit.
- Stage only the cleanup files and inspect the cached diff before committing.

## File Map

- `load-test/k6/README.md`: navigation for the remaining three benchmark categories.
- `load-test/k6/batch-fetch-size/*`: self-contained experimental runner, analyzer, tests, and local ignore rule to remove.
- `application-nplus1-benchmark.yaml`: explicitly out of scope and retained.

---

### Task 1: Remove the batch-fetch-size experiment

**Files:**

- Delete: `load-test/k6/batch-fetch-size/.gitignore`
- Delete: `load-test/k6/batch-fetch-size/README.md`
- Delete: `load-test/k6/batch-fetch-size/analyze.py`
- Delete: `load-test/k6/batch-fetch-size/core-get.js`
- Delete: `load-test/k6/batch-fetch-size/evaluate.sh`
- Delete: `load-test/k6/batch-fetch-size/test_analyze.py`
- Modify: `load-test/k6/README.md`

**Interfaces:**

- Produces: a k6 overview containing only read-model, recently-viewed N+1, and coupon benchmark entry points.
- Preserves: `default_batch_fetch_size: 0` for the N+1 benchmark profile.

- [ ] **Step 1: Record the pre-cleanup failing audit**

```bash
test -z "$(git ls-files 'load-test/k6/batch-fetch-size/**')"
rg -n "batch-fetch-size|Hibernate batch fetch 크기" load-test/k6/README.md
```

Expected: the first command exits non-zero because six tracked harness files remain, and the second command finds the overview table row and internal-file bullet.

- [ ] **Step 2: Update the k6 overview without reverting its current edits**

Change the introduction to:

```markdown
이 디렉터리에는 서로 독립적인 세 가지 성능 실험이 함께 있다. 먼저 측정 목적을 고른 뒤 해당 진입점과 README만 보면 된다.
```

Keep this exact three-row table:

```markdown
| 측정 목적 | 직접 실행할 진입점 | 상세 가이드 |
|---|---|---|
| 반정규화 before/after | `read-model/*-comparison.js` 3개 | [read-model/README.md](read-model/README.md) |
| 최근 본 숙소 N+1 before/after | `nplus1-fixture-smoke.js`, `recently-viewed-nplus1-performance.js` | 이 문서의 N+1 절 |
| 쿠폰 Redisson(before)/Lua(after) 발급 | `coupon-issuance-comparison.js` | [상위 load-test README](../README.md) |
```

Keep the `lib/` and `test/` bullets, and remove only this batch-specific bullet:

```markdown
- `batch-fetch-size/core-get.js`, `analyze.py`: `evaluate.sh`가 내부에서 호출하므로 보통 직접 실행하지 않는다.
```

- [ ] **Step 3: Delete the six tracked harness files**

Apply one explicit deletion patch containing exactly these paths:

```text
load-test/k6/batch-fetch-size/.gitignore
load-test/k6/batch-fetch-size/README.md
load-test/k6/batch-fetch-size/analyze.py
load-test/k6/batch-fetch-size/core-get.js
load-test/k6/batch-fetch-size/evaluate.sh
load-test/k6/batch-fetch-size/test_analyze.py
```

If an ignored `__pycache__` directory remains locally, list its exact contents before removing that generated cache. Do not broaden deletion beyond `load-test/k6/batch-fetch-size`.

- [ ] **Step 4: Verify the cleanup and retained N+1 setting**

```bash
test ! -e load-test/k6/batch-fetch-size
! rg -n "batch-fetch-size|Hibernate batch fetch 크기" load-test/k6/README.md
rg -n "default_batch_fetch_size:[[:space:]]*0" \
  src/main/resources/application-nplus1-benchmark.yaml
for test_file in load-test/k6/test/*.js; do
  k6 run --quiet "$test_file" || exit 1
done
git diff --check
```

Expected: no tracked batch harness remains, the overview has no batch reference, the N+1 profile still reports batch fetch size `0`, every remaining k6 helper test passes, and the whitespace check exits `0`.

- [ ] **Step 5: Commit the isolated cleanup**

```bash
git add load-test/k6/README.md \
  load-test/k6/batch-fetch-size/.gitignore \
  load-test/k6/batch-fetch-size/README.md \
  load-test/k6/batch-fetch-size/analyze.py \
  load-test/k6/batch-fetch-size/core-get.js \
  load-test/k6/batch-fetch-size/evaluate.sh \
  load-test/k6/batch-fetch-size/test_analyze.py
git diff --cached --check
git diff --cached --stat
git commit -m "chore: batch fetch 크기 실험 제거"
```
