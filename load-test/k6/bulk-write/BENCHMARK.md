# 벌크 삭제 로컬·AWS 비교

위시리스트 삭제와 편의시설 삭제를 같은 앱에서 Before/After로 비교한다.
로컬은 `dev,bulk-delete-benchmark`, AWS는 `aws,bulk-delete-benchmark`를 사용한다.
프로필 그룹이 기존 `bulk-write-benchmark` API·토큰·DB 검증을 함께 활성화한다.
AWS 준비 명령은 파일만 생성한다. AWS 배포·인프라 생성·실측을 수행하지 않는다.

## 측정 범위

| 대상 | 비교 |
| --- | --- |
| 위시리스트 | 연결 행 개별 삭제 / 벌크 삭제 |
| 편의시설 DELETE_ONLY | 기존 편의시설 삭제 트랜잭션 |
| 편의시설 FULL_REPLACEMENT | 같은 숙소 수정 흐름에서 삭제 방식만 교체 |

주 지표는 서버의 연산 시작부터 트랜잭션 커밋까지의 시간이다. fixture 생성·검증·정리는 제외한다.
전체 교체 양쪽에 잠금·검증·재등록·이력·outbox 기록이 동일하게 포함된다.
실제 Redis 캐시 I/O는 둘 다 끈다. 로그인은 세션 Redis를 사용한다.
HTTP 시간은 원본 child JSON에 별도로 남으며, 주 지표와 섞지 않는다.
이는 순차 실행 1 VU에서의 연산 지연 비교다. 최대 처리량이나 동시 요청 성능을 나타내지 않는다.

같은 이미지·커밋·앱 1대·DB·풀 크기를 유지한다. 짝수 라운드 수로 AB/BA 순서를 교차하며
각 block은 별도 워밍업 후 표본마다 k6를 새로 실행한다. 기존 SQL/결과 검증과 정리를 그대로 사용한다.
편의시설 수가 활성 코드 수를 넘으면 결과의 `STRESS` 표시를 그대로 보존한다.
과거 outbox가 빠진 측정이나 서로 다른 환경의 표본을 하나의 개선율로 합치지 않는다.

## 공통 준비

1. **별도 disposable MySQL 스키마**를 빈 상태에서 이 앱의 V1–V28로 미리 마이그레이션한다.
   이름은 `*_bulk_write_benchmark`여야 한다. 앱은 시작 시 스키마명·필수 테이블·최종 V28·실패한 migration
   유무를 검사하며, 자동 migration/DDL은 거부한다. 운영 DB 또는 검증된 B 데이터셋을 변경해 준비하지 않는다.
2. 해당 스키마에 실제 로그인 가능한 **ACTIVE ADMIN** 한 명과 활성 `AMENITY_TYPE` 공통 코드를 준비한다.
   삭제 대상과 비교용 숙소·위시리스트는 요청마다 생성·검증·정리된다. 큰 공용 데이터 manifest는 필요 없다.
3. 앱 DB 계정은 이 스키마에만 권한을 부여하고 Debezium의 캡처 대상에 넣지 않는다.
   앱은 로그아웃까지 세션 Redis에 접근할 수 있어야 한다.
4. 현재 시간대를 지원하는 JDK 21(검증에 사용한 버전 21.0.12.1), Python 3.10+, Node.js 18+,
   `infra/aws/toolchain.env`에 고정된 k6를 준비한다.
5. 이메일·비밀번호·벤치마크 토큰을 각각 소유자만 읽는 0600 파일로 준비한다. 토큰은 32자 이상이며
   앱의 `BENCHMARK_BULK_WRITE_TOKEN`과 같아야 한다. 파일은 저장소·결과 묶음 밖에 둔다.
   설정 JSON에는 **파일의 절대 경로만** 쓴다.

## 로컬 실행

새 작업트리에서는 기존 작업트리의 `.env`가 자동 복사되지 않는다.
전용 DB의 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`와 Redis 연결 설정을 준비한다.
애플리케이션 실행 셸에는 다음 값을 전달한다. DB 자격 증명과 토큰은 파일/숨김 입력으로 주입한다.

```bash
export BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA=airbob_bulk_write_benchmark
export JDBC_REWRITE_BATCHED_STATEMENTS=true
# BENCHMARK_BULK_WRITE_TOKEN은 측정용 token 파일과 같은 값으로 준비
bash load-test/k6/bulk-write/run-bulk-delete-benchmark-server.sh
```

launcher는 커밋된 현재 코드를 사용한다. 추적 중인 수정 사항이 남으면 시작을 거부하고
현재 full commit을 런타임에 기록한다. 기존 예약 이력 INSERT launcher는 변경하지 않았다.

`local-experiment.example.json`을 복사해 실제 커밋, 계정 파일 경로, 앱 주소, DB명, 풀 크기를 맞춘다.
`runId`는 매 실행마다 새 이름을 사용하고 `example=false`로 바꾼다.
첫 확인은 크기 각 1개·2라운드·표본 1개·워밍업 1개로 줄일 수 있다.

```bash
python3 load-test/k6/bulk-write/run-experiments.py plan --config /absolute/local-bulk-delete.json
python3 load-test/k6/bulk-write/run-experiments.py run --config /absolute/local-bulk-delete.json --check-inputs
python3 load-test/k6/bulk-write/run-experiments.py run --config /absolute/local-bulk-delete.json
```

`plan`과 `--check-inputs`는 네트워크에 연결하지 않는다.
실행은 관리자 로그인 후 runtime API로 프로필, 앱 커밋/이미지 선언, 실제 DB명·MySQL 서버 UUID,
V28, JVM/MySQL 버전, 실제 JDBC rewrite 옵션·풀 크기를 확인한다.
각 k6 child도 쓰기 전에 같은 runtime을 확인하므로 앱 재시작이나 여러 앱으로의 분산이 감지되면 중단한다.
쓰기 요청에도 확인한 runtime ID를 보내며 다른 인스턴스는 fixture 생성 전에 409로 거부한다.
앱의 커밋·이미지 값은 배포 시 공급하는 선언이므로 실제 빌드/배포 기록과도 일치시켜야 한다.
설정 조회만으로 이미지 공급망을 증명하는 것은 아니다.

## AWS 준비와 실행

```bash
make aws-bulk-delete-prepare
# 또는 실제 설정을 전달해 새 출력 경로에 준비
make aws-bulk-delete-prepare \
  BULK_DELETE_AWS_CONFIG=/absolute/aws-bulk-delete.json \
  BULK_DELETE_PREPARED=build/k6/bulk-delete-aws/prepared-2
