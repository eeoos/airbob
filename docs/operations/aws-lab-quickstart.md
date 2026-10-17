# AWS 실험 환경 사용

```bash
make lab-start
make lab-status
```

`lab-start`는 저장된 B 스냅샷을 사용합니다. 자원이 없으면 새 RDS를 복원하고 32개 테이블의 정확한 행 수·스키마를 확인한 뒤 검색·CDC·앱을 준비합니다. 이미 켜져 있으면 접속을 확인하고, 일시 정지 상태라면 같은 자원을 다시 켭니다. SQL 덤프를 다시 적재하지 않습니다.

준비가 끝나면 만료 시각, RDS 복원 시간, RDS 준비 후 앱 사용 가능까지 걸린 시간, AWS ALB와 API 확인 명령이 표시됩니다. API 확인 명령의 `--connect-to`는 원래 인증서 이름을 유지하면서 AWS ALB로 연결합니다. 기존 OCI 도메인 연결은 바꾸지 않습니다.

## 낮에 사용하고 밤에 정지하기

```bash
make lab-pause
# 다음 사용 시
make lab-start
```

정지는 DB를 초기화하지 않습니다. 현재 DB 변경 내용, 인스턴스와 디스크를 보존하며 다음 `lab-start`에서 재개와 접속 확인을 수행합니다. 재개만 실행하려면 `make lab-resume`, 현재 위치의 접속 권한과 준비 상태를 다시 확인하려면 `make lab-access`를 사용합니다.

새 환경의 기본 유지 시간은 24시간입니다. 일주일 동안 같은 환경을 사용할 때는 **새 자원을 만들기 전에** 기간을 선택합니다.

```bash
make lab-start LAB_ARGS="--ttl-hours 168"
```

선택 범위는 새 환경에 대해 6~168시간입니다. 정지·재개는 처음 정한 만료 시각을 연장하지 않습니다. 별도로 고정한 운영 기한이 있으면 그 상한이 우선합니다. 기간 선택 자체가 비용 예산을 증액하지는 않습니다.

## B 상태로 다시 시작하기

```bash
make lab-destroy
make lab-start
```

`lab-destroy`는 현재 Terraform 실험 자원을 삭제합니다. 보관용 수동 B 스냅샷과 Foundation은 유지합니다. 다음 `lab-start`는 이 스냅샷에서 **새 RDS**를 만들므로 이전 실험 중 변경한 DB 내용은 이어받지 않습니다. 보관된 스냅샷의 과거 운영 만료 시각이 새 실행을 막지는 않습니다.

## 설정과 최초 준비

저장된 공개 기준 설정은 `infra/aws/lab-baseline.json`입니다. 매번 실행 ID, RDS UUID, 해시, 증거 JSON을 만들 필요가 없습니다. 다른 AWS 프로필은 다음처럼 선택합니다.

```bash
make lab-start LAB_ARGS="--profile my-admin-profile"
```

구성된 AWS 프로필, GitHub CLI 인증, AWS CLI, Terraform, Session Manager plugin, `curl`, `git`, `lsof`가 필요합니다. Python은 PyMySQL 1.1.2를 사용합니다. 이 Mac용 Makefile은 `.local/airbob-lab/venv`와 `.local/airbob-lab/bin`이 준비돼 있으면 이를 사용합니다. 다른 컴퓨터에서 Python 환경을 만들 때는 다음 두 명령을 한 번 실행합니다.

```bash
python3 -m venv .local/airbob-lab/venv
.local/airbob-lab/venv/bin/python -m pip install PyMySQL==1.1.2
```

명령은 검토된 현재 `main` 소스에서 실행합니다. AWS 인증이 만료되거나 작업이 실패하면 진행 기록과 자원을 보존합니다. 로컬 대기가 중단됐다면 `lab-start`는 이미 접수된 작업 기록을 읽습니다. 접수·게시 성공 여부가 불분명한 작업은 자동으로 다시 제출하지 않습니다.

자동화용 JSON 출력은 `LAB_ARGS="--json"`으로 선택할 수 있습니다. 운영자가 기존 승인 기한을 유지할 때는 `lab-start`의 `--approved-deadline`에 해당 UTC epoch 상한을 전달합니다. 명시 상한이 없는 새 실행만 GitHub 접수에 최대 5분 여유를 두며, 실제 자원의 선택 TTL은 그대로 유지됩니다.

현재 검증 범위는 새 대상의 32개 테이블 행 수·DDL, 네이티브 검색 복원 수·대표 조회, 서비스 준비 상태입니다. 전체 행 내용 해시나 실제 부하 시험이 완료됐다는 의미는 아닙니다.
