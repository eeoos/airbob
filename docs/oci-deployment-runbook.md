# OCI 배포 런북

예약 날짜 재고의 공통 hard-cutover 계약은
[reservation-inventory-cutover.md](reservation-inventory-cutover.md)를 먼저 따른다.

## 배포 전제

main CD의 self-hosted runner에는 다음 항목이 준비되어 있어야 한다.

- Docker Engine과 Docker Compose v2 (up --wait, --wait-timeout 지원)
- rsync
- GHCR pull 권한
- $HOME/airbob/.env.oci
- 80/443 포트와 Docker named volume을 사용할 수 있는 권한

.env.oci는 저장소에서 배포하지 않는다. 최초 배포 전에 서버에서 만들고 권한을 제한한다.
DB, provider, Slack webhook 값을 command line이나 티켓에 복사하지 않는다.

## Reviewed asset 동기화

CD는 deploy job에서도 commit을 checkout한 다음
[sync-oci-deployment-assets.sh](../scripts/sync-oci-deployment-assets.sh)를 실행한다. 다음
경로만 $HOME/airbob의 managed deployment asset으로 동기화한다.

- docker-compose.oci.yml
- debezium-config/
- src/main/resources/db/migration/
- docker/debezium/, docker/kafka/, docker/mysql/init/
- logstash/, monitoring/, nginx/, scripts/

managed directory 안에서 저장소에서 삭제된 파일은 서버에서도 삭제한다. 반면 서버가
소유하는 root secret, application log, Docker named volume은 동기화 대상이 아니므로
삭제하지 않는다. 배포 디렉터리 전체에 rsync --delete를 사용하지 않는다.

## 정상 배포 순서

[deploy-oci.sh](../scripts/deploy-oci.sh)는 항상 .env.oci와
docker-compose.oci.yml을 명시해서 다음 순서로 실행한다.

1. docker compose config --quiet
2. immutable SHA image pull과 local latest tag 갱신
3. 기존 Nginx와 app을 중지해 신규 요청과 기존 outbox producer 차단
4. MySQL, Redis, Elasticsearch, Kafka 기동 및 health 대기
5. V25 예약 재고 컷오버 preflight one-shot 실행
6. kafka-topic-init one-shot 실행
7. 기존 Debezium과 connector monitor 중지
8. 애플리케이션과 동일한 Flyway 11.7.2로 V1~현재 migration 적용
9. Debezium 기동 및 health 대기
10. debezium-connector-init one-shot 실행
11. debezium-connector-monitor 기동 및 health 대기
12. app 교체와 health 대기
13. Nginx 교체, config 검증, Docker network 내부 app health 검증

5번, 6번, 8번, 10번은 app의 depends_on에만 맡기지 않고 CD가 명시적으로 실행한다. 따라서
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
`test`와 읽기 전용 `performance-lab`만 lifecycle 비활성화와 readiness 우회를 허용한다.
OCI app이 실행 중인 채 bootstrap으로 health가 `unhealthy`여도 배포 스크립트는 전체
readiness 기한을 기다리며, container가 `exited` 또는 `dead`일 때만 즉시 실패한다.

최초 cutover 검사는 DB만으로 이전 writer를 fence한다고 주장하지 않는다. V26 table이 아직
없다면 배포 전에 이 Compose stack 밖의 V24 이하 app, batch, 관리 SQL writer를 모두 중지하고
DB writer 자격 증명 사용을 통제해야 한다. preflight를 통과한 뒤 current app이 healthy가 될
때까지 그런 writer를 다시 시작하면 안 된다. 최초 cutover 전에 데이터가 있다면 migration을
강행하지 말고 별도 검증된 backfill 계획을 먼저 수립한다.

수동으로 같은 reviewed script를 실행할 때는 immutable image tag를 넘긴다.

    DEPLOY_DIR="$HOME/airbob" IMAGE_TAG="<git-sha-7>" \
      sh "$HOME/airbob/scripts/deploy-oci.sh"

## Migration 경계와 실패 처리

배포 실패는 public admission을 닫기 전후가 다르다.

| 실패 시점 | 자동 동작 | 운영 조치 |
| --- | --- | --- |
| config 검증·image pull | script가 실패하고 기존 app/Nginx는 건드리지 않는다 | 원인을 고친 뒤 같은 SHA 또는 수정 SHA로 재실행한다 |
| V25 preflight 실패 | app과 Nginx를 중지하지만 Flyway는 실행하지 않는다 | 남은 writer와 데이터·시간대를 확인한다. 자동 V24 재시작은 하지 않으며 명시적 운영 판단 뒤 재개한다 |
| Flyway 시작 이후 | app과 Nginx를 중지한 채 실패한다 | migration/connector 상태를 확인하고 V25 cutover·V26 inventory·현재 V27 index를 이해하는 current binary로 roll-forward한다 |

Nginx와 기존 app을 중지한 뒤 별도 Flyway container가 migration을 적용한다. Flyway가
성공하기 전에는 새 EventRouter connector를 등록하지 않는다. 이 경계를 넘은 뒤 CD는
이전 image ID를 찾거나 pre-V25 binary로 자동 rollback하지 않는다.
다음 순서를 사용한다.

1. nginx가 중지됐는지 확인하고 별도 ingress가 있다면 함께 차단한다.
2. DB schema version과 app health 실패 유형을 확인한다. secret, provider body,
   connector raw trace는 일반 로그나 티켓에 붙이지 않는다.
3. connector monitor와 Kafka topic은 유지한다.
4. V25 cutover와 V26 inventory를 이해하고 현재 V27 schema에 맞는 수정 image를 build/push한다.
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
