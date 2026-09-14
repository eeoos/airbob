# Global B의 명시적 RDS class 선택 검토

이 변경은 기본 `db.t3.small`을 유지하고, 새 Global B 실행에서만 `db.m6i.large`를 명시적으로 선택하게 한다. 새 클래스도 MySQL 8.4.11, gp3 100GiB, Single-AZ, 암호화, 비공개 DB 한 대라는 기존 조건을 따른다. 실행 중인 RDS의 크기를 변경하거나 부분 SQL import를 재개하는 기능은 없다.

이 파일과 로컬 qualification은 코드 검토 결과다. 새 사양·비용·기간 승인, 실제 IAM 적용, 실행 또는 성능 측정의 증거가 아니다. 기존 $40 승인과 공통 절대 기한 `1789366861`은 코드에서 늘리지 않았다. 다음 공통 기간이나 예산이 필요하면 별도 검토·승인 후 관련 실제 입력을 고정해야 한다.

## 선택과 상속

워크플로의 기존 `b_operation` JSON에 아래 필드만 추가한다. `workflow_dispatch` 입력은 여전히 25개다.

| 최초 작업 | 허용되는 추가 필드 | 조건 |
|---|---|---|
| `prepare`, 소량 | `rdsInstanceClass` | 생략하면 기존 small. 큰 클래스의 소량은 자체 검증 대상이므로 사전 `classRehearsal`을 받지 않는다. |
| `prepare`, 최종 | `rdsInstanceClass`, `classRehearsal` | 큰 클래스는 실제 같은 클래스 소량의 정확한 증거 참조가 필수다. |
| `snapshot-restore` | `rdsInstanceClass`, `classRehearsal` | 기존 정확한 provenance 필드와 함께 사용한다. 큰 클래스는 같은 클래스 소량 증거가 필요하다. |
| retained services/native/snapshot/CDC/ASG | 없음 | 원래 operator의 선택을 상속한다. 새 선택이나 새 소량 증거로 바꿀 수 없다. |
| classic `up`, legacy snapshot promotion | 없음 | 기존 `db.t3.small`/legacy 계약을 유지한다. |

`rdsInstanceClass`의 값은 `db.t3.small` 또는 `db.m6i.large`뿐이다. `classRehearsal`은 `{key,versionId,sha256,bytes}` 네 필드의 실제 S3 참조이며 키는 `data-bootstrap/<actual-small-run>/<actual-small-dataset>-rds-class.json`이다. 이 문서는 실제 VersionId나 성공 실행 ID를 만들어 제공하지 않는다.

`aws-lab.sh`는 최초 operator에 선택과 소량 참조를 저장한다. 후속 작업은 원래 operator, phase3의 클래스, 실제 `DescribeDBInstances` 결과 및 `PendingModifiedValues.DBInstanceClass`를 각각 같은 선택값과 비교한다. 두 클래스 중 하나라는 집합 검사로 대체하지 않는다. 실제 resource fence와 새 controller lease fence도 구분한다. 정리 작업은 원래 값을 상속하며 기존 정리 기한 규칙을 유지한다.

## 별도 소량 증거

`growth_b_rds_class.py`는 봉인된 준비 도구 바깥에 둔 검증기다. 선택한 큰 클래스의 소량에서만 다음 순서로 `SMALL_CLASS_REHEARSAL_VERIFIED`를 발행한다.

1. 원래 context SHA, 실제 RDS 이름/resource ID/생성 시각, 클래스, 태그, 원래 resource fence·expiry 및 100GiB/gp3 상태를 준비 전 읽는다.
2. 기존 봉인 준비를 실행한다. 기존 SQL import, 전체 행/DDL 비교, 소유·인벤토리·111계정 검증은 바꾸지 않는다.
3. 같은 RDS를 준비 후 다시 읽는다. raw standalone 성공 영수증과 기존 준비 wrapper의 UUID·SHA·상태를 비교한다.
4. raw standalone의 실제 S3 VersionId를 얻은 뒤 별도 클래스 증거를 조건부 생성하고, 그 정확한 버전의 전체 바이트를 다시 읽는다. 마지막 기존 wrapper에는 이 증거의 참조만 추가한다.

