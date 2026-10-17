# OCI 배포 런북

예약 날짜 재고의 공통 hard-cutover 계약은
[reservation-inventory-cutover.md](reservation-inventory-cutover.md)를 먼저 따른다.

## CI와 CD의 역할

PR은 `ci.yml`에서 변경 범위에 맞는 앱·인프라·데이터 검사를 병렬로 실행한다.
`CI result`는 항상 실행하며, 선택한 검사가 실패·취소·누락되면 실패한다.
문서와 보관한 측정 코드만 바뀌면 무거운 검사는 생략하고 결과 검사만 통과한다.
필수 통과 검사 이름은 `CI result`다. 같은 PR의 이전 실행은 새 실행이 시작되면 취소한다.

`main`의 CD도 같은 CI 정의를 호출한다. 해당 커밋의 검사가 모두 통과한 뒤,
그 실행에서 테스트와 함께 만든 `app-jar-<전체 SHA>`를 받아 GHCR 이미지를 한 번 만든다.
이미지 태그는 전체 커밋 SHA이며, 서버에는 빌드 결과의 digest를 전달한다.
CD에서 테스트를 생략한 JAR를 다시 만들거나 PR 실행의 산출물을 가져오지 않는다.

| 변경 | 선택하는 검사 | OCI 배포 |
|---|---|---|
| 앱 Java 코드 | 앱 | 실행 |
| 앱 테스트만 | 앱 | 생략 |
| DB migration·앱 설정·공유 빌드·Docker 설정 | 관련 앱·인프라·데이터 검사 | 운영 입력이 바뀌면 실행 |
| AWS 인프라·도구 | 인프라·데이터 | 앱 이미지 정의 등 공유 운영 입력만 실행 |
| B 복원 Python 도구 | 데이터 | 생략 |
| 문서·보관한 load-test 코드 | 결과 검사 | 생략 |

경로 선택은 [scope.py](../../scripts/ci/scope.py)에 모았다. Git 전체 diff를 사용하므로
파일 삭제와 300개가 넘는 변경도 포함한다. CI 설정 변경은 모든 검사 그룹을 실행한다.
정의가 없는 새 코드 경로도 모든 검사를 실행한다.

AWS 앱 이미지가 새로 필요하면 `Publish AWS application image`를 수동 실행한다.
검토한 `main` SHA를 입력하고 같은 CI를 통과한 뒤 ECR에 게시하며 OCI 배포는 하지 않는다.
고정된 과거 B 이미지 게시와 AWS 자원 기동·종료 워크플로는 별도로 유지한다.
OCI에서 사용하지 않던 GHCR 인프라 이미지 게시 작업은 제거했고, Elasticsearch·Debezium은
아래와 같이 서버에서 검토한 소스로 빌드한다.

## 배포 전제

main CD의 self-hosted runner에는 다음 항목이 준비되어 있어야 한다.

- Docker Engine과 Docker Compose v2 (up --wait, --wait-timeout 지원)
- Docker Buildx (Elasticsearch와 Debezium 이미지 빌드)
- rsync
- GHCR pull 권한
- $HOME/airbob/.env.oci
- 80/443 포트와 Docker named volume을 사용할 수 있는 권한

.env.oci는 저장소에서 배포하지 않는다. 최초 배포 전에 서버에서 만들고 권한을 제한한다.
DB, provider, Slack webhook 값을 command line이나 티켓에 복사하지 않는다.

OCI는 한 인스턴스의 Compose stack으로 실행한다. MySQL 8.4.11, Elasticsearch 8.18.8,
Debezium 3.0.8.Final을 로컬 개발 환경과 맞추고 Kafka 3.7.0과 Redis 7.2를 사용한다.
Elasticsearch와 Debezium은 동기화한 저장소의 Dockerfile로 OCI에서 빌드한다.
Dockerfile의 base image digest와 Debezium archive checksum을 사용하며,
서버에 남아 있는 GHCR `latest` 이미지는 사용하지 않는다.

