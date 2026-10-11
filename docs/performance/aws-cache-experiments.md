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
2. 검증한 데이터와 전용 캐시 Redis가 준비된 **B application 단계**에서 다음 선택값을 추가한다.
   기존 `aws-lab.sh services`의 RUN_ID·이미지·데이터·readiness 인자는 그대로 필요하다.

   ```bash
   B_SERVICE_STAGE=application
   B_CACHE_BENCHMARK_ENABLED=true
   B_CACHE_BENCHMARK_APPS=2
   KEEP_ON_FAILURE=true
   ```

   이 값은 나중에 기존 services 명령의 환경변수로 전달한다. 예를 들어 이미 준비한 동일한
   services 실행 환경에서 `B_SERVICE_STAGE=application B_CACHE_BENCHMARK_ENABLED=true
   B_CACHE_BENCHMARK_APPS=2 infra/aws/scripts/aws-lab.sh services`를 사용한다.
   **이는 실제 AWS 자원을 변경하는 단계이며 오프라인 준비 명령에 포함되지 않는다.**

   기본값은 비활성이다. 명시적으로 선택한 경우에만 고정 앱 1~4대와 별도 `c6i.xlarge` 부하 발생기를 사용한다.
   앱 수는 min=desired=max로 고정하고 자동 확장 정책을 사용하지 않는다. 실험용 ALB에는 현재 부하 발생기의
   공인 IPv4 `/32`로부터 오는 HTTPS만 추가로 허용한다. 세션 Redis와 캐시 Redis는 계속 분리한다.
   모든 자원은 기존 lab Terraform 상태·RunId·FencingToken·만료/정리 체계에 남는다.
   이 추가 단계는 CLI 로그인에서의 1시간 역할 세션에 맞춰 실행 40분, 정리 5분,
   lease 전환 5분으로 제한한다. 기존 자원은 실패 시 보존하고 별도 `down`으로 정리한다.
   자격증명 만료 여유 5분과 기존 lease·fencing 검사는 그대로 유지한다.
   기존 B 앱이 재고 초기화 중이라면 `B_CACHE_BENCHMARK_EXISTING_APP_HANDOFF=true`를 별도로 선택할 수 있다.
   이 경로는 앱 대수를 바꾸지 않고, 이미 렌더링된 B 앱의 이미지·JAR·환경과 실행 상태를 확인한 뒤
   측정 실행기로 제어를 넘긴다. 일반 B 기동 스크립트의 예약 재고 초기화/HTTP readiness 대기를 다시 실행하지 않는다.
   원본 앱이 아직 초기화 중일 수도 있으므로 이 단계는 일반 서비스의 준비 완료를 뜻하지 않는다.
   실행기는 이후 `cache-benchmark` 읽기 전용 프로필로 전환하고 ALB readiness·토큰 차단·응답 동일성을
   확인한 뒤에만 부하를 보낸다. 일반 B 배포는 기존의 재고 초기화와 readiness 검증을 유지한다.
   기동 스크립트가 아직 실행 중이면 같은 자원을 동시에 수정하지 말고, 해당 기동 작업 종료와
   lease 정리를 확인한 다음 전환한다. 기존 앱 환경이 없는 새 호스트는 이 전환 경로에서 거부한다.
   최초 B 복원과 전체 배포에는 기존 실행 시간이 필요하므로, 새 환경의 만료 시간은
   복원 시간과 각 배포 단계의 여유를 포함해야 한다. 6시간 TTL은 복원이 15분을 넘으면
   이후 단계의 5시간 45분 lease를 수용하지 못하므로 전체 시작에는 더 긴 TTL을 사용한다.
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

앱의 원본 환경 파일은 보존하고, 같은 Compose·CPU/메모리 설정에 임시 `aws,cache-benchmark` 환경 파일을 사용한다.
기본적으로 이미 실행 중인 동일 이미지를 사용한다. 기존 B 기준 앱과 측정 이미지가 다르면
`originalApplication: {appImage, appCommit}`에 원본의 정확한 digest와 커밋을 명시한다.
실행기는 원본 이미지/환경의 일치를 확인한 뒤 ECR에 이미 게시된 `appImage`를 임시 이미지 설정으로
선택한다. **OFF/ON은 같은 측정 이미지**를 사용하며 종료·복구 시 원본 이미지와 환경으로 돌아간다.
이미지 가져오기 전에 복구 기록과 타이머를 만들고, 원본 파일이 외부에서 바뀌면 덮어쓰지 않는다.