기본 small의 공개 wrapper에는 새 자격 증거 참조를 추가하지 않는다. 기존 `validate-receipt`와 `validate_rehearsal` 검증을 먼저 통과해야 하며, 큰 클래스의 완료에는 추가 자격 검증이 필요하다.

증거에는 실제 원래 context의 닫힌 projection, 원래 expiry와 wrapper projection, 전후 관측, raw standalone exact ref, 봉인 6개 source SHA 및 기존 raw tool identity를 보존한다. 소비자는 순수 historical validator로 이 실제 값들을 교차 확인한다. 만료된 소량 증거의 문법을 맞추기 위해 새 기한이나 성공 wrapper를 만들지 않는다.

이는 전후 RDS 관측과 복원 성공의 결합이다. 성능이나 전체 시간 동안의 클래스 연속 관측을 주장하지 않으며 `performanceMeasured=false`다. 기존 small 실행은 큰 클래스의 실행 또는 성능 증거가 될 수 없다. 아직 큰 클래스에서 성공한 실제 소량 자격 증거는 없다. 새 검증기 source가 달라지면 그 source에 연결된 새 소량 자격 검토가 필요하다.

최종 dump는 이 자격 증거의 raw SHA가 최종 preparation manifest의 `files.smallRdsReceipt.sha256`와 같아야 한다. 스냅샷 target은 실제 provenance에 기록된 앱·migration 입력과 같은 raw 소량을 기존 `validate_rehearsal`로 확인한다. 두 경로 모두 실제 VersionId/전체 bytes/SHA를 확인하며 누락·외부 run prefix·도구 변경·클래스 변경은 차단한다. 기존 최종 게시 도구나 입력에 과거 소량을 새 클래스로 재표시할 수 없다. 새 소량이 성공한 뒤 실제 raw receipt를 사용하는 최종 marker와 별도 class ref를 준비해야 한다.

## Terraform와 IAM 경계

`lab/rds.tf`와 RDS module은 최초 선택을 실제 `instance_class`로 전달한다. `BDatabaseClass`는 큰 DB instance에만 생성하며 parameter/subnet 그룹에서는 제거한다. 기본 DB와 legacy의 태그 집합은 유지한다. 기존 ephemeral RunId/fence/expiry 태그를 줄이지 않는다.

Terraform apply 직전의 클래스 검증은 생성의 선택값과 기존 resource의 before/after 값을 비교한다. 외부에서 클래스를 변경한 뒤 Terraform으로 원래 크기로 되돌리는 계획도 자동 허용하지 않는다. 클래스가 같은 일반 parameter 변경은 허용한다.

스냅샷의 기존 `configured_storage_gib=null` 의미는 유지하고 별도 `allocated_storage_gib`를 실제 provider 출력으로 전달한다. 최초 생성에서 storage/type이 unknown인 경우는 검증한 동일 B 스냅샷의 실제 available provenance가 100GiB/gp3/8.4.11/암호화임을 증명한 경우만 허용한다. 생성 후 실제 API의 100GiB/gp3/선택 클래스 검사는 생략하지 않는다.

Foundation은 기존 exact ARN/tag 조건을 유지하면서 큰 클래스의 Create를 별도 허용한다. 큰 클래스 Restore는 승인 snapshot ID가 B namespace일 때만 허용하며 기존 v4 등 legacy restore 승인은 큰 클래스 권한으로 바뀌지 않는다. 새 B snapshot 생성 승인은 복원 승인과 별개인 기존 exact creation ID를 계속 사용한다.

DB class 태그는 원래 값으로만 다시 적용할 수 있고 삭제 권한은 추가하지 않는다. Modify의 클래스 조건이 존재하면 태그의 최초 선택과 비교한다. 클래스 조건이 없는 일반 Modify는 해당 deny에 걸리지 않는다. 공식 문서만으로 `rds:DatabaseClass`가 모든 Modify에서 반드시 요청한 새 클래스를 의미한다고 보장할 수 없으므로, IAM만으로 변경 불가를 증명했다고 표현하지 않는다. operator·현재 API·pending 값·Terraform before/after 검사가 함께 필요하다.