MySQL 8.4는 `airbob_mysql84-data` 볼륨을 사용한다. 기존 `airbob_mysql-data`의 8.0
데이터 디렉터리는 자동으로 연결하지 않는다. 8.4 볼륨이 없으면 새 DB를 초기화한다.
서버 기본 시간대와 JDBC 시간대는 UTC다. MySQL health는 TCP 연결과 인증된 `SELECT 1`,
Elasticsearch health는 single-node에서 허용되는 yellow 이상 상태로 확인한다.

## 최초 인증서 발급

DNS가 OCI 서버를 가리키고 80번 포트를 사용할 수 있는 상태에서 실행한다.
Compose 프로젝트 이름은 `airbob`이며 인증서는 기존 `airbob_certbot-certs`,
인증 확인 파일은 `airbob_certbot-www` 볼륨을 사용한다.

```sh
bash scripts/init-ssl.sh admin@airbob.cloud
```

앱 연결이 없는 임시 Nginx에서 인증 확인 요청만 처리한다. 운영
`docker/nginx/conf.d/default.conf`는 수정하지 않으며, 성공·실패·중단 시 이번 실행에서
생성한 컨테이너를 정리한다. 이미 실행 중인 Nginx를 자동 중지하지 않는다.
인증서 발급 후에는 아래 정상 배포 절차로 앱을 기동한다.

## Reviewed asset 동기화

CD는 deploy job에서도 commit을 checkout한 다음
[sync-oci-deployment-assets.sh](../../scripts/sync-oci-deployment-assets.sh)를 실행한다. 다음
경로만 $HOME/airbob의 managed deployment asset으로 동기화한다.

- docker-compose.oci.yml
- src/main/resources/db/migration/
- docker/debezium/, docker/elasticsearch/, docker/kafka/, docker/mysql/init/
- docker/logstash/, docker/nginx/, monitoring/
- scripts/ 안의 deploy-oci.sh, sync-oci-deployment-assets.sh,
  preflight-reservation-inventory-cutover.sh, reindex-accommodations.sh, init-ssl.sh

managed directory 안에서 저장소에서 삭제된 파일은 서버에서도 삭제한다. 반면 서버가
소유하는 root secret, application log, Docker named volume은 동기화 대상이 아니므로
삭제하지 않는다. 배포 디렉터리 전체에 rsync --delete를 사용하지 않는다.

배포 디렉터리의 `scripts/`는 위 5개 파일로 맞춘다. 이전에 복사된 CodeDeploy·데이터셋·
보관용 도구도 이 디렉터리에서는 제거되므로, 개인 도구는 managed directory 밖에 둔다.
데이터셋 도구는 필요한 모듈이 포함된 별도 저장소에서 사용한다.
CD의 변경 감지도 같은 5개 스크립트만 포함하며, 데이터셋 도구나 안내 문서 변경은
OCI 앱 빌드·배포를 유발하지 않는다. 전체 역할은 [실행 도구 안내](../../scripts/README.md)에 있다.

## 정상 배포 순서

[deploy-oci.sh](../../scripts/deploy-oci.sh)는 항상 .env.oci와
docker-compose.oci.yml을 명시해서 다음 순서로 실행한다.

1. docker compose config --quiet
2. 검증 후 게시한 app image digest를 `APP_IMAGE`로 지정하고, 로컬에 없으면 GHCR에서 pull
3. Elasticsearch와 Debezium 이미지를 `compose build --pull`로 빌드
4. 기존 Nginx와 app을 중지해 신규 요청과 기존 outbox producer 차단
5. MySQL, Redis, Elasticsearch, Kafka 기동 및 health 대기
6. V25 예약 재고 컷오버 preflight one-shot 실행
7. kafka-topic-init one-shot 실행
8. 기존 Debezium과 connector monitor 중지
9. 애플리케이션과 동일한 Flyway 11.7.2로 V1~현재 migration 적용
10. Debezium 기동 및 health 대기
11. debezium-connector-init one-shot 실행
12. debezium-connector-monitor 기동 및 health 대기
13. app 교체와 health 대기
14. Nginx 교체, config 검증, Docker network 내부 app health 검증