초기화 중인 원본 앱에서 전환하려면 정확한 `originalApplication`과 함께
`allowInitializingOriginal: true`를 명시한다. 원본 컨테이너·이미지·환경 해시가 앞선 B 캐시
handoff 기록과 일치해야 하며, 실패한 앱을 정상으로 취급하지 않는다. 초기화 중이었던 앱은
종료 시 원본 이미지·환경·실행 상태로 되돌리되 HTTP readiness를 확인했다고 표시하지 않는다.
앱별 `recovered-*.json`에 확인 범위를 남긴다. 준비된 일반 서비스를 대상으로 하는 기본 경로는
계속 readiness까지 기다린다. 한계 비교를 위해 실험 사이 재개된 예약 초기화가 남긴 DB 부하도
가라앉힌 뒤 측정하고, 작업 종료 후에는 기존 lab destroy로 실험 자원을 정리한다.

데이터 버전은 V28이어야 한다. 원본 데이터·스냅샷과 B 런타임 binding은 변경하지 않는다.
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
예열과 SSM 제어 시간을 포함해 두 조건을 한 lease 안에 끝내기 어렵다면
`capacityVariants: ["cache-off"]`, `["cache-on"]`으로 각각 새 실험 ID를 사용한다.
양쪽 인프라·데이터·VU·판정 기준은 동일하게 유지한다.

`responseCheck`의 기본값은 `canonical`이다. 발생기의 JSON 정렬 비용이 병목 후보이면
`body`로 선택해 의미적으로 검증한 V1 응답 본문 전체를 모든 요청에서 비교할 수 있다.
어느 방식도 응답 검증을 샘플링하거나 상태 코드만 확인하지 않는다. 본문 직렬화 순서까지
동일해야 하므로 같은 fixture로 반복 확인한 뒤 양쪽 조건에서 같은 방식을 사용한다.

- 기준: p95 ≤ `p95LimitMs`, 응답 오류 0, 요청 누락 0, 실제 성공 RPS ≥ 설정값의 99%.
- `confirmed-boundary`: 낮은 경계는 반복 통과하고 높은 경계는 반복 실패했다. 측정 해상도와 SLO를 함께 기록한다.
- `lower-bound-only`: 상한까지 통과했다. 최대값이나 증가율로 바꾸어 적지 않는다.
- `unstable-or-inconclusive`: 반복 결과가 달라 경계 확정 불가.
- `inconclusive-generator-or-evidence`: 요청 누락·지표 불일치·부하 발생기 CPU/메모리 여유 부족 등으로 서버 상한 판정 불가.

기본 `capacityCriterion: "latency-slo"`는 위 응답 시간 기준을 포함한다. 예제의 100ms는
비교용 임시 기준이며 Airbob의 사용자 요구나 서버 포화를 입증하는 값이 아니다.
물리적인 처리 한계를 조사하려면 `capacityCriterion: "completed-load"`를 명시한다.
이 경우 p95 기준을 넘어도 탐색을 계속하며, 오류 0·k6 dropped_iterations 0·목표 요청량/RPS의 99% 이상 달성
조건과 발생기/앱 관측 검증은 그대로 적용한다. `sloPassed`에 원래 p95 기준의 판정을,
`capacityPassed`에 선택한 처리량 판정을 각각 기록한다. 결과는 항상 선택한 기준과
측정 시간 안에서의 반복 검증이며, 포화 원인은 같은 구간의 자원 지표로 별도 확인한다.
요청 시작 시각을 기준으로 측정 구간을 삼등분한 `phaseLatencyMs`도 남긴다. 완료 조건을
통과해도 후반으로 갈수록 지연이나 앱의 대기가 증가한다면 장시간 안정적인 처리량이라고
단정하지 않고 더 낮은 부하 또는 더 긴 관측으로 확인한다.

