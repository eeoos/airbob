# 숙소 상세 캐시 AWS 측정 실행 가이드

현재 제공하는 명령은 **오프라인 준비(`prepare`)**와 **이미 켜진 AWS 실험 환경의 측정(`run`)**으로 나뉜다.
`prepare`는 AWS·Terraform·Docker·k6를 실행하지 않는다. `run`도 EC2/RDS 생성·기동이나 공개 DNS 변경을 하지 않는다.
실제 AWS 검증은 아직 수행하지 않았으며 아래 실행기는 로컬의 가짜 AWS/SSM 응답과 단위 테스트로 검증한다.

## 지금 할 수 있는 오프라인 준비

저장소 루트에서 실행한다.

```bash
make aws-cache-prepare
```

기본 출력은 `build/k6/cache-aws/prepared/`이다. 같은 경로를 덮어쓰지 않으므로 재생성할 때는
`CACHE_PREPARED=build/k6/cache-aws/prepared-2`처럼 새 경로를 지정한다.

| 생성 파일 | 용도 |
|---|---|
| `plan.json` | 실험 조건, 필요한 기존 자원, 소스·번들 해시, AWS 호출 0회 표시 |
| `config.json` | 공개된 리소스 식별자와 측정 조건. 토큰·DB 비밀번호 없음 |
| `host-bundle.zlib` | SSM으로 전달할 앱/Redis/부하 발생기 제어 코드와 공통 k6 코드 |
| `token-read-policy.json` | 앱·부하 발생기에 필요한 정확한 SecureString 경로의 조회 권한 |
| `token-admin-policy.json` | 해당 토큰을 준비·삭제하는 운영자에게 필요한 권한 |

예제의 `example: true`는 실제 실행을 차단한다. 예제 리소스 ID와 숙소 ID는 실재하는 대상으로 간주하지 않는다.
준비된 설정이나 소스가 변경되면 새 경로로 다시 `prepare`한다.

## 나중에 AWS를 켤 때 준비할 환경

1. 현재 변경을 포함한 앱 이미지를 기존 **GitHub OIDC 발행 경로**로 게시하고 정확한 digest와 커밋을 확정한다.
   `cache-benchmark` 프로필, 로컬 병합 ON/OFF 설정과 `/api/v2/benchmark/cache-runtime`이 포함되어야 한다.
   이미 발행된 B 데이터/앱 런타임 조합의 승인 규칙을 유지하며 새 이미지에 맞는 조합을 준비한다.
   측정 실행기는 이미지 발행, 데이터 이관, Flyway migration을 수행하지 않는다.
2. V28 데이터와 전용 캐시 Redis가 준비된 **B application 단계**에서 다음 선택값을 추가한다.
   기존 `aws-lab.sh services`의 RUN_ID·이미지·데이터·readiness 인자는 그대로 필요하다.

   ```bash
   B_SERVICE_STAGE=application
   B_CACHE_BENCHMARK_ENABLED=true
   B_CACHE_BENCHMARK_APPS=2
   ```

   이 값은 나중에 기존 services 명령의 환경변수로 전달한다. 예를 들어 이미 준비한 동일한
   services 실행 환경에서 `B_SERVICE_STAGE=application B_CACHE_BENCHMARK_ENABLED=true
   B_CACHE_BENCHMARK_APPS=2 infra/aws/scripts/aws-lab.sh services`를 사용한다.
   **이는 실제 AWS 자원을 변경하는 단계이며 오프라인 준비 명령에 포함되지 않는다.**

   기본값은 비활성이다. 명시적으로 선택한 경우에만 고정 앱 1~4대와 별도 `c6i.xlarge` 부하 발생기를 사용한다.
   앱 수는 min=desired=max로 고정하고 자동 확장 정책을 사용하지 않는다. 실험용 ALB에는 현재 부하 발생기의
   공인 IPv4 `/32`로부터 오는 HTTPS만 추가로 허용한다. 세션 Redis와 캐시 Redis는 계속 분리한다.
   모든 자원은 기존 lab Terraform 상태·RunId·FencingToken·만료/정리 체계에 남는다.
3. `/airbob/performance-lab/cache-benchmark/<RUN_ID>/token`에 **64자리 16진수 SecureString**을 외부에서 준비한다.
   토큰 값은 설정 파일·Git·번들에 넣지 않는다. 앱과 부하 발생기는 해당 값을 자기 호스트에서 직접 조회한다.
   생성된 IAM 문서는 필요한 범위를 설명하며 자동 적용하지 않는다. 기본 `aws/ssm` 키를 사용하고,
   별도 KMS 키를 택하면 두 역할에 해당 키의 복호화 권한도 필요하다.
