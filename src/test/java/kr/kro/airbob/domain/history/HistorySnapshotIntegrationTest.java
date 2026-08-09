package kr.kro.airbob.domain.history;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationHistory;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.service.AccommodationService;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.dto.MemberRequest;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberHistory;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.service.MemberService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DisplayName("이력 테이블 SCD2 동작 통합 테스트")
class HistorySnapshotIntegrationTest {

	@Autowired private MemberService memberService;
	@Autowired private MemberRepository memberRepository;
	@Autowired private MemberHistoryRepository memberHistoryRepository;
	@Autowired private RedisTemplate<String, Object> redisTemplate;
	@Autowired private SessionRedisRepository sessionRedisRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private AccommodationService accommodationService;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private AccommodationHistoryRepository accommodationHistoryRepository;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

	@Container
	private static final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_test");

	@Container
	private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.datasource.username", mySQLContainer::getUsername);
		registry.add("spring.datasource.password", mySQLContainer::getPassword);
		registry.add("spring.flyway.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.flyway.user", mySQLContainer::getUsername);
		registry.add("spring.flyway.password", mySQLContainer::getPassword);
		registry.add("spring.data.redis.host", redisContainer::getHost);
		registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
		registry.add("spring.kafka.consumer.enabled", () -> "false");
		registry.add("spring.kafka.producer.enabled", () -> "false");
	}

	@BeforeEach
	@AfterEach
	void clean() {
		accommodationHistoryRepository.deleteAllInBatch();
		memberHistoryRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("member: 가입 시 CREATE 스냅샷 1행(현재행), 탈퇴 시 직전행 close + DELETE 스냅샷 open (SCD2)")
	void memberHistoryScd2() {
		// 가입 → 첫 데이터(CREATE)부터 기록
		memberService.createMember(MemberRequest.Signup.builder()
			.nickname("kim").email("kim@test.com").password("password1").build());
		Member member = memberRepository.findAll().get(0);

		List<MemberHistory> afterCreate = memberHistoryRepository.findAll();
		assertThat(afterCreate).hasSize(1);
		MemberHistory created = afterCreate.get(0);
		assertThat(created.getChangeType()).isEqualTo(ChangeType.CREATE);
		assertThat(created.getStatus()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(created.getMemberId()).isEqualTo(member.getId());
		assertThat(created.getHistoryCreatedAt()).isNotNull();
		assertThat(created.getValidTo()).isEqualTo(HistoryConstants.FOREVER); // 현재 행 = 센티넬

		// 탈퇴 → SCD2: 직전 현재 행 close + 새 스냅샷 open
		memberService.deleteMember(member.getId(), "사용자 탈퇴");

		List<MemberHistory> afterDelete = memberHistoryRepository.findAll();
		assertThat(afterDelete).hasSize(2);

		List<MemberHistory> current = afterDelete.stream()
			.filter(h -> h.getValidTo().equals(HistoryConstants.FOREVER)).toList();
		assertThat(current).hasSize(1); // 현재 행은 정확히 1개
		assertThat(current.get(0).getChangeType()).isEqualTo(ChangeType.DELETE);
		assertThat(current.get(0).getStatus()).isEqualTo(MemberStatus.DELETED);

		MemberHistory closed = afterDelete.stream()
			.filter(h -> !h.getValidTo().equals(HistoryConstants.FOREVER)).findFirst().orElseThrow();
		assertThat(closed.getChangeType()).isEqualTo(ChangeType.CREATE);
		assertThat(closed.getStatus()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(closed.getValidTo()).isBefore(HistoryConstants.FOREVER); // 닫힘
	}

	@Test
	@DisplayName("회원 탈퇴 시 해당 회원의 모든 세션만 Redis에서 제거한다")
	void memberDeletionRevokesOnlyTargetMemberSessions() {
		memberService.createMember(MemberRequest.Signup.builder()
			.nickname("kim").email("session@test.com").password("password1").build());
		Member member = memberRepository.findAll().getFirst();
		sessionRedisRepository.saveSession("target-session-1", member.getId());
		sessionRedisRepository.saveSession("target-session-2", member.getId());
		sessionRedisRepository.saveSession("other-session", 999L);

		memberService.deleteMember(member.getId(), "사용자 탈퇴");

		assertThat(sessionRedisRepository.getMemberIdBySession("target-session-1")).isEmpty();
		assertThat(sessionRedisRepository.getMemberIdBySession("target-session-2")).isEmpty();
		assertThat(redisTemplate.hasKey("MEMBER_SESSIONS:" + member.getId())).isFalse();
		assertThat(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:" + member.getId())).isFalse();
		assertThat(sessionRedisRepository.getMemberIdBySession("other-session")).contains(999L);
		assertThat(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:999")).isTrue();
		assertThat(redisTemplate.opsForZSet().range("MEMBER_SESSIONS:999", 0, -1))
			.containsExactly("other-session");

		sessionRedisRepository.deleteAllSessions(999L);
	}

	@Test
	@DisplayName("로그인과 탈퇴가 사용하는 회원 쓰기 잠금은 같은 회원에서 직렬화된다")
	void memberWriteLocksSerializeLoginAndDeletion() throws Exception {
		memberService.createMember(MemberRequest.Signup.builder()
			.nickname("kim").email("concurrent-session@test.com").password("password1").build());
		Member member = memberRepository.findAll().getFirst();

		CountDownLatch deletionLockAcquired = new CountDownLatch(1);
		CountDownLatch releaseDeletionLock = new CountDownLatch(1);
		CountDownLatch loginLockAttempted = new CountDownLatch(1);
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CompletableFuture<Void> deletionLockFuture = CompletableFuture.runAsync(() ->
			transactionTemplate.executeWithoutResult(status -> {
				memberRepository.findByIdForUpdate(member.getId()).orElseThrow();
				deletionLockAcquired.countDown();
				try {
					if (!releaseDeletionLock.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("회원 탈퇴 잠금 해제 대기 시간 초과");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("회원 탈퇴 잠금 대기 중 인터럽트", exception);
				}
			}), executor);

		try {
			assertThat(deletionLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<Member> loginLockFuture = CompletableFuture.supplyAsync(() ->
				transactionTemplate.execute(status -> {
					loginLockAttempted.countDown();
					return memberRepository.findByIdAndStatusForUpdate(
						member.getId(), MemberStatus.ACTIVE
					).orElseThrow();
				}), executor);

			assertThat(loginLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> loginLockFuture.get(1, TimeUnit.SECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseDeletionLock.countDown();
			deletionLockFuture.get(5, TimeUnit.SECONDS);
			assertThat(loginLockFuture.get(5, TimeUnit.SECONDS).getId()).isEqualTo(member.getId());
		} finally {
			releaseDeletionLock.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("accommodation: 생성 시 CREATE 스냅샷 1행, 삭제 시 직전행 close + DELETE 스냅샷 open (SCD2)")
	void accommodationHistoryScd2() {
		Member host = memberRepository.save(Member.builder().email("host@test.com").nickname("host").build());

		AccommodationResponse.Create created = accommodationService.createAccommodation(host.getId());
		Long accommodationId = created.id();

		List<AccommodationHistory> afterCreate = accommodationHistoryRepository.findAll();
		assertThat(afterCreate).hasSize(1);
		AccommodationHistory createdHistory = afterCreate.get(0);
		assertThat(createdHistory.getChangeType()).isEqualTo(ChangeType.CREATE);
		assertThat(createdHistory.getStatus()).isEqualTo(AccommodationStatus.DRAFT);
		assertThat(createdHistory.getAccommodationId()).isEqualTo(accommodationId);
		assertThat(createdHistory.getValidTo()).isEqualTo(HistoryConstants.FOREVER);

		accommodationService.deleteAccommodation(accommodationId, host.getId());

		List<AccommodationHistory> afterDelete = accommodationHistoryRepository.findAll();
		assertThat(afterDelete).hasSize(2);

		List<AccommodationHistory> current = afterDelete.stream()
			.filter(h -> h.getValidTo().equals(HistoryConstants.FOREVER)).toList();
		assertThat(current).hasSize(1);
		assertThat(current.get(0).getChangeType()).isEqualTo(ChangeType.DELETE);
		assertThat(current.get(0).getStatus()).isEqualTo(AccommodationStatus.DELETED);

		AccommodationHistory closed = afterDelete.stream()
			.filter(h -> !h.getValidTo().equals(HistoryConstants.FOREVER)).findFirst().orElseThrow();
		assertThat(closed.getChangeType()).isEqualTo(ChangeType.CREATE);
		assertThat(closed.getValidTo()).isBefore(HistoryConstants.FOREVER);
	}
}