부하 발생기는 고정 `clientVUs`를 쓰고 요청 구간의 CPU·가용 메모리를 기록한다. CPU 90% 이상 또는 가용 메모리 15% 미만이면
처리량 근거를 불충분으로 분류한다. VU 부족으로 누락되면 같은 서버 조건을 유지한 채 발생기 설정을 조정해 새 실험으로 재측정한다.
예제 VU 2,000개가 모든 RPS에 충분하다는 보장은 없다. 상한은 AWS 모드에서 최대 20,000 RPS이며 이는 서비스 목표나 예상 성능이 아니다.

자동 탐색이 누락 때문에 멈추면 `capacityProbeRates: [1200, 1400, 1600]`처럼 고정한
요청률을 진단할 수 있다. 각 요청률을 `capacity.probeSeconds` 동안 `rounds`회 측정하며,
두 번째 회차는 역순으로 진행한다. 결과의 `fixed-rate-observations`는 자동으로 확정한
최대값이 아니다. 누락 지점에서는 같은 앱·DB·요청률에 VU만 늘린 대조 실험으로 발생기
동시성 설정의 영향을 확인하고, 통과 구간은 긴 측정으로 다시 검증한다.

`hikariMaximumPoolSize`를 명시하면 실험용 앱 환경에서만 풀 크기를 10~40개로 지정하고,
실제 적용값을 앱 지표로 확인한다. 값을 생략하면 원래 설정을 유지하며 복구 시 원본 환경을
그대로 사용한다. 풀 크기 변경 실험은 병목 진단용으로 구분하고, 캐시 OFF/ON의 최종 성능
비교에서는 같은 풀 크기를 사용한다. 예를 들어 VU만 늘린 결과와 풀 크기까지 바꾼 결과를
한꺼번에 비교해 풀 크기 효과라고 해석하지 않는다.

`capacity.warmupRps`는 처리량 단계의 예열 요청률이다. 예제는 100 RPS이며 실제로는
`min(해당 단계 RPS, warmupRps)`로 `warmupSeconds` 동안 예열한다. 값이 없는 기존 설정은
이전의 최대 40 RPS 예열을 유지한다. 예비 측정에서 JVM·DB 지표의 안정화를 확인한 뒤
본 비교의 예열 조건을 고정한다. 예열 결과도 별도 JSON에 남기며 본 측정 통계에는 넣지 않는다.

새 JVM에 처음부터 높은 부하를 주면 예열 자체가 과부하될 수 있다. 필요한 경우
`capacity.conditioningStages: [{"rps": 100, "seconds": 30}, {"rps": 400, "seconds": 60}]`으로
각 캐시 조건을 시작할 때 낮은 부하부터 예열한다. 이후에는 각 측정 단계의 기존 예열을
그대로 수행한다. 초기 예열은 증가하는 요청률로 최대 3단계·총 300초까지이며 양쪽 조건을
같게 설정한다. 예열 중 오류나 요청 누락이 있으면 결과를 저장하고 본 측정 전에 중단한다.

`capacity.rampSeconds`를 1~60초로 지정하면 본 측정의 **같은 k6 프로세스와 VU**에서
1 RPS부터 목표 RPS까지 서서히 올린 뒤 `probeSeconds` 또는 `confirmSeconds` 동안 유지한다.
별도 예열 프로세스가 종료된 뒤 새 연결을 만들며 곧바로 고부하를 시작하는 영향을 구분하기 위한
선택값이다. 생략하거나 0이면 기존의 즉시 시작 방식을 유지한다.

`completed`, p95, 성공 RPS, 앱 CPU 표본과 발생기 자원 판정은 요청 시작 시각이 유지 구간에
속한 요청을 기준으로 한다. 램프에서 시작해 늦게 끝난 요청은 램프에 남는다. 모든 응답의 본문을
검증하며 램프 중 응답 오류와 전체 실행 구간의 `dropped_iterations`도 통과를 막는다.
서버 카운터는 램프를 포함하므로 `totalCompleted`와 대조한다. `ramp.completed`와 유지 구간
요청 수를 따로 남기며, DB 로딩·SELECT·누적 앱 타이머는 이 전체 범위다. 램프 요청으로 유지
구간의 요청 누락을 채워 통과시킬 수 없다. TTL 검증과 원격 실행·복구 기한에도 램프 시간을 포함한다.

