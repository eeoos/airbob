# 리뷰 요약 반정규화 전후 k6 측정

숙소 상세·위시리스트 안의 숙소 목록·최근 본 숙소의 **전체 API 응답**을 비교한다.
`comparison.js`가 한 버전을 측정하고, `run-comparison.mjs`가 두 버전을 순서대로 반복한다.
기존 단독 리뷰 요약 API 및 주소 N+1 비교 스크립트와는 별개다.

## 실행 전 준비

- Node.js 20 이상과 k6가 필요하다. 로컬 검증에는 Node.js 20.14.0, k6 1.5.0을 사용했다.
- 앱은 `dev,read-model-benchmark` 프로필로 실행한다. 앱과 k6의 `BENCHMARK_READ_MODEL_TOKEN`이 같아야 한다.
- 데이터와 요약 테이블을 준비한다. 측정 중 리뷰·숙소·위시리스트 내용을 변경하지 않는다.
- 최근 본 숙소 기록은 일반 개발 프로필에서 해당 회원으로 미리 준비한다. 비교 프로필은 기록 생성 API를 차단한다.
- 아래 명령은 저장소 루트의 **zsh** 기준이다. 예시 ID와 이메일을 실제 데이터로 바꾼다.

앱 실행 터미널:

```zsh
read -rs 'BENCHMARK_READ_MODEL_TOKEN?벤치마크 토큰: '
echo
export BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev,read-model-benchmark ./gradlew bootRun
```

k6 실행 터미널:

```zsh
export BASE_URL=http://localhost:8080
read -rs 'BENCHMARK_READ_MODEL_TOKEN?앱에 설정한 벤치마크 토큰: '
echo
export BENCHMARK_READ_MODEL_TOKEN
```

## 1. 숙소 상세

익명 조회를 비교하려면 로그인 환경 변수를 비워 둔다.

```zsh
unset BENCHMARK_SESSION_ID BENCHMARK_EMAIL BENCHMARK_PASSWORD
TARGET=accommodation-detail ACCOMMODATION_ID=30 DATASET_LABEL=review-heavy \
  node load-test/k6/review-summary/run-comparison.mjs
```

- Before: `/api/v2/accommodations/30/review-summary-before`
- After: `/api/v2/accommodations/30`

**양쪽 모두 캐시를 사용하지 않는다.** V1 상세를 After로 바꾸면 상세 캐시 효과가 섞인다.
로그인 정보를 지정하면 양쪽 모두 같은 회원의 찜 여부를 포함해 비교한다.
리뷰가 적은 숙소와 많은 숙소는 ID와 `DATASET_LABEL`을 바꿔 각각 측정한다.

## 2. 위시리스트 안의 숙소 목록

해당 위시리스트를 소유한 계정으로 로그인한다.

```zsh
export BENCHMARK_EMAIL='benchmark@example.com'
read -rs 'BENCHMARK_PASSWORD?계정 비밀번호: '
echo
export BENCHMARK_PASSWORD

TARGET=wishlist-accommodations WISHLIST_ID=42 PAGE_SIZE=20 EXPECTED_ROWS=20 \
  DATASET_LABEL=wishlist-20 \
  node load-test/k6/review-summary/run-comparison.mjs
```

- Before: `/api/v2/members/wishlists/accommodations/42/review-summary-before?size=20`
- After: `/api/v1/members/wishlists/accommodations/42?size=20`

`EXPECTED_ROWS`는 그 페이지에서 실제로 반환할 공개 숙소 개수다. 페이지 크기와 반드시 같지는 않다.
`PAGE_SIZE`는 1~50이다. 다음 페이지는 `CURSOR`에 API 응답의 `page_info.next_cursor` 원문을 넣는다.
스크립트가 URL 인코딩하고 양쪽에 동일한 커서를 전달한다. 측정 중 다음 페이지로 자동 이동하지 않는다.

## 3. 최근 본 숙소

위와 같은 로그인 환경 변수를 사용한다. 그 계정에 준비된 **유효한 공개 숙소 기록 수**를 지정한다.

```zsh
TARGET=recently-viewed EXPECTED_ROWS=100 DATASET_LABEL=recent-100 \
  node load-test/k6/review-summary/run-comparison.mjs
```

- Before: `/api/v2/members/recently-viewed/review-summary-before`
- After: `/api/v1/members/recently-viewed`

최근 본 기록을 초기화하거나 추가하지 않는다. GET의 기존 동작에 따라 비공개·삭제된 숙소 기록은 정리된다.
`EXPECTED_ROWS`는 0~100이다. 위시리스트도 빈 페이지 실험이라면 명시적으로 `EXPECTED_ROWS=0`을 사용한다.
행 수가 예상과 다르면 부하를 시작하지 않으므로, 실수로 빈 목록을 측정하는 일을 방지한다.

이메일·비밀번호 대신 기존 세션을 사용할 수도 있다.

```zsh
unset BENCHMARK_EMAIL BENCHMARK_PASSWORD
read -rs 'BENCHMARK_SESSION_ID?SESSION_ID 쿠키 값: '
echo
export BENCHMARK_SESSION_ID
```

같은 계정으로 다른 곳에서 로그인하면 활성 세션이 바뀌어 측정이 실패할 수 있다.
회원 목록에는 벤치마크 토큰과 로그인 세션이 모두 필요하다.