6번, 7번, 9번, 11번은 app의 depends_on에만 맡기지 않고 CD가 명시적으로 실행한다. 따라서
기존 producer를 닫은 뒤 schema를 먼저 적용하고, 새 outbox column 계약을 이해하는
connector가 RUNNING이 된 뒤에만 Kafka listener를 시작한다. 기존 airbob-app이나 nginx
container가 없는 첫 배포도 같은 순서를 사용한다.

V25는 기존 예약 행을 날짜 재고로 추측해 변환하지 않는 의도적인 hard cutover다. 최초
cutover에서만 preflight는 이전 app/Nginx를 중지한 뒤 `reservation`이 0건인지 확인한다.
V26 `accommodation_inventory_day`가 이미 존재하는 후속 배포는 정상 예약 데이터를 허용하고
zero-data 검사를 건너뛴다. 모든 배포에서 `PUBLISHED` 숙소에는 `CET` 같은
single-segment 식별자도 포함하는 최소한 형식상 유효한 `time_zone_id`가 있어야 한다.
IANA ZoneId의 완전한 유효성은 새 애플리케이션의
기동 bootstrap이 다시 검증하고, 실패하면 health가 열리지 않는다. preflight 실패를 Flyway로
넘기지 않아 V25 guard 실패를 `repair`해야 하는 정상 운영 시나리오를 만들지 않는다.

운영 `aws`/`oci` profile은 inventory startup과 rolling seed를 둘 다 필수로 검증한다.
현재 운영 프로필에서는 lifecycle과 readiness 검증을 수행한다. 실험 프로필은 제거됐으며,
테스트의 격리 설정을 운영 배포에 적용하지 않는다.
OCI app이 실행 중인 채 bootstrap으로 health가 `unhealthy`여도 배포 스크립트는 전체
readiness 기한을 기다리며, container가 `exited` 또는 `dead`일 때만 즉시 실패한다.

최초 cutover 검사는 DB만으로 이전 writer를 fence한다고 주장하지 않는다. V26 table이 아직
없다면 배포 전에 이 Compose stack 밖의 V24 이하 app, batch, 관리 SQL writer를 모두 중지하고
DB writer 자격 증명 사용을 통제해야 한다. preflight를 통과한 뒤 current app이 healthy가 될
때까지 그런 writer를 다시 시작하면 안 된다. 최초 cutover 전에 데이터가 있다면 migration을
강행하지 말고 별도 검증된 backfill 계획을 먼저 수립한다.

수동으로 같은 reviewed script를 실행할 때도 게시 결과의 digest를 넘긴다.

    DEPLOY_DIR="$HOME/airbob" IMAGE_DIGEST="sha256:<게시된 이미지의 64자리 해시>" \
      sh "$HOME/airbob/scripts/deploy-oci.sh"

기존 수동 운영을 위한 `IMAGE_TAG`도 지원하지만 자동 CD는 digest를 사용한다.
두 값을 함께 주면 digest를 우선한다.

### V28 견적 만료 제거 배포

V28은 `reservation_quote.expires_at`과 해당 CHECK만 제거한다. 기존 견적 가격·회원·예약 연결과
`created_at` 기반 보존 정리는 유지한다. 기존 migration을 수정하거나 견적을 일괄 삭제하지 않는다.

`quote_expires_at` 없는 응답과 기존 응답을 모두 읽는 프런트엔드를 먼저 배포한다. 이후 이전
백엔드와 별도 quote reader/writer를 중지하고 V28 migration과 새 백엔드를 적용한다. 이미 열린
구버전 브라우저는 새 응답을 처리하지 못할 수 있으므로 새 화면을 로드해야 한다. 새 프런트엔드는
기존 결제 복구 기록의 예약·결제 식별자를 보존한다. V28 적용 후에는 제거된 컬럼을 요구하는
구버전 백엔드로 돌아가지 말고 V28 호환 바이너리로 roll-forward한다.

## Migration 경계와 실패 처리

배포 실패는 public admission을 닫기 전후가 다르다.