4. `aws-experiment.example.json`을 복사해 실제 앱/Redis/부하 발생기 ID, ASG·ALB,
   이미지 digest·커밋, 데이터 manifest 해시와 존재하는 공개 숙소 ID를 채운다. 마지막으로 `example`을 `false`로 바꾼다.
   기존 `make lab-start`만으로 위 측정 환경이 자동 선택되는 것은 아니다.

자격증명은 기존 `airbob-lab-operator` 역할을 사용한다. 원격 호스트에는 Python 3·systemd·Docker/Compose·AWS CLI가 필요하다.
부하 발생기에는 실행 시 `infra/aws/toolchain.env`에 고정된 k6 1.5.0을 내려받아 SHA-256을 검증하고,
실험 전용 디렉터리에 설치한다. 로컬 Mac의 k6로 AWS에 부하를 보내지 않는다.

## 실제 측정 명령 — AWS 환경이 준비된 다음에만

```bash
python3 load-test/k6/cache/run-aws-experiments.py prepare \
  --config /absolute/path/to/aws-cache-config.json \
  --output build/k6/cache-aws/aws-prepared

python3 load-test/k6/cache/run-aws-experiments.py run \
  --prepared build/k6/cache-aws/aws-prepared \
  --output build/k6/cache-aws/aws-results
```

실행 전 계정·역할·RunId·호스트 역할·VPC·자원 만료·고정 ASG·ALB 연결을 확인한다.
이미 종료되거나 다른 run에 속한 인스턴스는 시작하지 않고 거부한다. 측정 중 변경은 기존 orchestration lease로 직렬화한다.

앱의 원본 환경 파일은 보존하고, 같은 Compose·이미지·CPU/메모리 설정에 임시 `aws,cache-benchmark` 환경 파일을 사용한다.
각 앱에서 실제 Flyway 버전, 캐시 ON/OFF, 병합 ON/OFF와 TTL 설정을 확인하고
무토큰 요청 차단 및 V2/V1 전체 응답 동일성을 검증한다. Kafka 소비·스케줄링·외부 쓰기는 측정 프로필로 격리한다.

HTTPS 요청의 이름은 인증서와 같은 `api.airbob.cloud`를 유지하되, **실험 ALB DNS에서 조회한 IPv4로만 연결**한다.
TLS 검증을 유지하며 공개 DNS와 OCI 트래픽은 전환하지 않는다. 단계마다 선택한 ALB 주소를 결과에 기록한다.
ALB가 요청을 분배하므로 앱별 요청 수가 정확히 같다고 가정하지 않고 실제 카운터를 합산한다.

| scenarios 값 | 방법 | 결과 |
|---|---|---|
| `latency` | 같은 RPS에서 캐시 OFF/ON. 두 회차의 순서 교대 | p95·오류율·DB 로딩 |
| `miss` | JVM·DB 예열 후 전용 캐시만 비우고 같은 숙소 버스트 | 앱별/합산 실제 DB 로딩·SELECT·p95 |
| `outage` | 전용 캐시 Redis pause, 병합 OFF/ON에서 지속 부하와 버스트 | DB 로딩·병합률·p95·오류율·복구 검증 |
| `capacity` | 지수 증가 탐색 → 통과/실패 사이 세분화 → 양쪽 경계 반복 측정 | 반복 검증한 지속 RPS 범위 또는 미확정 사유 |

Lua permit은 성능 측정 항목에 포함하지 않는다. 기본 예제는 `latency`, `miss`, `outage`만 선택한다.
실험마다 새 `experimentId`와 새 출력 경로를 사용한다. 서버 응답 전체를 fixture와 비교하므로 데이터가 바뀌면 유효한 비교로 인정하지 않는다.

## 최대 지속 처리량

별도 설정에서 `scenarios`를 `["capacity"]`로 선택한다. 기본 탐색은 100 RPS에서 시작해 배로 올리고,
통과/실패 범위를 50 RPS 이내로 좁힌다. 이후 낮은 경계와 높은 경계를 각각 **5분씩 3회** 확인한다.
서버를 계속 과부하시키지 않도록 명시한 `ceilingRps`와 전체 명령 기한을 지킨다.

- 기준: p95 ≤ `p95LimitMs`, 응답 오류 0, 요청 누락 0, 실제 성공 RPS ≥ 설정값의 99%.
- `confirmed-boundary`: 낮은 경계는 반복 통과하고 높은 경계는 반복 실패했다. 측정 해상도와 SLO를 함께 기록한다.
- `lower-bound-only`: 상한까지 통과했다. 최대값이나 증가율로 바꾸어 적지 않는다.
- `unstable-or-inconclusive`: 반복 결과가 달라 경계 확정 불가.
- `inconclusive-generator-or-evidence`: 요청 누락·지표 불일치·부하 발생기 CPU/메모리 여유 부족 등으로 서버 상한 판정 불가.

