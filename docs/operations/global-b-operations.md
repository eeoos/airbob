# 글로벌 B 복원·개발 환경 인수 도구

검증된 SQL release와 Elasticsearch native snapshot을 옮기고, 같은 DB 볼륨을 일반 개발 환경에서 이어 쓰기 위한 도구다. 실제 수행 결과와 한계는 [검증 요약](global-b-verification.md)에 있다. 원본 CSV, 압축 덤프, 비공개 계정, 전체 로그와 봉인된 실행 자료는 Git 외부의 영구 보관소에 둔다.

## 입력과 실행 조건

- MySQL 8.4.11, V1–V28 마이그레이션, 검증 당시의 앱 JAR와 ETL 실행 도구를 함께 확인한다. 임의의 최신 소스로 봉인된 release를 덮어쓰지 않는다.
- release의 consumer manifest와 checksum 파일 해시는 독립적인 인수 기록에서 받는다. 내부 checksum 파일 하나만으로 출처가 확인되는 것은 아니다.
- Python 3.12 이상과 JDK 21을 사용한다. 실제 시간대 검증은 JDK 21.0.12.1에서 통과했다. Java/Python의 시간대 규칙과 고정 경계 사례를 확인하는 검사가 준비 단계에 포함된다.
- 모든 경로·컨테이너·볼륨·서버 UUID·이미지 digest는 실제 관측값으로 설정한다. 입력 파일과 이전 단계의 결과 해시가 바뀌면 다음 단계를 중단한다.
- 전체 업무 DB는 최대 하나를 순차 사용한다. 압축 SQL은 스트리밍하고 전체 평문 임시 파일을 만들지 않는다. Mac 파일시스템과 Docker VM 내부의 여유 공간을 각각 확인한다.
- 비공개 계정 파일은 실제 디렉터리 권한 0700, 파일 권한 0600을 유지한다. 비밀번호나 세션 값은 명령 인자, Git, S3, 공개 보고서에 넣지 않는다. 계정별 비밀번호를 적용한 뒤 정상 로그인·본인 확인·로그아웃을 통과한 계정만 사용 가능으로 표시한다.

## 도구별 역할

| 목적 | 진입점 | 실행 범위 |
|---|---|---|
| 새 로컬 DB 또는 검토한 기존 DB 교체 | [restore-growth-b-local.py](../../scripts/restore-growth-b-local.py) | 기본 호출은 사전 확인이다. `--apply`에는 정확한 사전 결과와 SHA가 필요하다. |
| OCI 호스트에서 같은 복원 수행 | [restore-growth-b-oci.py](../../scripts/restore-growth-b-oci.py) | 호스트에서 직접 실행하며 SSH 전송은 수행하지 않는다. |
| 검증 완료 볼륨을 일반 Compose에 인수 | [adopt-growth-b-dev.py](../../scripts/adopt-growth-b-dev.py) | SQL을 다시 가져오거나 DB·볼륨을 새로 만들지 않는다. |
| 검색 companion 생성 | [produce-growth-b-search-snapshot.py](../../scripts/produce-growth-b-search-snapshot.py) | 정확한 앱 JAR와 검증된 MySQL에서 검색 문서를 만들고 native snapshot을 봉인한다. |
| 검색 companion 복원 | [restore-growth-b-search.py](../../scripts/restore-growth-b-search.py) | 전체 문서 ID·필드·매핑을 비교한 뒤 alias를 활성화한다. |
| 일반 개발 앱 확인 | [verify-growth-b-dev.py](../../scripts/verify-growth-b-dev.py) | 대표 계정 로그인·본인 확인·권한·역할별 조회·검색·로그아웃을 확인한다. |
| B 자료 게시·가져오기 | [publish-growth-dataset-b.py](../../infra/aws/scripts/publish-growth-dataset-b.py), [fetch-growth-dataset-b.py](../../infra/aws/scripts/fetch-growth-dataset-b.py) | 정확한 객체 VersionId·크기·SHA를 고정하고 대용량 파일은 제한된 조각으로 처리한다. |
| AWS 준비 및 복원 | [growth_b_aws_restore.py](../../infra/aws/scripts/growth_b_aws_restore.py), [growth_b_prepare.py](../../infra/aws/scripts/growth_b_prepare.py) | 계획·사전 확인·실행을 구분하고 자원 소유권, lease, TLS, 쓰기 중지, 작은 RDS 복원 결과를 확인한다. |