| 실패 시점 | 자동 동작 | 운영 조치 |
| --- | --- | --- |
| config 검증, app image pull, 인프라 image build | script가 실패하고 기존 app/Nginx는 건드리지 않는다 | 원인을 고친 뒤 같은 SHA 또는 수정 SHA로 재실행한다 |
| 인프라 기동과 Kafka bootstrap 실패 | app과 Nginx를 중지하지만 Flyway는 실행하지 않는다 | 이미지 버전, 연결, health를 확인하고 수정 후 재실행한다 |
| V25 preflight 실패 | app과 Nginx를 중지하지만 Flyway는 실행하지 않는다 | 남은 writer와 데이터·시간대를 확인한다. 자동 V24 재시작은 하지 않으며 명시적 운영 판단 뒤 재개한다 |
| Flyway 시작 이후 | app과 Nginx를 중지한 채 실패한다 | migration/connector 상태를 확인하고 V25 cutover·V26 inventory·V27 index·V28 quote를 이해하는 current binary로 roll-forward한다 |

Nginx와 기존 app을 중지한 뒤 별도 Flyway container가 migration을 적용한다. Flyway가
성공하기 전에는 새 EventRouter connector를 등록하지 않는다. 이 경계를 넘은 뒤 CD는
이전 image ID를 찾거나 pre-V25 binary로 자동 rollback하지 않는다.
다음 순서를 사용한다.

1. nginx가 중지됐는지 확인하고 별도 ingress가 있다면 함께 차단한다.
2. DB schema version과 app health 실패 유형을 확인한다. secret, provider body,
   connector raw trace는 일반 로그나 티켓에 붙이지 않는다.
3. connector monitor와 Kafka topic은 유지한다.
4. V25 cutover와 V26 inventory를 이해하고 현재 V28 schema에 맞는 수정 image를 build/push한다.
5. CD 또는 deploy-oci.sh로 roll-forward하고 app health가 통과한 뒤에만 Nginx를 연다.
6. QUEUED, EXECUTING, WAITING_RETRY, MANUAL_REVIEW 상태를 결제 런북으로 점검한다.

V25 이후 schema와 날짜 재고, outbox row, Kafka topic, Connect offset/schema history를 이전 binary에
맞추려고 삭제하지 않는다.

## Connector 지속 감시

debezium-connector-init의 성공은 배포 순간의 gate일 뿐이다.
debezium-connector-monitor는 이후에도 30초마다
/connectors/airbob-outbox-connector/status를 직접 조회한다.

- 연결 제한 시간 5초, 전체 요청 제한 시간 10초
- connector가 RUNNING이고 task가 한 개 이상이며 모두 RUNNING일 때만 healthy
- 상태 파일에는 RUNNING 또는 NOT_RUNNING만 기록
- 같은 상태에서는 로그를 반복하지 않고 상태 전환 때만 고정 문구 기록
- Connect 응답 body, task trace, webhook을 출력하지 않음
- monitor process 자체가 종료되면 restart: unless-stopped로 재시작

상태는 payload를 조회하지 않고 다음처럼 확인한다.

    docker inspect \
      --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      airbob-debezium-connector-monitor

    docker logs --since 10m airbob-debezium-connector-monitor

unhealthy이면 즉시 긴급 대응한다.

1. outbox cleanup을 끄고 retention을 줄이지 않는다.
2. monitor의 마지막 상태 전환 시각과 Debezium container health를 확인한다.
3. 권한이 제한된 터미널에서 Connect status를 조회하되 connector/task의 id와 state만
   추출하고 raw trace를 저장하거나 전송하지 않는다.
4. heartbeat, Connect offset, 대상 topic ingress, consumer lag 순으로 확인한다.
5. 수정 후 connector init을 다시 실행하고 monitor가 healthy로 복구되는지 확인한다.

Docker의 unhealthy 신호를 실제 on-call paging으로 전달하는 host-level alert rule은 운영
환경에서 별도로 연결해야 한다. 이 외부 경보도 상태 전환에만 발송하고 bounded HTTP
timeout을 사용해야 하며, connector raw 응답이나 Slack webhook을 메시지에 포함하면 안
된다. Debezium outbox를 경보 전송 경로로 사용하지 않는다.