부하 발생기는 고정 `clientVUs`를 쓰고 요청 구간의 CPU·가용 메모리를 기록한다. CPU 90% 이상 또는 가용 메모리 15% 미만이면
처리량 근거를 불충분으로 분류한다. VU 부족으로 누락되면 같은 서버 조건을 유지한 채 발생기 설정을 조정해 새 실험으로 재측정한다.
예제 VU 2,000개가 모든 RPS에 충분하다는 보장은 없다. 상한은 AWS 모드에서 최대 20,000 RPS이며 이는 서비스 목표나 예상 성능이 아니다.

각 단계 전에 전용 캐시를 비우고 예열해 긴 탐색 중 이전 단계의 TTL이 만료되는 것을 피한다.
기본 TTL 10분·Jitter 2분을 유지하며 한 측정 구간은 5분까지다. 캐시 적중 실험에서 DB 로딩이 발생하면
캐시가 예열된 상태라는 가정을 검증하지 못했으므로 해당 근거를 거부한다.

`deadlineSeconds`는 기존 measurement lease 한도인 90분 이하이며 마지막 10분은 복구를 위해 남긴다. 설정한 탐색이 기한에 맞지 않으면
결과를 완료로 표시하지 않고 복구한다. 처리량 실험은 다른 시나리오와 분리하고, 필요하면 회차/범위를 나눈다.

## 결과와 복구

로컬 출력 디렉터리에 `report.md`, `comparison.json`, 단계별 결과, 앱별 필요한 Prometheus 카운터 snapshot을 저장한다.
정상 종료 시 기존 evidence 버킷의 `measurements/<RUN_ID>/<experimentId>/`에도 변경 불가능한 객체로 게시한다.
부하 발생기에는 전체 k6 로그·fixture·자원 샘플이 실험 전용 디렉터리에 남는다. 앱 환경 파일과 토큰은 게시하지 않는다.

DB 로딩은 loader 실행 횟수이며 SQL 실행 횟수와 다르다. 이 비로그인 경로에서는 성공 loader 1회=SELECT 3회를 검증한다.
병합률은 병합된 요청 수/전체 완료 요청 수이며, 지속 부하와 버스트를 구분해 해석한다. 단일 로딩을 보장하는 single-flight로 표현하지 않는다.

정상 종료와 예외 종료에서 Redis를 복구하고 모든 앱을 원래 환경으로 되돌린다. Redis pause와 앱 전환에는 호스트 측
systemd 복구 타이머도 둔다. 앱 교체를 막기 위해 일시 정지한 ASG `ReplaceUnhealthy`는 앱 복구 후 원래 상태로 되돌린다.
제어 프로세스가 강제 종료되면 타이머의 앱/Redis 복구 외에 아래 명령으로 ASG 복구와 결과를 확인한다.

```bash
python3 load-test/k6/cache/run-aws-experiments.py recover \
  --output build/k6/cache-aws/aws-results
```

복구도 새 measurement lease를 요구하므로 다른 실험이나 아직 유효한 이전 제어 작업과 겹쳐 실행되지 않는다.
`recovery-required`이면 복구되지 않은 호스트가 기록된다. 원래 환경 파일이 외부에서 바뀌면 덮어쓰지 않는다.
측정용 앱 수와 부하 발생기 자체는 유지된다. 측정을 마친 뒤 동일 B application 단계에서
`B_CACHE_BENCHMARK_ENABLED=false`로 원래 고정 1대 구성으로 되돌린 다음 일상적인 pause를 사용하거나,
기존 lab destroy 절차로 전체 실험 자원을 정리한다.

## AWS를 호출하지 않는 검증

```bash
python3 -m unittest discover -s load-test/k6/test -p 'test_cache_*.py'
K6_NO_USAGE_REPORT=true k6 run --quiet load-test/k6/test/cache-experiment-config-test.js
python3 -m unittest discover -s infra/aws/tests -p 'test_cache_benchmark_infrastructure.py'
python3 -m unittest discover -s infra/aws/tests -p 'test_growth_b_service_infrastructure.py'
./gradlew test --tests 'kr.kro.airbob.common.benchmark.CacheBenchmark*'
```

Terraform 정책 테스트는 provider·backend 없는 임시 디렉터리의 `terraform console`만 사용한다.
SSM 장애·복구와 AWS 대상 식별은 가짜 클라이언트로 검증하며 실제 IAM 권한이나 배포 성공을 보장하는 결과로 해석하지 않는다.
