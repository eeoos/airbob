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
- [ci/contracts.py](ci/contracts.py): 아래 두 가지가 바뀌면 `scope` 잡을 실패시킨다. 이 경우 `CI result`도 실패한다.
  - 보관 부하 테스트 파일: `load-test/archive-source.json`에 기록된 해시·모드와 다르거나, 기록되지 않은 파일이 있는 경우
  - 이미 적용된 Flyway 마이그레이션: 수정·삭제된 경우
  - 로컬에서는 `--base <커밋> [--head <커밋>]`으로 과거 변경도 검사할 수 있다.
- [ci/pr_report.py](ci/pr_report.py): PR 점검 보고서의 사실을 결정적으로 모은다. AI는 쓰지 않는다.
  - `tests`: `app` 잡에서 모든 모듈의 JUnit XML을 `test-summary.json`으로 요약한다.
  - `facts`: `report` 잡에서 CI 잡 결과, 테스트, 바뀐 영역, 계약 신호를 `facts.json`과 `report.md`로 만든다. 계약 신호는 API 스냅샷, 마이그레이션, 오류 코드, ArchUnit 동결, 보관 해시, 설정, 빌드다.
  - 로컬에서는 `facts --base <커밋> --head <커밋> --out-dir <폴더>`로 과거 변경을 재현할 수 있다.
- [ci/pr_baseline.py](ci/pr_baseline.py): PR과 비교할 main 테스트 요약을 찾는다.
  - main 실행이 올린 `test-summary-<sha>`를 쓴다. PR base 커밋에 없으면 first-parent로 거슬러 올라가 가장 가까운 이전 main 커밋을 쓴다(최대 30개).
  - 보고서에는 "main 대비 테스트 증감"이 들어간다. 모듈별 증감, 사라진 클래스, 테스트가 줄어든 클래스, 새 클래스를 보여 주고, 감소에는 ⚠️를 단다.
  - PR base는 PR 아티팩트가 아니라 `workflow_run` 이벤트 값을 쓴다. 아티팩트의 base와 다르면 비교하지 않는다. 이 저장소의 main 실행이 만든 아티팩트만 쓴다.
  - 빌드 성공이 기록되지 않은 요약은 일부 모듈의 테스트만 담았을 수 있다. 빌드가 실패한 경우와 `build_outcome`이 없는 이전 형식이 여기에 해당한다. 그래서 PR과 main 어느 쪽이든 비교하지 않는다.
  - 찾지 못함·조회 실패·다운로드 실패도 구분해 표시한다.
- [ci/claude_review.py](ci/claude_review.py): `claude-review.yml`이 PR을 Claude로 리뷰하고 결과를 새 댓글로 단다.
  - git에서 리뷰 기준, `CLAUDE.md`, 바뀐 경로에 해당하는 `.claude/rules`, PR 설명, 커밋, diff, 바뀐 파일을 모은다.
    - 기준과 규칙은 base 커밋에서 읽는다. PR이 자기를 리뷰할 기준을 약하게 바꿀 수 없게 하기 위해서다.
  - Messages API에 도구 없이 리뷰 요청을 한 번 보낸다(구조화 출력). 응답을 받지 못한 요청만 SDK가 재시도한다. 인증은 ID 페더레이션(GitHub OIDC)이다.
  - 로컬에서는 `--dry-run <파일>`로 보낼 요청을 파일로 확인할 수 있다.
- [ci/pr_comment.py](ci/pr_comment.py): `pr-report.yml`(`workflow_run`)이 보고서를 PR 고정 댓글 하나로 만들거나 갱신한다.
  - 아티팩트는 데이터로만 다룬다. PR 번호, head 커밋, 저장소, 마커를 검증한 뒤 텍스트로 게시한다.

`ci.yml`은 CD가 읽기 권한으로 재사용하므로 PR 쓰기 권한을 요청하지 않는다. 그래서 역할을 나눈다.
- 수집: `ci.yml`의 `report` 잡(읽기 전용, `CI result`에 포함되지 않음)
- 게시: main의 `pr-report.yml`

`scripts/ci/test_*.py`는 `scope` 잡에서 매번 실행된다.

PR과 main 배포는 같은 CI 정의를 사용한다. 전체 흐름은 [CI·CD 안내](../docs/operations/oci-deployment-runbook.md#ci와-cd의-역할)를 따른다.

## 보관한 일회성 도구

[bootstrap-oci-b-host-runtime.py](archive/bootstrap-oci-b-host-runtime.py)는 당시 OCI ARM64 호스트에
고정된 Python 런타임을 설치·검증한 원본이다. 버전·경로·입력 해시를 당시 값으로 보존했다.
현재 배포나 데이터 복원의 자동 단계로 실행하지 않는다.

`scripts/` 중 배포와 검색 운영 표의 5개 파일만 OCI CD 대상이다.
데이터셋 도구·보관 코드·이 안내만 바뀌면 OCI 앱을 다시 배포하지 않는다.
