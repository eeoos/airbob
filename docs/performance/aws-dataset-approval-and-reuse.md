# 데이터 승인과 반복 복원

이 문서는 새 AWS lab 흐름을 설명한다. 실제 실행 전 새 ETL 명세와 소비자 어댑터 연결이 필요하다. 기존 실패 실행의 기록은 이 흐름의 승인 근거로 사용하지 않는다.

## 최초 릴리스

1. 빈 MySQL 8.4에 마이그레이션을 적용하고 ETL을 실행한다. 작은 샘플로 호환성을 확인한 뒤 최종 데이터를 생성한다.
2. 검증한 dump와 검색 스냅샷을 불변 릴리스로 게시한다.
3. 기존 `aws-up`과 같은 릴리스·이미지·네트워크 입력으로 `make aws-prepare`를 실행한다. `DATABASE_BOOTSTRAP=dump`, `MODE=performance`, `DNS_MODE=direct-only`, `LOAD_GENERATOR_ENABLED=false`를 사용한다.
4. DB·검색 데이터의 전체 검증이 끝나면 `data-bootstrap/<run-id>/dataset-qualification.json`의 S3 VersionId와 원본 RDS 식별자가 출력된다. ALB·앱 ASG는 생성하지 않는다. 아직 DB·서비스 호스트 비용은 발생하므로 TTL 전에 승격과 정리를 끝낸다.
5. 게시자/관리자 자격으로 정확한 S3 버전의 승인용 기록을 내려받아 아래 명령을 실행한다. 스냅샷이 available인 것과 원본 RDS·기록 연결을 확인한 후에만 승격 결과 파일이 생성된다.

```bash
AIRBOB_REGION=ap-northeast-2 \
  infra/aws/scripts/promote-rds-snapshot.sh \
  /secure/release/manifest.json \
  /secure/dataset-qualification.json "$QUALIFICATION_VERSION_ID" \
  "airbob-$RUN_ID" "airbob-dataset-$DATASET_RELEASE" \
  /secure/snapshot-promotion.json
```

6. 검증한 스냅샷 ID를 foundation의 `approved_rds_snapshot_identifier`에 등록한다. 승격 명령 자체는 이 허용 목록을 변경하지 않는다.
7. 준비 실행을 `make aws-down RUN_ID=<run-id>`로 삭제한다. 영구 스냅샷과 승인 기록은 실행별 정리 대상에서 제외된다.

준비 전용 DB는 승격까지 앱·ETL·수동 SQL의 쓰기에 사용하지 않는다. 변경했으면 새 릴리스로 다시 검증한다. DB와 검색 검증을 마치기 전에 실패한 실행은 승인되지 않으며, 기본 실패 정리와 TTL은 유지된다. 일부 검증만 성공한 상태를 전체 승인으로 취급하지 않는다.

## 반복 실험

새 RunId로 `aws-up`을 실행하면서 `DATABASE_BOOTSTRAP=snapshot`, 승인된 `RDS_SNAPSHOT_IDENTIFIER`, 최초 `RDS_SNAPSHOT_SOURCE_RUN_ID`, `RDS_SNAPSHOT_SOURCE_RESOURCE_ID`, 정확한 `RDS_ENGINE_VERSION`을 지정한다.

스냅샷과 원본 S3 기록의 버전·내용 해시를 맞춘 뒤 복원한다. 원본 객체가 교체됐거나 엔진·스키마·데이터 릴리스가 다르면 중단한다. 기록의 전체 데이터 지문은 최초 승인에서 승계하며, 이번 실행에서 계산한 값이라고 표시하지 않는다.

DB 연결·Flyway·스키마·소수 실험 대상, ES 매핑·문서 수·alias, Redis·Kafka·CDC·앱 상태는 매번 확인한다. 전체 COUNT·행 해시·관계 집계·ES scroll은 반복하지 않는다. 스냅샷 복원 직후에는 별도 워밍업과 A/A 확인을 한다. 복원 완료가 즉시 균일한 디스크 읽기 성능을 뜻하지는 않는다.

앱 이미지·캐시·ASG 수는 같은 승인 데이터를 사용하는 실험 변수다. 실행별 증거에 남기되 데이터 재사용 조건으로 비교하지 않는다. 마이그레이션·기준 데이터 변경은 새 호환성 확인이 필요하다.

쿠폰 발급, 리뷰 동시 수정, 예약 만료·삭제 등은 실행별 초기 상태를 준비하고 변경된 범위의 정확성을 검증한다. 다음 비교에 오염된 상태를 넘기지 않는다. 동일 릴리스 재사용은 실험 결과 검증을 생략한다는 뜻이 아니다.

## 진단

SSM 출력의 `bootstrap stage=... status=... elapsedSeconds=...`로 데이터 내려받기, DB 복원·검증, 검색 복원·검증, 실험 상태, Kafka/CDC, 완료 기록 게시의 실패 위치와 시간을 확인한다. 자격 증명·SQL 데이터는 단계 로그에 넣지 않는다.

`KEEP_ON_FAILURE=false`가 기본이며, 성공한 준비 실행도 TTL 만료 시 정리된다. 승격 전에 준비 DB를 삭제하면 그 DB에서 스냅샷을 만들 수 없다. 동일 스냅샷 ID에 대한 승격 재시도는 기존 후보의 출처·태그를 다시 확인하며 중복 스냅샷을 생성하지 않는다. 로컬 결과 파일은 덮어쓰지 않으므로 재시도 시 새 출력 경로를 사용한다.
