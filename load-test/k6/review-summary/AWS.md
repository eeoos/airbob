# 리뷰 요약 반정규화 AWS 측정

`prepare`는 **로컬 파일만 생성**한다. AWS·DNS·앱·DB에 접속하거나 자원을 만들지 않는다.
`run`은 나중에 준비된 AWS 부하 발생기에서 명시적으로 실행한다. 실제 AWS 검증은 아직 수행하지 않았다.
세 API의 경로·응답 검증·전후 비교는 [기존 k6 스크립트](README.md)를 그대로 사용한다.

## 지금: 오프라인 준비

저장소 루트에서 Node.js 20 이상으로 실행한다.

```bash
node load-test/k6/review-summary/aws.mjs prepare \
  --output build/k6/review-summary-aws/prepared
```

기본 입력은 `aws-experiment.example.json`이다. ID·리뷰 수는 설명용 예제이며 실재하는 데이터로 간주하지 않는다.
`example: true`인 준비물은 `run`을 거부한다. 같은 출력 경로를 덮어쓰지 않는다.

| 준비 파일 | 내용 |
| --- | --- |
| `config.json` | 대상 앱·ALB·데이터 식별자, 사례별 ID·리뷰 합계·부하 조건 |
| `plan.json` | 준비 상태, 스크립트 해시, 선택 가능한 사례·요청률, 아직 확인할 환경 조건 |
| `load-test/k6/review-summary/` | 실행기·공통 k6 코드·앱 환경 예제·이 문서 |
| `infra/aws/toolchain.env` | 저장소에 고정된 k6 버전과 Linux AMD64 배포물 체크섬 |
| `SHA256SUMS` | 준비 파일의 무결성 확인용 체크섬 |

`readyToExecute: false`는 오프라인 준비로는 원격 환경의 준비 여부를 확인할 수 없다는 뜻이다.
준비 후 설정·스크립트를 수정하면 실행을 거부한다. 원본 설정을 고치고 새 경로로 다시 준비한다.

## 비교할 사례

| 사례 ID | 반환 크기 | 목적 |
| --- | ---: | --- |
| `detail-light`, `detail-heavy` | 숙소 1개 | 숙소별 리뷰량 비교 |
| `wishlist-light`, `wishlist-heavy` | 각각 숙소 20개 | 같은 페이지 크기에서 리뷰량만 나누어 비교 |
| `recent-light`, `recent-heavy` | 각각 숙소 20개 | 같은 최근 본 목록 크기에서 리뷰량 비교 |
| `recent-max-heavy` | 숙소 100개 | 리뷰가 많은 최대 목록 크기 사례, 일반 조회와 구분 |

예제는 20·50·100 RPS를 허용한다. **한 번에 사례 하나와 요청률 하나**를 선택한다.
기본 조건은 워밍업 30초·측정 60초·3회차이며 AB/BA/AB 순서다. 한 조합에 약 10분과 검증 시간이 필요하다.
20 RPS부터 확인하고, 오류·요청 누락이 생기면 다음 요청률로 자동 진행하지 않는다.
긴 부하 실험은 `measureSeconds`를 180~300으로 늘려 별도로 준비할 수 있다.

## 나중에: AWS 환경 준비

[기존 Lab 운영 절차](../../../docs/performance/aws-lab-quickstart.md)와
[AWS 실험 경계](../../../docs/performance/aws-performance-lab.md)를 따른다.
이 도구는 자원 생성, 앱 환경 전환·복구, SSM 배포, IAM 변경, measurement lease 획득·갱신을 자동화하지 않는다.
운영자가 기존 운영 절차로 측정 lease를 유지하고, 다른 부하·배포·중지 작업과 겹치지 않게 실행한다.

1. 현재 세 API의 Before 경로와 런타임 검증 API가 포함된 앱 이미지를 기존 GitHub OIDC 경로로 발행한다.
   같은 digest 고정 이미지로 Before/After를 측정한다. 이전 단독 리뷰 요약 API용 AWS 실행기는 사용하지 않는다.
2. 현재 코드에 맞는 **V28 실험 DB**를 별도로 준비하고 `flyway_schema_history`와 데이터 manifest를 확인한다.
   기존 V27 실험 자료를 V28로 표시해 재사용하지 않는다. 실행기는 migration·데이터 복원·인덱스 변경을 하지 않는다.
3. 실험용 ALB의 정상 타깃을 **고정 앱 1대**로 맞추고, 같은 리전에 별도 Linux 부하 발생기를 준비한다.
   ALB 접근은 기존 Lab 규칙대로 허용한다. 앱/발생기 ID·태그·실행 수명·이미지 digest·DB 연결은 운영 절차에서 확인한다.
   설정에 쓴 ID만으로 이 AWS 자원 검증이 자동 완료되는 것은 아니다.
