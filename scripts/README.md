# 실행 도구 안내

## 배포와 검색 운영

| 파일 | 용도 |
|---|---|
| [deploy-oci.sh](deploy-oci.sh) | OCI 앱 배포. 기존 앱 중지, DB 마이그레이션, Kafka·Debezium 준비, 앱 상태 확인 순서로 실행한다. |
| [sync-oci-deployment-assets.sh](sync-oci-deployment-assets.sh) | 검토한 Compose·설정·마이그레이션과 이 표의 스크립트 5개를 배포 디렉토리에 동기화한다. |
| [preflight-reservation-inventory-cutover.sh](preflight-reservation-inventory-cutover.sh) | Flyway 실행 전에 예약 재고 전환 조건을 검사한다. 배포 과정에서 호출된다. |
| [init-ssl.sh](init-ssl.sh) | OCI 최초 인증서를 발급한다. 운영 Nginx 설정을 바꾸지 않고 임시 컨테이너를 정리한다. |
| [reindex-accommodations.sh](reindex-accommodations.sh) | 새 Elasticsearch 인덱스를 채우고 검증한 뒤 검색 alias를 전환한다. |

배포는 [OCI 운영 안내](../docs/operations/oci-deployment-runbook.md),
검색 복구는 [전체 재색인 절차](../docs/operations/logstash-reindex.md)를 따른다.
옛 CodeDeploy의 JAR 직접 실행·종료 스크립트와 ZIP 패키징 경로는 제거했다.

## B 데이터셋 복원과 개발 환경

| 파일 | 용도 |
|---|---|
| [restore-growth-b-local.py](restore-growth-b-local.py) | 검토한 데이터셋을 로컬 MySQL에 복원한다. |
| [restore-growth-b-oci.py](restore-growth-b-oci.py) | 같은 복원 절차를 OCI 호스트에서 실행한다. |
| [adopt-growth-b-dev.py](adopt-growth-b-dev.py) | 보존한 데이터 볼륨을 일반 개발 Compose에 연결·검증한다. |
| [produce-growth-b-search-snapshot.py](produce-growth-b-search-snapshot.py) | 데이터셋에 대응하는 검색 스냅샷을 생성한다. |
| [restore-growth-b-search.py](restore-growth-b-search.py) | 검색 스냅샷을 복원하고 검증한다. |
| [verify-growth-b-dev.py](verify-growth-b-dev.py) | 실행 중인 일반 개발 앱에서 대표 계정의 조회를 확인한다. |
| [complete-growth-b-local.py](complete-growth-b-local.py) | 별도 로컬 스택에 MySQL과 검색 데이터 준비를 완료한다. |
| [serve-growth-b-local.py](serve-growth-b-local.py) | 위 별도 로컬 스택의 고정된 앱 이미지를 실행·관리한다. |

입력 파일·버전·복원 순서는 [B 데이터셋 운영 안내](../docs/operations/global-b-operations.md)를 따른다.
일반 개발 Compose 인수와 별도 로컬 스택은 서로 다른 경로다. 이 도구들은 필요한
`infra/aws/scripts/` 모듈과 함께 저장소에서 사용하며, 일반 OCI 앱 배포에는 복사하지 않는다.

## CI 검사 선택과 결과

- [ci/scope.py](ci/scope.py): 정확한 Git 변경 범위에서 앱·API 문서·인프라·데이터 검사와 OCI 배포 여부를 고른다.
- [ci/check_result.py](ci/check_result.py): 선택한 모든 검사가 성공했는지 확인하는 필수 `CI result` 검사다.

PR과 main 배포는 같은 CI 정의를 사용한다. 전체 흐름은 [CI·CD 안내](../docs/operations/oci-deployment-runbook.md#ci와-cd의-역할)를 따른다.

## 보관한 일회성 도구

[bootstrap-oci-b-host-runtime.py](archive/bootstrap-oci-b-host-runtime.py)는 당시 OCI ARM64 호스트에
고정된 Python 런타임을 설치·검증한 원본이다. 버전·경로·입력 해시를 당시 값으로 보존했다.
현재 배포나 데이터 복원의 자동 단계로 실행하지 않는다.

`scripts/` 중 배포와 검색 운영 표의 5개 파일만 OCI CD 대상이다.
데이터셋 도구·보관 코드·이 안내만 바뀌면 OCI 앱을 다시 배포하지 않는다.
