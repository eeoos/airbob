# 쿠폰 AWS 실험 준비와 실행

`make aws-coupon-prepare`는 **로컬 파일만 생성**한다. AWS·Terraform·Docker·k6를 호출하지 않으며,
세션 생성, 이미지 게시, 앱 재시작, DNS 변경도 하지 않는다.
실제 측정은 나중에 준비된 전용 Linux 부하 발생기에서 별도로 실행한다.

## 지금: 오프라인 준비

```bash
make aws-coupon-prepare
```

기본 출력은 `build/k6/coupon-aws/prepared/`다. 기존 경로를 덮어쓰지 않는다.
다른 설정·경로는 `COUPON_CONFIG=/absolute/config.json COUPON_PREPARED=build/k6/coupon-aws/prepared-2`로 지정한다.

| 파일 | 용도 |
|---|---|
| `coupon-tools.tar.gz` | Linux 호스트에 전달할 k6·Python·Node 도구, 같은 디렉토리 구조 유지 |
| `config.json` | 실험 조건과 환경 경로. 비밀번호·토큰 값은 없음 |
| `plan.json` | 필요한 회원 수, 실행 수·시간, 소스/번들 해시, AWS·네트워크 호출 0회 표시 |
| `app.env.example` | 쿠폰 전용 앱 프로필과 배경 작업 비활성 설정 |
| `cloudwatch-read-policy.json` | 부하 발생기에 필요한 리전 제한 CloudWatch 읽기 권한 예시 |
| `SHA256SUMS` | 전달 중 파일 변경 확인 |

`readyToExecute=false`는 오프라인 준비로 실제 앱·자격 증명·데이터 상태까지 확인한 것은 아니라는 뜻이다.
`example=true` 설정은 실제 실행을 거부한다. 설정이나 소스가 바뀌면 새 경로로 다시 준비한다.
준비한 묶음에 앱 바이너리·개인 세션·DB 비밀번호·토큰은 포함하지 않는다.

## 앱과 데이터 준비 조건

현재 변경을 포함한 앱 이미지를 기존 **GitHub OIDC 발행 경로**로 게시한 뒤, 정확한 digest를 사용한다.
오프라인 준비는 이 게시를 수행하지 않는다. 기존 B 데이터/앱의 승인된 조합과 배포 절차도 유지한다.

쿠폰 전용 앱은 `SPRING_PROFILES_ACTIVE=aws,coupon-performance`로 실행한다.
그룹 설정이 `coupon-benchmark`를 함께 활성화한다.

- 같은 이미지에서 V2 조건부 UPDATE와 V1 Lua를 제공하며 양쪽 모두 DB 커밋 후 201을 반환한다.
- 쿠폰·세션·실험용 쿠폰 관리 API와 health/prometheus만 허용한다. 일반 예약·결제·회원 생성 API는 차단한다.
- 스케줄러·Kafka 소비·인벤토리 시딩·외부 쓰기를 끈다. Hikari는 쓰기 가능 상태로 유지한다.
- Flyway는 **기존 V28의 검증만** 수행한다. 앱 시작으로 데이터를 마이그레이션하지 않는다.
- 기존 읽기/캐시 실험 프로필과 섞으면 시작을 거부한다. 기존 `SPRING_PROFILES_INCLUDE=read-model-benchmark`
  같은 설정을 남긴 채 프로필을 덧붙이지 않는다.
- 원본 런타임 설정을 보존하고 실험용 설정에서 `app.env.example` 값을 적용한다. DB·Redis·Elasticsearch 연결과
  IAM 역할은 해당 격리 lab의 기존 설정을 사용한다. 앱마다 같은 벤치마크 토큰을 사용한다.

이 모드는 이미 복원·검증된 **최종 B / V28** 대상으로 준비한다. 구형 `isolated-read` 시작 계약이나 V27
`performance-lab` 모드를 쿠폰 모드라고 간주하지 않는다. `make lab-start`만으로 이 프로필이 선택되지는 않는다.
앱 수·이미지·프로필 전환은 기존 실험 환경의 배포 단계에서 별도로 적용하고, 끝나면 원본 설정으로 복구한다.
쿠폰 실행기는 EC2/RDS/ASG를 생성·시작하거나 앱 프로필을 전환하지 않는다.

## 최종 B의 실제 회원 목록 연결

기존 `benchmark-dataset-v2`는 구형 전체 데이터 계약이다. 최종 B 측정에서는 소스 데이터에 연결된
작은 **`coupon-accounts-v1` manifest**를 사용한다. 로컬 smoke test의 가짜 전체 manifest를 AWS에 재사용하지 않는다.

준비된 데이터셋에서 사용할 **실제로 존재하는 ACTIVE 회원 이메일의 JSON 배열**과 원본 데이터 manifest/envelope를 준비한다.
이메일 목록은 로그인에 사용할 공통 비밀번호가 적용된 실험 계정들로 구성한다. 관리자 계정은 이 목록과 분리한다.

```bash
python3 load-test/k6/coupon/prepare_aws.py accounts \
  --dataset-id 실제-데이터셋-id \
  --source-manifest /absolute/source-dataset-envelope.json \
  --emails /absolute/active-coupon-account-emails.json \
  --output /absolute/coupon-accounts.json
```