4. 기존 앱 환경의 사본에 `aws-application.env.example`의 측정 설정을 병합한다.
   DB·Redis·Elasticsearch·Kafka 연결 값은 기존 실험 환경을 유지하며 일반 Redis와 상세 캐시 Redis를 분리한다.
   원본 환경은 보존하고 측정 종료 후 복구한다. 이 파일은 B 서비스 자동 배포 옵션을 추가하는 것이 아니다.
5. 앱은 `aws,traffic-benchmark,read-model-benchmark` 프로필로 시작한다. 현재 코드에서는 `performance-lab`도 활성화된다.
   V28임을 먼저 확인하고 Flyway 자동 실행을 끈다. 상세 캐시·SQL 출력 로그·백그라운드 작업·외부 쓰기를 비활성화한다.
   로그인은 회원 행 잠금을 사용하므로 풀 전체를 강제로 read-only로 설정하지 않는다.
6. 앱의 `AIRBOB_RUN_ID`, 기존 자원 fence의 `AIRBOB_RESOURCE_FENCING_TOKEN_SHA256`, 실제 `AIRBOB_APP_INSTANCE_ID`를 설정한다.
   `AIRBOB_RUNTIME_REVISION`은 선택한 이미지 digest에서 `sha256:`를 뺀 값으로 맞춘다.
   벤치마크 토큰은 앱과 발생기 호스트에만 제공하고 설정 JSON·준비물·Git에 넣지 않는다.

`cache-benchmark` 프로필은 회원 API 비교에 사용하지 않는다. 최근 본 Before는
`/api/v2/members/recently-viewed/review-summary-before`이며 기존 주소 N+1 비교 경로와 다르다.

## 데이터 입력값 확정

AWS의 실제 데이터에서 입력을 선택한다. 로컬 숙소·위시리스트 ID를 그대로 복사하지 않는다.
각 사례의 `publishedReviewCount`에는 **응답에 포함되는 숙소들의 공개 리뷰 합계**를 적는다.
행 수가 맞아도 이 합계가 다르면 k6는 부하 전에 실패한다. 전후 응답 전체의 일치 여부도 별도로 검증한다.

- 상세: 공개 숙소 ID와 공개 리뷰 수를 확인한다.
- 위시리스트: 소유한 테스트 계정, 위시리스트 ID, 첫 페이지의 공개 숙소 수·리뷰 합계를 확인한다.
  light/heavy는 같은 `pageSize=20`, 같은 `expectedRows=20`으로 준비한다. 현재 페이지 밖의 리뷰는 합계에서 제외한다.
  다른 페이지를 쓰려면 `cursor`에 실제 API의 다음 커서 원문을 넣는다.
- 최근 본: 사례별 전용 테스트 계정에 같은 크기의 공개 숙소 기록을 미리 준비한다.
  준비 단계에서 기존 `POST /api/v1/members/recently-viewed/{id}`로 기록할 수 있다.
  이때는 `read-model-benchmark`를 켜기 전이며, `aws,traffic-benchmark`와 나머지 작업 격리 설정을 사용한다.
  측정 프로필은 기록 생성 API를 차단한다. 일반 Redis를 비우거나 다른 사용자의 기록을 변경하지 않는다.
- 데이터 준비는 측정 전에 끝내고 새 fixture를 만들었다면 실험 데이터 식별자와 manifest도 구분한다.
  요청률을 바꿔도 같은 사례의 숙소·회원·목록 순서와 리뷰 합계를 유지한다.

`authEnvPrefix`는 비밀번호가 아니라 발생기의 환경변수 이름 접두어다. 예를 들어 `RS_WISHLIST_LIGHT`라면
`RS_WISHLIST_LIGHT_EMAIL`과 `RS_WISHLIST_LIGHT_PASSWORD`, 또는 `RS_WISHLIST_LIGHT_SESSION_ID` 중 한 방식을 사용한다.
회원 사례마다 알맞은 소유자 계정을 지정한다. 여러 최근 본 사례의 기록이 다르면 계정도 분리한다.

## 실제 설정으로 다시 준비하고 전송

예제를 작업용 JSON으로 복사하여 실제 식별자·데이터·리뷰 합계를 채우고 마지막에 `example`을 `false`로 바꾼다.
계정 이메일·비밀번호·세션·토큰은 JSON에 넣지 않는다.

```bash
node load-test/k6/review-summary/aws.mjs prepare \
  --config /absolute/path/to/review-summary-aws.json \
  --output build/k6/review-summary-aws/actual-prepared
```