세부 인자는 각 진입점의 `--help`에 있다. 새 로컬 복원의 사전 확인은 다음 형태다. 경로는 실제로 검토한 입력으로 바꾼다.

```sh
python3 scripts/restore-growth-b-local.py \
  --config /absolute/private/restore-config.json \
  --output /absolute/evidence/new-preflight
```

기존 검증 볼륨의 일반 개발 환경 인수는 `plan → observe → baseline → prepare → produce → restore` 순서다. 각 실행 단계는 해당 자원의 최신 `observe` 결과와 이전 단계의 해시를 받는다. MySQL·producer·service 역할에 맞는 Compose 자원은 도구 밖에서 준비한다. 계획 확인 예시는 다음과 같다.

```sh
python3 scripts/adopt-growth-b-dev.py plan \
  --config /absolute/private/adoption-config.json \
  --config-sha256 REVIEWED_CONFIG_SHA256 \
  --output /absolute/evidence/new-adoption-plan
```

계획 확인만으로 일반 개발 앱이 시작되지는 않는다. 최종 인수 후의 앱 시작은 평소 개발 프로젝트에서 수행한다. 일반 앱의 스케줄러가 재고를 보충한 뒤에는 인수 당시의 동결된 전체 행 지문을 현재 DB의 고정값으로 취급하지 않는다.

`complete-growth-b-local.py`와 `serve-growth-b-local.py`는 별도 로컬 스택 설치·유지 경로다. 이번 완료된 일반 Compose 인수는 `adopt-growth-b-dev.py`를 사용했다. 두 경로를 같은 실행으로 중복 적용하지 않는다.

## 반복 검증과 결과 보존

첫 번째 성공 증거와 입력의 동일성이 확인되면, 실패한 두 번째 반복만 다시 실행할 수 있다. 실제 B 완료 실행은 이 방식으로 첫 번째를 인계하고 두 번째만 재실행했다. 덤프를 다시 생성하지 않았다. 한국 자정을 가로지를 때 재고 검증 날짜 범위가 달라지는 문제 자체는 수정하지 않았고, 같은 봉인 앱·도구로 자정 뒤 초기화해서 통과했다.

봉인 release와 기존 결과 JSON의 원래 바이트는 유지한다. 영구 보관 경로가 달라지면 원본을 수정하는 대신 경로 매핑과 복사 전후 해시를 별도로 남긴다. 과거 결과에 들어 있는 개인 경로를 공개 문서에 복사하지 않는다. 현재 사용하는 비공개 계정 파일과 native repository는 개발 프로젝트의 비공개 디렉터리에서 계속 사용할 수 있다.

## 테스트와 배포 범위

계정 fixture의 출처와 오프라인 검사 명령은 [fixture 설명](../../infra/aws/tests/fixtures/etl-account-runtime/README.md)에 있다. B 계약 381개, 게시 계약 13개, 인프라 표현식 검사 9개가 별도 ETL checkout 없는 환경에서 통과했다. 실제 MySQL 잠금·재고·시간대 검사는 Testcontainers로 격리한다.

PR은 CI를 실행한다. `main`에 반영된 앱·배포 관련 변경은 별도의 OCI CD 실행 대상이므로 PR CI 통과와 배포 완료는 다른 상태다. 이 변경의 실제 완료 범위는 로컬 검증까지이며, B의 S3 게시·AWS/OCI 복원·클라우드 로그인은 완료했다고 주장하지 않는다. 최종 B RDS 실행에는 같은 도구로 성공한 작은 RDS 복원 결과가 추가로 필요하다.