공식 근거: [RDS IAM actions/condition keys](https://docs.aws.amazon.com/service-authorization/latest/reference/list_rds.html), [Create/Modify policy examples](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/security_iam_id-based-policy-examples-create-and-modify-examples.html). 이 작업은 네트워크 호출 없이 Root가 확인한 근거와 로컬 소스를 사용했다.

managed policy 크기는 생성된 JSON으로 검사했다. 변경 없는 host boundary는 6006B, data policy는 5724B, rds provision은 legacy 승인에서 4964B이며 테스트한 긴 B 승인 ID에서 6045B다. 모두 6144B 이하이다. 같은 조건의 parameter/subnet 생성과 정확한 option/parameter/subnet 사용 ARN만 합쳤으며 허용 resource/action 범위를 넓히지 않았다.

Root가 frozen Foundation copy에서 수행한 mocked Terraform 42개는 통과했다. Root의 실제 읽기 전용 plan은 IAM 두 정책 외에 실패한 fence74 lab VPC의 기존 private-zone association drift도 포함했다. 이 작업은 DNS 소스를 수정하지 않았고 plan을 적용하지 않았다. 실제 실패 lab 정리 뒤 fresh full plan이 필요하다.

## 검토 및 실행에 남은 조건

- 봉인 준비 6개, SQL/ETL/앱, source·snapshot native 엔진과 snapshot core는 바이트를 유지했다. service 9개 payload도 바꾸지 않았다. 별도 class guard는 준비/서비스 outer context의 source SHA로 고정한다.
- 새 snapshot host 도구 archive는 9개, native host source는 18개이며 controller를 포함한 native archive는 19개다. 새 operation은 실제 새 archive SHA를 사용해야 한다. 이전 operation/source archive를 새 내용으로 재표시할 수 없다.
- 모든 성공 형태의 로컬 시험 데이터는 합성 fixture이다. 실제 작은/큰 RDS 생성, 원격 요청, SQL, publication, 성능 측정, Git 변경 또는 배포는 수행하지 않았다.
- revision2 전체 Python과 이전 operator shell은 Root 검증을 통과했다. 아래 R5 의존성 보완을 포함한 revision3의 독립 affected 검증과 CI, 새 승인 범위에 맞춘 실제 Terraform 계획은 남는다. 큰 클래스의 실제 소량 성공, 원문 영수증 보존, 용량/시간 측정과 최종 입력 재검토도 선행해야 한다.
- 이 변경으로 고정 14,400초 SQL timeout이나 18,000초 command/기존 6h lease를 늘리지 않았다. 큰 클래스가 시간 내 성공할지는 실제 소량·최종 실행 전에는 알 수 없다. 기존 부분 import를 `resume-preparation` 성공 조건으로 바꿀 수 없다.

## Root 전체 QA 이후의 연결 보완

이전 29파일 동결본의 qualification SHA는 `8c59f20c11050c1384a0700909b072d374c56eb63121236f35759148667b9378`이며 Root가 그 바이트를 별도 보존했다. 그 버전에서 focused Python 190개는 통과했지만 Root의 전체 Python 1,067개 실행에서는 55개 실패와 8개 skip이 나왔다. 전체 operator shell은 같은 버전에서 통과했다.

그중 53개는 CDC/target-service shell harness가 새 class 함수들을 추출하지 않아 실제 검증 전에 종료됐고, 하나는 bytecode 시험의 고정 호출 수가 늘어난 Python 실행 수와 달랐다. 남은 하나는 호출자의 umask 때문에 shared-directory 거절 fixture가 의도한 0755가 되지 않은 문제였으며 Root가 명시적 chmod로 수정했다. 클래스 검증기를 no-op으로 대체하지 않았다. 갱신한 harness는 실제 `selected`/`live` CLI와 정확한 RDS API fixture를 사용하고, 큰 클래스의 정상 전달과 현재·pending 클래스 drift 거부를 검사한다. 기존 snapshot 완료 fixture의 실제 public 바이트와 completion validator도 유지한다.

추가로 실제 snapshot outer shell은 도구 파일 수를 여전히 8개로 검사하고 있었다. 두 count literal만 9로 고쳤다. 생성한 실제 9파일 archive를 shell의 추출·count·SHA·host CLI admission에 통과시키며, 누락8개·추가10개·동수 foreign 교체·파일 바이트 변조를 거부하는 회귀를 추가했다. 기존 8개 도구와 새 class guard의 바이트는 바꾸지 않았다. Bytecode 시험은 준비·서비스·snapshot의 총 10개 interpreter 진입점과 각 자식 Python까지 검사한다.

revision2 qualification은 이 테스트 수정과 Root의 local-service fixture 수정을 모두 포함한다. Root의 revision2 전체 Python 결과는 1,087 PASS / 8 SKIP이며 원본 receipt SHA는 `080351ed63970cd4f3abca3f72bb9cee24f6dd21a70e325a2be5d2cca4129759`다. 이 결과는 당시 35파일 버전의 이력으로 보존한다.

로컬 검증 명령과 exact source SHA 목록은 같은 디렉터리의 `global-b-rds-class-qualification.json`에 기록한다. 이것은 클라우드 완료 영수증이 아니다.

## Revision3: R5 실제 source archive 의존성

새 public package를 깨끗한 interpreter에서 실제로 import하면서 R5 archive 25개에 `growth_b_rds_class.py`가 누락된 것을 재현했다. snapshot host/controller가 이 helper를 import하므로 원래 package는 격리된 호스트에서 실행할 수 없었다. 수정은 `growth_b_snapshot_service_verify.source_files()`의 정확한 의존 목록에 해당 파일 하나를 추가하는 한 줄이며, 기존 snapshot/core/봉인 6개/엔진은 그대로다.

현재 변경 목록은 revision2의 35파일에 R5 verifier와 그 전용 테스트를 더한 **37파일**이다. 이전 qualification `f8d1e98cf690999b0a54ca9a5dbfbe60705570652f178b2deb40dccdfd1e25c9`와 35-file map은 새 R5 artifact의 `.private/revision-2/`에 보존했다. revision3의 실제 전용 R5 테스트는 **25 PASS**이며, 실제 26개 archive의 격리 import/rebuild 및 누락·변조 helper 거부를 포함한다. 추가 패키지 경계 테스트 **19 PASS**는 실제 production builder와 local filesystem consumer를 검증했다. cloud/DMI/owner admission만 fixture로 대체하며 실제 클라우드 완료를 주장하지 않는다.

| 새 로컬 package | 파일 수 | gzip bytes | source bytes | SHA256 |
|---|---:|---:|---:|---|
| native controller (host18 + controller1) | 19 | 172344 | 666724 | `4ba3691c6fc0c6e1120c158f4c1208508418417166ecd433a7cfb0ace2b5c067` |
| source R4 | 22 | 212632 | 806144 | `d3b8995556be35446288146da63733c74d1bea2799c60a5900e3bd7ef6e2b776` |
| snapshot-target R5 | 26 | 257546 | 994022 | `ac323c10c165ecb85e12b64bb57e1ab250372cca6fadd4dc527791c9f75fcd50` |

R4 archive는 이전 22파일 공개 바이트와 동일함을 현재 source에서 다시 확인했다. Native/R5는 새 바이트이며 이전 archive나 operation SHA를 재사용할 수 없다. R5 package에는 snapshot host의 실제 9개 의존성이 모두 포함된다. 세 archive는 전체 member SHA/size, 고정 metadata, 압축 해제 상한, source-only allowlist, 격리 import/rebuild와 실제 소비 파일 경계를 통과했다. 게시·원격 staging·dispatch는 수행하지 않았고 S3 VersionId나 실행 성공 receipt도 만들지 않았다.

현재 revision3의 전체 repository QA/CI 성공은 아직 주장하지 않는다. Root가 새 37파일 동결본으로 affected 검증과 CI를 이어간다. 기존 $40/공통 기한, 실제 same-class small 및 새 final 입력/권한 계획의 선행 gate는 그대로다.