시간 기반 실행에서는 명목 요청 수(`RPS × 초`)와 실제 유지 구간 집계가 정확히 같지 않을 수 있다.
`dropped_iterations`는 VU가 없어 시작하지 못한 반복을 세며, 명목 요청 수와의 모든 차이를 뜻하지 않는다.
따라서 처리량의 기존 99% 달성 기준을 요청 수에도 동일하게 적용한다. 유지 구간의 요청 수와
실제 성공 RPS가 각각 목표의 99% 이상이어야 하고, `dropped_iterations`·응답 오류는 여전히 0이어야 한다.
`minimumCompletedForOfferedLoad`, `offeredLoadRatio`, `nominalRequestShortfall`을 함께 남긴다.
명목량과의 차이를 가상의 완료 건수로 채우지 않으며, 실제 RPS도 보정하지 않는다. 이전의 정확한 건수
비교와 임시 밀리초 허용량 판정은 이 달성률 판정으로 대체한다. 과거 결과를 재판정할 때는 원본을 보존하고,
적용 전/후 전체에 같은 기준을 적용하며 실제 달성 RPS를 함께 공개한다.
시간 분리에는 [k6의 시나리오 시작 시각](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/)을
사용하며, [고정 버전 실행기](https://github.com/grafana/k6/blob/v1.5.0/lib/executor/ramping_arrival_rate.go)는
고정 종료 시각과 별도의 도착 스케줄로 실행된다.

처리량 측정 중에는 각 앱의 localhost Prometheus 지표를 SSM으로 약 10초 간격으로 읽어
`*-app-samples.ndjson`에 남긴다. 커넥션 풀 사용·대기, 획득/보유 시간, CPU, GC와 JVM 스레드를
허용 목록으로 수집하며 환경 변수·토큰·응답 본문은 수집하지 않는다. `*-app-diagnostics.json`의
최대 대기는 표본 최댓값이며 순간 최댓값을 보장하지 않는다. 앱 시각이 실제 요청 구간에 있는
표본만 요약하고, 누적 지표는 k6 초기화·종료 대기를 포함한 전후 snapshot 차이를 사용한다.
관측 실패·앱 교체·필수 지표 누락은 한계 판정을 미확정으로 남긴다. 이 샘플링에는 작은 추가
관측 부하가 있으며 DB·Redis 자체의 포화까지 증명하지는 않는다. 해당 원인을 주장하려면
같은 시간대의 RDS/Redis 지표 또는 별도 진단 실험을 함께 확인한다.
간헐적인 지연은 GC 정지와 커넥션 획득·보유 시간의 최대값도 함께 본다. 이 최대값은
Micrometer가 일정 기간 보관하는 값이므로 현재 표본 시각에 발생했다고 단정하지 않고,
전후 누적 시간·횟수 변화와 함께 해석한다.

각 단계 전에 전용 캐시를 비우고 예열해 긴 탐색 중 이전 단계의 TTL이 만료되는 것을 피한다.
기본 TTL 10분·Jitter 2분을 유지하며 한 측정 구간은 5분까지다. 캐시 적중 실험에서 DB 로딩이 발생하면
캐시가 예열된 상태라는 가정을 검증하지 못했으므로 해당 근거를 거부한다.

`deadlineSeconds`는 기존 measurement lease 한도인 90분 이하이며 마지막 10분은 복구를 위해 남긴다. 설정한 탐색이 기한에 맞지 않으면
결과를 완료로 표시하지 않고 복구한다. 처리량 실험은 다른 시나리오와 분리하고, 필요하면 회차/범위를 나눈다.
CLI 로그인에서 역할 세션을 한 번 발급받아 환경 변수로 사용하는 경우에는 측정 기한도 그
세션보다 짧게 선택해야 한다. 긴 측정은 MFA 로그인한 `dev-eeoos`를 `source_profile`로 하고
`airbob-lab-operator`를 선택하는 AWS CLI 역할 프로필의 자동 갱신을 사용할 수 있다.
측정 실행기는 모든 AWS 호출과 lease 호출에 같은 역할 프로필을 전달하며, 기한·복구 여유는
그대로 유지한다. 설정에는 자격증명을 저장하지 않고 원본 로그인 세션도 유효해야 한다.

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