`--dataset-id`에는 실제 영문/숫자/하이픈 식별자를 넣는다. 출력에는 원본 파일 SHA-256, 데이터셋 ID,
V28 선언, 계정 목록과 개수만 들어간다. 이 명령은 **계정을 만들거나 DB에 접속하지 않는다.**
원본 파일과 실제 RDS 복원 대상의 일치는 데이터 복원 증거로 확인해야 하며, 계정의 유효성은 이후 실제 로그인으로 확인한다.

최대 RPS × 측정 시간 + 1만큼 서로 다른 회원이 필요하다. 기본 예제는 **180,001명**이다.
부족한 계정이나 만료된 세션을 순환 사용하지 않는다. 계정 준비가 필요하면 측정 전에 전용 데이터 준비 단계에서 수행한다.
로그인에도 시간이 들며 모든 세션의 TTL은 1시간이다. 계정 수가 많을 때는 측정 시간·RPS와 로그인 소요 시간을 함께 검토한다.
실행기는 오래된 세션으로 측정하는 대신 중단하거나 새로 로그인한다.

## 실제 환경이 준비된 다음 입력할 값

`config.json`을 복사해 다음을 채운다.

- `baseUrl`: **실험 ALB에만 연결되는 HTTPS origin**. 인증서 검증을 끄지 않는다.
  운영 `api.airbob.cloud`를 그대로 입력하면 OCI에 연결될 수 있으므로 사용하지 않는다.
  동일 인증서 이름을 써야 한다면 전용 부하 발생기의 호스트 이름 해석만 실험 ALB로 연결하고,
  Python·Node·k6 모두 동일한 실험 주소를 사용하는지 확인한다. 공개 DNS는 전환하지 않는다.
- `appMetricsUrls`: ALB 뒤 각 앱의 직접 접근 가능한 private `/actuator/prometheus` 주소.
  ALB URL을 앱 수만큼 반복해서 넣지 않는다. 앱 수와 Hikari 설정은 실험 중 고정한다.
- `appVersion`: 실제 배포한 이미지 digest 또는 40자리 커밋.
- `benchmarkDatasetManifest`: 위에서 만든 계정 manifest의 Linux 호스트 절대 경로.
- `adminSessionFile`: ACTIVE ADMIN 계정으로 실제 로그인한 SESSION_ID 값만 담은 0600 파일.
- `benchmarkTokenFile`: 앱들과 같은 벤치마크 토큰을 담은 0600 파일.
- `accountPasswordFile`: 계정 준비에 사용한 비밀번호 파일. 이미 유효한 세션 파일이 있으면 `null`도 가능하다.
- `sessionFixture`: 새 로그인으로 만든 세션 파일 경로. 자동 생성한 세션은 결과와 분리된 임시 디렉토리에 보관한다.
- `cpu.region`, `cpu.dbInstanceIdentifier`: 앱이 실제로 사용하는 실험 RDS. IAM 예시는 자동 적용하지 않는다.

전용 Linux 부하 발생기에 Python 3.10+, Node.js 18+, AWS CLI와 `infra/aws/toolchain.env`에 고정된 k6를 설치한다.
k6 배포 파일의 SHA-256도 해당 파일의 값으로 확인한다. 이 도구 설치 역시 오프라인 준비에는 포함되지 않는다.
환경을 확인한 뒤 `example=false`로 바꾼다.

**로컬 파일 검사만** 먼저 실행할 수 있다. 로그인·HTTP·AWS 호출은 발생하지 않는다.

```bash
python3 load-test/k6/coupon/run_experiments.py \
  --config /absolute/coupon-experiment.json --check-inputs
```

이 검사는 manifest 형식·계정 수와 개인 파일의 소유권/권한을 확인한다. 앱 로그인·IAM 권한을 검증하는 명령은 아니다.

## 나중: 실제 측정

기존 lab의 실험 점유·만료/정리 절차를 확보하고 다른 부하를 멈춘 상태에서 실행한다.
`plan.json`의 시간에는 로그인 시간이 제외되어 있다. CloudWatch 대기와 정리를 포함해 lab 수명을 충분히 확보한다.
실행기는 기존 점유나 수명을 연장하지 않는다.

```bash
sha256sum --check SHA256SUMS
tar -xzf coupon-tools.tar.gz
python3 load-test/k6/coupon/run_experiments.py \
  --config /absolute/coupon-experiment.json --execute
```

각 RPS에서 새 쿠폰·별도 워밍업·DB/Lua 순서 교차 반복을 수행한다. SQL 수는 앱별 카운터 차이,
CPU는 CloudWatch의 실제 요청 구간 내 완전한 1분 버킷으로 수집한다. 요청 누락이나 불완전한 지표를 0으로 대체하지 않는다.
최상위 단계까지 통과해도 결과가 `lower-bound-only`이면 최대 처리량을 확정한 것이 아니다.

결과는 `build/k6/coupon-experiments/{runId}/`에 남는다. DB 비교 쿠폰은 실행 후 비활성화하며
Lua 쿠폰은 발급 종료 시각과 기존 보존 정책을 따른다. Redis 전체 초기화는 하지 않는다.
실패 시 보고서의 `cleanup-required`/`created` DB 쿠폰을 먼저 확인한다. 결과를 보관한 뒤 앱 원본 설정으로 복구하고
기존 lab 정리 절차를 따른다. 준비 명령은 이 실행·복구·정리 작업을 대신 실행하지 않는다.