```

출력에는 도구 tar.gz, 설정 JSON, 실행 계획, 공개 소스 해시, 앱 환경 예시, 안내와 SHA256SUMS가 들어간다.
개인 파일이나 앱 이미지·DB 데이터·토큰은 포함하지 않는다. `readyToExecute=false`는 실제 서버를
확인하지 않은 오프라인 준비라는 뜻이다. 준비된 설정의 Linux 호스트 경로를 확인한다.

기존 AWS 환경에 다음 실행 조건을 별도로 적용한다.

- 이번 변경을 포함한 앱 이미지를 기존 GitHub OIDC 발행 경로로 게시하고 digest로 고정한다.
- 전용 V28 스키마와 **일반 트래픽을 받지 않는 앱 1대**를 사용한다. AWS 앱 환경은
  `aws-application.env.example`을 기준으로 원래 런타임 환경을 보존한 뒤 별도로 만든다.
  DB 접속 정보는 전용 스키마로 바꾸고, 해당 lab의 Redis·Kafka·Elasticsearch 연결 설정은 유지한다.
  다른 실험 프로필이나 `SPRING_PROFILES_INCLUDE`를 남기지 않는다.
- 전용 프로필은 로그인·로그아웃, runtime 조회, 두 삭제 API, health/prometheus만 허용한다.
  예약 이력 INSERT·일반 예약·결제·회원 쓰기 API는 제공하지 않는다.
  Kafka 소비자·스케줄러·검색 bootstrap·재고 시딩·상세 캐시·외부 쓰기는 비활성화한다.
- 앱 수와 Hikari 크기를 고정한다. Linux 부하 발생기는 앱과 분리한다.
  AWS 예시의 주소를 해당 실험에만 연결되는 **HTTPS origin**으로 바꾼다.
  공개 OCI 주소 `api.airbob.cloud`는 이 실행기에서 허용하지 않는다. 인증서 검증과 DNS 정책은 유지한다.
- 기존 lab 점유·만료 시간을 확보하고 다른 부하를 멈춘다. 실행기는 lab 수명을 연장하거나 ASG/RDS를 조작하지 않는다.
  `make lab-start`만으로 벌크 삭제 프로필이 선택되지는 않는다.

준비된 묶음을 기존 부하 발생기에 전달하고 확인한다.

```bash
sha256sum --check SHA256SUMS
tar -xzf bulk-delete-tools.tar.gz
python3 load-test/k6/bulk-write/run-experiments.py run --config /absolute/aws-bulk-delete.json --check-inputs
python3 load-test/k6/bulk-write/run-experiments.py run --config /absolute/aws-bulk-delete.json
```

로컬과 AWS는 같은 조건 JSON 구조와 실행기를 사용한다. 예시를 그대로 실행하는 것은 거부한다.
서버 설정이 입력과 다르거나 검증/child 실행이 실패하면 성공 보고서를 만들지 않는다.

## 결과와 정리

- `build/k6/bulk-write/<runId>-*.json`: 워밍업, 표본별 원본, block별 검증된 관측값.
- `build/k6/bulk-delete/<runId>/plan.json`, `runtime.json`: 실행 순서와 확인한 환경.
- `comparison.json`: 대상·크기·범위별 Before/After p50/p95, SQL 횟수, 라운드별 p50, p50 감소율.
  p50/p95는 원본 표본의 nearest-rank다. 음수 감소율은 After가 느린 결과이며 그대로 남긴다.
  표본 수가 적을 때 p95를 안정적인 꼬리 지연으로 해석하지 않는다.
- `failure.json`: 미완료 block 표시. 부분 결과를 성공 비교로 합치지 않는다.

HTTP 요청이 정상 반환되면 해당 fixture와 outbox는 정리된다. 앱 종료/네트워크 timeout은 자동 정리를
보장하지 않으므로 disposable 스키마의 잔여물을 확인하고 새 runId로 다시 실행한다.
전체 Redis 초기화나 공유 데이터 삭제는 하지 않는다. 결과를 보관한 뒤 실험 앱을 종료하거나
보존한 원래 런타임 설정으로 복구하고 기존 AWS lab 정리 절차를 따른다.

## 구현 검증

실제 MySQL/Redis, HTTP 로그인, Python/k6 실행기를 함께 확인하려면 Docker와 k6를 준비한 뒤 실행한다.
기본 Java 테스트에서는 외부 실행기를 사용하는 마지막 검증만 제외하며 CI는 함께 실행한다.

```bash
BULK_DELETE_VERIFY_RUNNER=true ./gradlew test --tests '*BulkDeleteBenchmarkHttpIntegrationTest'
python3 -m unittest discover -s load-test/k6/test -p 'test_bulk_delete_*.py' -v
```

이 검증은 disposable Testcontainers DB와 작은 데이터로 수행하는 연결 확인이다.
출력된 smoke 지연 수치를 정식 로컬/AWS 성능 결과로 사용하지 않는다.