생성된 디렉터리 전체를 기존 승인된 전송 경로로 부하 발생기에 복사한다. 해당 디렉터리에서
`sha256sum -c SHA256SUMS`로 확인한다. 체크섬은 파일 무결성 확인이며 작성자 인증을 대신하지 않는다.
발생기에 Node.js 20 이상과 `infra/aws/toolchain.env`에 고정된 k6 1.5.0을 준비한다.
Linux AMD64 배포물을 설치할 때 같은 파일의 SHA-256도 확인한다. 실행기는 도구를 내려받거나 설치하지 않는다.

## 실제 측정 명령 — AWS를 준비한 다음에만

아래는 **AWS Linux 부하 발생기의 Bash**에서, 복사한 준비 디렉터리를 현재 디렉터리로 둔 예다.
인증 값은 호스트에서 입력하거나 기존 비밀 관리 경로로 주입한다. 명령 출력·로그에 비밀 값을 찍지 않는다.

```bash
read -rsp 'Benchmark token: ' BENCHMARK_READ_MODEL_TOKEN; echo
read -rp 'Wishlist-light account email: ' RS_WISHLIST_LIGHT_EMAIL
read -rsp 'Wishlist-light password: ' RS_WISHLIST_LIGHT_PASSWORD; echo
export BENCHMARK_READ_MODEL_TOKEN RS_WISHLIST_LIGHT_EMAIL RS_WISHLIST_LIGHT_PASSWORD

# 먼저 인증·데이터·경로를 짧게 검증한다. 이 결과는 성능 개선 근거로 사용하지 않는다.
node load-test/k6/review-summary/aws.mjs run \
  --prepared . --case wishlist-light --rate 20 --smoke \
  --output /var/lib/airbob/review-summary-results/wishlist-light-smoke-01

# 같은 사례의 본 측정. 다음 실행도 항상 새 출력 경로를 사용한다.
node load-test/k6/review-summary/aws.mjs run \
  --prepared . --case wishlist-light --rate 20 \
  --output /var/lib/airbob/review-summary-results/wishlist-light-r20-01
```

실행기는 지정한 **실험 ALB의 DNS에서 조회한 IPv4 하나**를 고정한다. HTTPS 호스트 이름과 인증서 검증은
`api.airbob.cloud`로 유지하고, Node와 k6 모두 그 IP로만 연결한다. 공개 서비스 DNS를 바꾸지 않는다.
각 버전의 부하 전후에 런타임 API로 앱의 Run ID·이미지 식별자·인스턴스·프로필·작업 격리를 확인한다.
앱 교체나 ALB 주소 변경이 생기면 실행을 중단하고 환경을 확인한 뒤 새 결과 경로에서 다시 시작한다.

## 결과와 DB 지표

`run.json`에는 사례·설정·실제 접속 IP·상태를, API별 디렉터리에는 개별 측정·런타임 검증·`comparison.json`을 저장한다.
성공은 모든 회차의 응답·리뷰 수·누락·런타임 검증이 통과했다는 의미다. 실패한 실행의 일부 숫자를 최종 개선율로 사용하지 않는다.
측정 중 중단되면 성공 비교 파일이 없을 수 있다. 실행기는 앱 설정이나 데이터를 변경하지 않으며 자원도 종료하지 않는다.
운영자가 측정 환경을 복구하고 lease를 정리한 뒤 기존 pause/destroy 절차를 사용한다.

개별 결과의 `measurement.startedAt`과 `finishedAt`은 **워밍업을 제외한 측정 구간의 UTC 시각**이다.
동일 구간의 RDS CPU·연결 수·읽기 I/O와 앱 CPU·GC·Hikari 대기를 기존 모니터링에서 함께 확인한다.
CloudWatch 집계 주기보다 짧은 실험은 겹친 버킷을 Before/After 전용 CPU 값으로 해석하지 않는다.
DB 비용 비교는 더 긴 구간이나 기존 Prometheus의 충분한 수집 주기를 사용한다.

SQL 실행 시간과 읽은 행 수는 기존 `load-test/mysql/capture-statement-digests.sql` 등의 읽기 전용 수집 절차로
구간 전후 차이를 별도 확보한다. 이 HTTP 실행기는 CloudWatch·MySQL 지표를 자동 수집하지 않는다.
고정 RPS 지연 비교만으로 최대 처리량, 쓰기 비용 개선, 운영 환경 전체의 개선율을 주장하지 않는다.

## AWS 없이 검증

```bash
node --test load-test/k6/test/review-summary-benchmark-test.mjs \
  load-test/k6/test/review-summary-comparison-test.mjs \
  load-test/k6/test/review-summary-aws-test.mjs
```

검증은 로컬 모의 서버·가짜 DNS/런타임 응답을 사용한다. 실제 AWS IAM·ALB·RDS·배포의 성공을 확인하는 테스트는 아니다.