## 측정 시간과 요청률

기본값은 10 RPS, 워밍업 30초, 측정 1분, 3회차다. 각 회차는 두 버전을 실행한다.
순서는 `before → after`, `after → before`, `before → after`로 번갈아 적용한다.
한 번에 한 API만 측정하며, 두 버전을 동시에 실행하지 않는다.

처음에는 짧게 실행해 설정과 데이터만 확인한다. 이 결과의 p99는 성능 개선 근거로 쓰지 않는다.

```zsh
TARGET=accommodation-detail ACCOMMODATION_ID=30 \
  ROUNDS=1 RATE=2 WARMUP_DURATION=3s MEASURE_DURATION=5s \
  node load-test/k6/review-summary/run-comparison.mjs
```

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `RATE` | `10` | 초당 요청 수. 반복 하나에 GET 하나를 실행 |
| `WARMUP_DURATION` | `30s` | 선택한 버전의 예열 시간 |
| `MEASURE_DURATION` | `1m` | 실제 측정 시간 |
| `ROUNDS` | `3` | 전후 비교 쌍의 반복 횟수 |
| `REQUEST_TIMEOUT` | `5s` | 요청 제한 시간 |
| `SETTLE_SECONDS` | `2` | 워밍업 종료 유예 이후 추가 대기 |
| `PRE_ALLOCATED_VUS` | `max(20, RATE × 2)` | 미리 준비할 가상 사용자 수 |
| `MAX_VUS` | `PRE_ALLOCATED_VUS` | 가상 사용자 상한 |
| `RESULT_DIR` | `build/k6/review-summary` | 실행기가 만드는 결과 디렉터리의 부모 경로 |
| `DATASET_LABEL` | `unspecified` | 직접 정한 데이터 구간 이름 |
| `APP_REVISION` | `unspecified` | 실제 실행 중인 앱의 커밋 또는 이미지 식별자 |

고정 요청률은 [k6 constant-arrival-rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)로 유지한다.
워밍업 요청이 측정에 겹치지 않도록 요청 제한 시간보다 긴 [종료 유예 시간](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/graceful-stop/)을 둔다.
VUs가 모자라 요청을 보내지 못하면 `dropped_iterations`로 드러난다. 서버 포화와 부하 발생기 부족을 구분한 뒤,
필요한 경우 양쪽에 동일하게 VUs를 늘려 다시 실행한다.

한 버전만 실행할 수도 있다. 결과 파일의 부모 디렉터리를 먼저 만든다.

```zsh
mkdir -p build/k6/review-summary
TARGET=accommodation-detail ACCOMMODATION_ID=30 VARIANT=before \
  RESULT_PATH=build/k6/review-summary/detail-before.json \
  k6 run --address '' load-test/k6/review-summary/comparison.js
```

`VARIANT=after`로 바꾸면 After를 측정한다. 직접 실행할 때 같은 `RESULT_PATH`를 쓰면 파일을 덮어쓴다.
설정만 검사하려면 `k6 run` 대신 `k6 inspect --include-system-env-vars`를 사용한다.

## 결과 읽기

실행기는 매번 새로운 디렉터리를 만들고 다음 파일을 저장한다.

```text
build/k6/review-summary/<target>-<실행별 식별자>/
  1-before.json
  1-after.json
  2-after.json
  2-before.json
  ...
  comparison.json
```

개별 결과의 `measurement`에는 완료·성공·실패·누락 건수, 실제 완료 구간의 RPS, p50/p95/p99가 들어간다.
로그인·전후 응답 검증·워밍업은 이 지표에 포함되지 않는다. `responseHash`는 전체 응답 데이터의 비교용 해시이며,
응답 원문·세션·비밀번호·토큰은 결과 파일에 기록하지 않는다. 편의시설 순서만 정규화하고 이미지와 목록 순서는 유지한다.

`comparison.json`은 회차별 전후 수치와 `(Before - After) / Before × 100` 지연 감소율을 담는다.
양수면 빨라졌고 음수면 느려졌다. 여러 회차의 p95를 평균내어 전체 p95라고 표시하지 않는다.
응답 불일치, 데이터 변경, 오류, 요청 누락, 표본 부족이 있으면 성공 비교 파일을 만들지 않고 종료한다.

이 실험은 **같은 요청률에서의 API 지연 비교**다. 최대 처리량이나 DB CPU·SQL 실행 시간을 직접 측정하지 않는다.
DB 지표는 기존 모니터링과 함께 확인한다. 단일 숙소·한 페이지·한 회원을 반복하므로 DB 버퍼가 예열된 조회 결과다.
리뷰 수와 목록 크기를 바꿔 별도 실행하고, 이력서에는 데이터 규모·요청률·측정 시간을 개선율과 함께 적는다.

## 스크립트 검증

```zsh
node --test load-test/k6/test/review-summary-benchmark-test.mjs
node --test load-test/k6/test/review-summary-comparison-test.mjs
```

첫 번째는 설정·응답·결과 계산을 검증한다. 두 번째는 실제 k6를 로컬 모의 서버에 연결해 인증,
전후 불일치, 워밍업 중 변경, 측정 오류, 요청 누락과 반복 실행을 검증한다. 실제 앱 성능 수치는 아니다.
