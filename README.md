<h1 align="center">$\bf{\large{\color{#6580DD} Codesquad \ - \ Airbob \ Backend \ Server}}$</h1>
<br>
<p align="center">
  🌐 <a href="https://www.airbob.cloud/" target="_blank"><b>https://airbob.cloud</b></a>
</p>
<br>

### TEST 계정<br>
ID: test@test.com <br>
PW: 123123123

### 주요 도시 숙소 목록 링크
- [로마](https://www.airbob.cloud/search?destination=%EC%9D%B4%ED%83%88%EB%A6%AC%EC%95%84+%EB%A1%9C%EB%A7%88&lat=41.8967068&lng=12.4822025&topLeftLat=42.05054624539585&topLeftLng=12.34170704408109&bottomRightLat=41.76959604595655&bottomRightLng=12.73028878823088&adultOccupancy=1&childOccupancy=0&infantOccupancy=0&petOccupancy=0)
- [하와이](https://www.airbob.cloud/search?adultOccupancy=1&childOccupancy=0&infantOccupancy=0&petOccupancy=0&destination=%EB%AF%B8%EA%B5%AD+%ED%95%98%EC%99%80%EC%9D%B4&lat=19.8986819&lng=-155.6658568&topLeftLat=22.37&topLeftLng=-160.53&bottomRightLat=18.55&bottomRightLng=-154.48) <br>
- [그리스](https://www.airbob.cloud/search?adultOccupancy=1&childOccupancy=0&infantOccupancy=0&petOccupancy=0&destination=Crete%2C+%EA%B7%B8%EB%A6%AC%EC%8A%A4&lat=35.240117&lng=24.8092691&topLeftLat=35.69569454391381&topLeftLng=23.51463927121635&bottomRightLat=34.92132439815794&bottomRightLng=26.31895032591595) <br>
- [싱가폴](https://www.airbob.cloud/search?adultOccupancy=1&childOccupancy=0&infantOccupancy=0&petOccupancy=0&destination=%EC%8B%B1%EA%B0%80%ED%8F%B4&lat=1.352083&lng=103.819836&topLeftLat=1.478400052327221&topLeftLng=103.5940000228498&bottomRightLat=1.149599959992529&bottomRightLng=104.0945000859547) <br>
- [도쿄](https://www.airbob.cloud/search?adultOccupancy=1&childOccupancy=0&infantOccupancy=0&petOccupancy=0&destination=%EC%9D%BC%EB%B3%B8+%EB%8F%84%EC%BF%84%EB%8F%84&lat=35.6764225&lng=139.650027&topLeftLat=36.4408483&topLeftLng=138.2991098&bottomRightLat=34.5776326&bottomRightLng=141.2405144) <br>

### 프로젝트 메인 시나리오 시연 영상
- 예약/결제
![Airbob-시연영상-gif2](https://github.com/user-attachments/assets/59449a2b-1588-4e90-8933-85bcd30bddae)

## 개발 환경
### Language
![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)

### Framework
![Spring Boot](https://img.shields.io/badge/spring_boot-%236DB33F.svg?style=for-the-badge&logo=springboot&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white)

### Database
![MySQL](https://img.shields.io/badge/mysql-4479A1.svg?style=for-the-badge&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/redis-%23DD0031.svg?style=for-the-badge&logo=redis&logoColor=white)
![Elasticsearch](https://img.shields.io/badge/elasticsearch-%230377CC.svg?style=for-the-badge&logo=elasticsearch&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white)

### Infra & Messaging
![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-000?style=for-the-badge&logo=apachekafka)
![Debezium](https://img.shields.io/badge/Debezium-000?style=for-the-badge&logo=debezium&logoColor=white)

### DevOps & Observability
![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)

### Build & Testing
![Gradle](https://img.shields.io/badge/Gradle-02303A.svg?style=for-the-badge&logo=Gradle&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=for-the-badge&logo=k6&logoColor=white)

<hr>

## 핵심 설계와 기술 선택

### 1. 3단계 동시성 제어 (Redis Hold → Redisson Lock → DB 검증)
인기 숙소에 동시 예약 요청이 몰릴 때 발생하는 Race Condition을 **3단계 방어 구조**로 해결했습니다.

| 단계 | 역할 | 기술 |
|------|------|------|
| **1. Redis Hold** | 결제 대기 중인 날짜에 대한 빠른 선차단 (최적화 레이어) | `MGET` 기반 다중 키 조회 |
| **2. Redisson MultiLock** | 동일 자원에 대한 동시 진입 직렬화 (진입 제어 레이어) | Pub/Sub 기반 분산 락 |
| **3. DB 중복 검증** | 최종 방어선 — DB를 진실의 원천으로 삼아 겹치는 예약 여부 재검증 | 트랜잭션 내 날짜 중복 조회 |

🔗 전체 흐름:
[ReservationService.java](src/main/java/kr/kro/airbob/domain/reservation/service/ReservationService.java)

**설계 포인트**
- **Pub/Sub 방식 락 대기**: 스핀 락 대신 Redis 채널 구독으로 락 해제 알림을 수신하여 불필요한 polling과 Redis
  부하를 최소화
- **Lock Key 오름차순 정렬**: 모든 요청이 동일한 순서로 락을 획득하도록 강제해 Circular Wait를 제거하고 데드락을
  구조적으로 방지 🔗
  [DateLockKeyGenerator.java](src/main/java/kr/kro/airbob/domain/reservation/service/DateLockKeyGenerator.java)
- **WatchDog 활성화**: `leaseTime`을 명시하지 않아 Redisson WatchDog이 락 TTL을 자동 갱신하도록 구성 — 장시간
  트랜잭션에서도 락 조기 만료 방지
- **'숙소 ID + 날짜' 단위 세분화 락**: 서로 다른 숙소 또는 날짜 간 불필요한 락 경합 없이 동시 처리량 유지
- **Redis 장애 격리**: Hold 설정/삭제 실패가 예약 트랜잭션에 전파되지 않도록 방어 처리 — Redis Hold 장애 시에도
  Redisson Lock과 DB 재검증으로 최종 정합성 유지

**테스트 검증** 🔗
[ReservationConcurrencyTest.java](src/test/java/kr/kro/airbob/domain/reservation/ReservationConcurrencyTest.java)
- 50개 동시 요청에서 1건만 성공하고 DB에도 1건만 저장됨을 검증
- 분산 락 제거 시 동일 조건에서 중복 예약이 발생함을 대조 실험으로 검증
- 날짜가 겹치는 두 예약의 동시 진행 시 데드락 없이 정상 처리됨을 검증
- 서로 다른 숙소는 같은 날짜여도 동시 예약이 모두 성공함을 통해 락 세분화 검증

### 2. Event-Driven Architecture (Kafka)
- 예약과 결제 시스템을 **Kafka** 기반의 비동기 이벤트로 분리하여 강한 결합도 해소 🔗 [ReservationEventTranslator.java](src/main/java/kr/kro/airbob/kafka/consumer/ReservationEventTranslator.java), [PaymentEventTranslator.java](src/main/java/kr/kro/airbob/kafka/consumer/PaymentEventTranslator.java)
- **Dead Letter Queue (DLQ)** 구축하여 메시지 처리 실패 시 자동 재시도 및 실패 로그 관리 🔗 [DlqConsumer.java](src/main/java/kr/kro/airbob/kafka/consumer/DlqConsumer.java)
- 외부 결제 시스템(PG) 장애 시에도 데이터 유실 없이 예약 요청을 안전하게 보관하는 회복 탄력성 확보

### 3. Transactional Outbox Pattern
- **Debezium(CDC)과 Outbox 패턴**을 활용해 DB 트랜잭션 커밋과 Kafka 이벤트 발행의 원자성 보장 🔗 [OutboxEventPublisher.java](src/main/java/kr/kro/airbob/outbox/OutboxEventPublisher.java)
- 'At-least-once' 전달 보장 및 메시지 중복 처리 방지를 위한 멱등성 고려 설계 🔗 [PaymentEventsConsumer.java](src/main/java/kr/kro/airbob/kafka/consumer/PaymentEventsConsumer.java)

### 4. Elasticsearch
- RDB의 `LIKE` 검색 한계를 극복하기 위해 **Elasticsearch** 검색 엔진 도입 🔗 [ElasticsearchConfig.java](src/main/java/kr/kro/airbob/config/ElasticsearchConfig.java)
- **Kafka Consumer** 기반의 인덱싱 파이프라인을 구축하여, MySQL 데이터 변경 이벤트를 실시간으로 Elasticsearch에 동기화 🔗 [AccommodationIndexingConsumer.java](src/main/java/kr/kro/airbob/kafka/consumer/AccommodationIndexingConsumer.java)

### 5. 성능 최적화
- **커서 기반 페이지네이션** 구현으로 대용량 데이터 조회 시 일정한 응답 성능(O(1)) 유지 🔗 [CursorParamArgumentResolver.java](src/main/java/kr/kro/airbob/cursor/resolver/CursorParamArgumentResolver.java)

<hr>

## 아키텍처
### 시스템 아키텍처

<img width="1651" height="1009" alt="Airbob-System-Architecture-png drawio (1)" src="https://github.com/user-attachments/assets/69e25cdb-f230-4a4e-9cbe-0eb006fe2ff7" />

> AWS Free Tier 크레딧 소진으로 인해  
> 현재는 OCI 단일 서버에서 Docker Compose 기반으로 운영 중입니다.


<br>

### 동시성 제어
인기 숙소 예약 시 발생하는 Race Condition을 해결하기 위해 날짜 단위 Redisson `MultiLock`을 적용했습니다. 아래는 동일 날짜 예약 요청이 경쟁할 때의 처리 흐름입니다.
```mermaid
sequenceDiagram
    participant UserA as User A
    participant UserB as User B
    participant API as API Server
    participant Redis as Redis (Redisson)
    participant DB as MySQL

    UserA->>API: 1. 예약 요청 (1/01 ~ 1/03)
    UserB->>API: 2. 예약 요청 (1/01 ~ 1/03)
    
    Note over API, Redis: Lock Key: [accommodation:101:20260101, ...20260102]

    API->>Redis: 3. User A: tryLock (Pub/Sub)
    Redis-->>API: 4. Lock Acquired (Success)
    
    API->>Redis: 5. User B: tryLock (Pub/Sub)
    Redis-->>API: 6. Lock Occupied -> Wait (Subscribe channel)

    API->>DB: 7. [User A] 재고 확인 & 예약 데이터 생성
    DB-->>API: 8. Commit
    
    API->>Redis: 9. [User A] unlock()
    Redis-->>API: 10. Publish 'Lock Released' Message

    Redis->>API: 11. [User B] Message Received (Wake up)
    API->>Redis: 12. [User B] tryLock Retry
    Redis-->>API: 13. Lock Acquired
    
    API->>DB: 14. [User B] 재고 확인 (Sold Out)
    API-->>UserB: 15. 예약 실패 (예약 마감)
```
<br>

### Transactional Outbox Pattern & CDC
서비스 간 데이터 정합성을 보장하기 위해 Outbox 패턴과 **Debezium(CDC)을** 도입했습니다.<br>

DB 트랜잭션 내에서 `Outbox` 테이블에 이벤트를 저장하고, Debezium이 이를 감지하여 Kafka로 발행함으로써 'At-least-once' 전달을 보장합니다.
```mermaid
sequenceDiagram
    participant Svc as Reservation Service
    participant DB as MySQL (Outbox Table)
    participant CDC as Debezium Connector
    participant Kafka as Apache Kafka
    participant Consumer as Payment/Search Consumer

    Svc->>DB: 1. 비즈니스 로직 수행 (INSERT/UPDATE)
    Svc->>DB: 2. Outbox 이벤트 저장 (INSERT into 'outbox')
    Svc->>DB: 3. Transaction Commit (Atomic)
    
    CDC->>DB: 4. Binlog 감지 (INSERT 'outbox')
    CDC->>Kafka: 5. Kafka 메시지 발행 (Topic: *.events)
    
    Kafka->>Consumer: 6. 메시지 소비 (Consume)
    
    alt 처리 성공
        Consumer->>Kafka: 7. Acknowledgment (Commit Offset)
    else 처리 실패
        Consumer->>Kafka: 7. DLQ로 이동 (Dead Letter Queue)
    end
```
<br>

### 검색 데이터 동기화 (Search Indexing Pipeline)
숙소 정보나 예약 상태 변경 시, 실시간으로 Elasticsearch 인덱스를 갱신하는 파이프라인입니다.<br>

Kafka Consumer가 변경 이벤트를 수신하여 ES에 반영함으로써, MySQL과 Elasticsearch 간의 **데이터 최종 일관성(Eventual Consistency)을** 유지합니다.
```mermaid
graph LR
    User[Admin/User] -->|Update| API[API Server]
    API -->|Commit| DB[(MySQL)]
    DB -.->|CDC| Debezium[Debezium]
    Debezium -->|Publish| Kafka{Kafka}
    
    subgraph "Indexing Consumer"
        Consumer[AccommodationIndexingConsumer]
    end
    
    Kafka -->|Consume| Consumer
    Consumer -->|Upsert/Delete| ES[(Elasticsearch)]
```
<br>

### ERD
<img width="1134" height="700" alt="airbob-erd" src="https://github.com/user-attachments/assets/e479eff6-262c-4a32-9cd1-2fbd9f00708f" />

## Core Features Scenario
Airbob의 핵심인 **숙소 예약 및 결제 프로세스**입니다. <br>
**동시성 제어(Redisson)를** 통해 중복 예약을 방지하고, **Outbox 패턴**으로 결제 데이터의 정합성을 보장합니다.

### 예약 및 결제 Sequnce diagram
```mermaid
sequenceDiagram
    actor User as 사용자
    participant API as API 서버
    participant Redis as Redis (락/캐시)
    participant DB as MySQL
    participant PG as Toss PG (외부)
    participant CDC as Debezium (CDC)
    participant Kafka as Apache Kafka

    note over User, Kafka: [Phase 1] 예약 요청 (동시성 제어)
    User->>API: 1. 예약 요청 (숙소ID, 날짜)
    API->>Redis: 2. 분산 락 획득 시도 (Pub/Sub)
    Redis-->>API: 3. 락 획득 성공

    API->>DB: 4. 재고 확인 및 예약 생성 (PENDING)
    DB-->>API: 5. 예약 완료 (ID 반환)
    API->>Redis: 6. 락 해제
    API-->>User: 7. 예약 대기 (결제 요청 필요)

    note over User, Kafka: [Phase 2] 결제 승인 및 확정 (데이터 정합성)
    User->>PG: 8. 결제 진행 (Toss 창)
    PG-->>User: 9. 결제 인증 성공
    User->>API: 10. 결제 승인 요청 (paymentKey)

    API->>PG: 11. 최종 승인 API 호출
    PG-->>API: 12. 승인 완료

    API->>DB: 13. 트랜잭션 시작
    API->>DB: 14. 결제 정보 저장 & 예약 확정 (CONFIRMED)
    API->>DB: 15. Outbox 이벤트 저장 (INSERT)
    API->>DB: 16. 트랜잭션 커밋

    par 비동기 이벤트 발행 (CDC)
        DB->>CDC: 17. Binlog 감지 (INSERT 감지)
        CDC->>Kafka: 18. 카프카 메시지 발행 (Topic: payment)
    end
    
    API-->>User: 19. 예약 확정 완료 화면
```

## API Reference
<p align="center">
  🌐 <a href="https://fourth-surprise-78f.notion.site/20dde1bad162819b9b79fa6b322b00a6?v=20dde1bad1628104a934000c7f90b893" target="_blank"><b>Notion API 명세서</b></a>
</p>
