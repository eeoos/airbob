package kr.kro.airbob.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.common.exception.AdminAccessDeniedException;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest.ChangeRole;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberHistory;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.service.MemberAdminService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("회원 역할 관리 통합 테스트")
class MemberRoleManagementIntegrationTest {

	private static final long TIMEOUT_SECONDS = 10;
	private static final String EXISTING_SESSION_ID = "member-role-management-existing-session";

	@Autowired private MemberAdminService memberAdminService;
	@Autowired private MemberRepository memberRepository;
	@Autowired private MemberHistoryRepository memberHistoryRepository;
	@Autowired private SessionRedisRepository sessionRedisRepository;
	@Autowired private AdminAuthInterceptor adminAuthInterceptor;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManagerFactory entityManagerFactory;

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
	}

	@BeforeEach
	@AfterEach
	void clearDatabase() {
		UserContext.clear();
		sessionRedisRepository.deleteSession(EXISTING_SESSION_ID);
		memberHistoryRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("MEMBER를 ADMIN으로 변경하면 현재 이력을 닫고 역할 변경 이력을 남긴다")
	void changeRoleClosesCurrentHistoryAndCreatesRoleChangeHistory() {
		Member actor = createAdmin("actor@test.com");
		Member target = createMember("target@test.com");
		createCurrentHistory(target, "가입");

		changeRoleAs(actor.getId(), target.getId(), MemberRole.ADMIN, "운영 관리자 지정");

		assertThat(memberRepository.findById(target.getId()).orElseThrow().getRole())
			.isEqualTo(MemberRole.ADMIN);
		assertThat(memberHistoryRepository.findAll())
			.filteredOn(history -> history.getMemberId().equals(target.getId()))
			.satisfiesExactlyInAnyOrder(
				history -> assertThat(history.getValidTo()).isBefore(HistoryConstants.FOREVER),
				history -> {
					assertThat(history.getValidTo()).isEqualTo(HistoryConstants.FOREVER);
					assertThat(history.getChangeType()).isEqualTo(ChangeType.ROLE_CHANGE);
					assertThat(history.getRole()).isEqualTo(MemberRole.ADMIN);
					assertThat(history.getChangeReason()).isEqualTo("운영 관리자 지정");
					assertThat(history.getHistoryCreatedBy()).isEqualTo(actor.getId());
				}
			);
	}

	@Test
	@DisplayName("기존 세션은 재발급 없이 역할 승격과 회수를 다음 관리자 요청에 즉시 반영한다")
	void existingSessionImmediatelyReflectsPromotionAndRevocation() {
		Member actor = createAdmin("session-actor@test.com");
		Member target = createMember("session-target@test.com");
		sessionRedisRepository.saveSession(EXISTING_SESSION_ID, target.getId());

		assertThat(sessionRedisRepository.getMemberIdBySession(EXISTING_SESSION_ID))
			.contains(target.getId());

		changeRoleAs(actor.getId(), target.getId(), MemberRole.ADMIN, "기존 세션 관리자 승격");
		try {
			UserContext.set(new UserInfo(target.getId()));
			assertThat(adminAuthInterceptor.preHandle(null, null, new Object())).isTrue();
		} finally {
			UserContext.clear();
		}
		assertThat(sessionRedisRepository.getMemberIdBySession(EXISTING_SESSION_ID))
			.contains(target.getId());

		changeRoleAs(actor.getId(), target.getId(), MemberRole.MEMBER, "기존 세션 관리자 권한 회수");
		try {
			UserContext.set(new UserInfo(target.getId()));
			assertThatThrownBy(() -> adminAuthInterceptor.preHandle(null, null, new Object()))
				.isInstanceOf(AdminAccessDeniedException.class);
		} finally {
			UserContext.clear();
		}
		assertThat(sessionRedisRepository.getMemberIdBySession(EXISTING_SESSION_ID))
			.contains(target.getId());
	}

	@Test
	@DisplayName("서로의 관리자 권한을 동시에 회수하면 한 명만 관리자 권한을 잃는다")
	void concurrentCrossRevocationLeavesExactlyOneActiveAdmin() throws Exception {
		Member adminA = createAdmin("admin-a@test.com");
		Member adminB = createAdmin("admin-b@test.com");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Throwable> revokeB = executor.submit(() -> changeRoleConcurrently(
				ready, start, adminA.getId(), adminB.getId()));
			Future<Throwable> revokeA = executor.submit(() -> changeRoleConcurrently(
				ready, start, adminB.getId(), adminA.getId()));

			assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<Throwable> results = Arrays.asList(
				revokeB.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
				revokeA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			assertThat(results).filteredOn(result -> result == null).hasSize(1);
			assertThat(results)
				.filteredOn(result -> result != null)
				.allSatisfy(result -> assertThat(result).isInstanceOf(AdminAccessDeniedException.class));
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			}
		}

		long activeAdminCount = memberRepository.findAll().stream()
			.filter(member -> member.getStatus() == MemberStatus.ACTIVE)
			.filter(member -> member.getRole() == MemberRole.ADMIN)
			.count();
		assertThat(activeAdminCount).isEqualTo(1);
	}

	@Test
	@DisplayName("요청 영속성 컨텍스트에 미리 로드된 관리자도 잠금 후 최신 권한으로 재검증한다")
	void requestBoundStaleActorsCannotBothRevokeEachOther() throws Exception {
		Member adminA = createAdmin("request-admin-a@test.com");
		Member adminB = createAdmin("request-admin-b@test.com");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch bothActorsPreloaded = new CountDownLatch(2);
		CountDownLatch firstRevocationCompleted = new CountDownLatch(1);
		try {
			Future<Throwable> revokeB = executor.submit(() -> changeRoleWithRequestBoundEntityManager(
				adminA.getId(), adminB.getId(), bothActorsPreloaded, null, firstRevocationCompleted));
			Future<Throwable> revokeA = executor.submit(() -> changeRoleWithRequestBoundEntityManager(
				adminB.getId(), adminA.getId(), bothActorsPreloaded, firstRevocationCompleted, null));

			assertThat(revokeB.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
			assertThat(revokeA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
				.isInstanceOf(AdminAccessDeniedException.class);
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			}
		}

		long activeAdminCount = memberRepository.findAll().stream()
			.filter(member -> member.getStatus() == MemberStatus.ACTIVE)
			.filter(member -> member.getRole() == MemberRole.ADMIN)
			.count();
		assertThat(activeAdminCount).isEqualTo(1);
	}

	private Throwable changeRoleConcurrently(
		CountDownLatch ready,
		CountDownLatch start,
		Long actorId,
		Long targetId
	) {
		try {
			return new TransactionTemplate(transactionManager).execute(status -> {
				try {
					UserContext.set(new UserInfo(actorId));
					ready.countDown();
					if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
						throw new IllegalStateException("동시 역할 변경 시작 대기 시간이 초과되었습니다.");
					}
					memberAdminService.changeRole(
						actorId, targetId, new ChangeRole(MemberRole.MEMBER, "관리자 권한 회수"));
					return null;
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("동시 역할 변경 작업이 중단되었습니다.", exception);
				} finally {
					UserContext.clear();
				}
			});
		} catch (Throwable throwable) {
			return throwable;
		}
	}

	private Throwable changeRoleWithRequestBoundEntityManager(
		Long actorId,
		Long targetId,
		CountDownLatch bothActorsPreloaded,
		CountDownLatch waitFor,
		CountDownLatch signalAfterCompletion
	) {
		EntityManager entityManager = entityManagerFactory.createEntityManager();
		TransactionSynchronizationManager.bindResource(
			entityManagerFactory, new EntityManagerHolder(entityManager));
		try {
			UserContext.set(new UserInfo(actorId));
			Member preloadedActor = memberRepository.findById(actorId).orElseThrow();
			assertThat(entityManager.contains(preloadedActor)).isTrue();
			bothActorsPreloaded.countDown();
			if (!bothActorsPreloaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("요청별 관리자 사전 로드 대기 시간이 초과되었습니다.");
			}
			if (waitFor != null && !waitFor.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("선행 권한 회수 완료 대기 시간이 초과되었습니다.");
			}

			memberAdminService.changeRole(
				actorId, targetId, new ChangeRole(MemberRole.MEMBER, "관리자 권한 회수"));
			return null;
		} catch (Throwable throwable) {
			return throwable;
		} finally {
			if (signalAfterCompletion != null) {
				signalAfterCompletion.countDown();
			}
			UserContext.clear();
			TransactionSynchronizationManager.unbindResource(entityManagerFactory);
			entityManager.close();
		}
	}

	private Member createAdmin(String email) {
		Member member = createMember(email);
		member.changeRole(MemberRole.ADMIN);
		return memberRepository.saveAndFlush(member);
	}

	private Member createMember(String email) {
		return memberRepository.saveAndFlush(Member.builder()
			.email(email)
			.password("password")
			.nickname(email)
			.build());
	}

	private void createCurrentHistory(Member member, String reason) {
		memberHistoryRepository.saveAndFlush(MemberHistory.openSystem(
			member, ChangeType.CREATE, reason, "TEST", LocalDateTime.now()));
	}

	private void changeRoleAs(Long actorId, Long targetId, MemberRole role, String reason) {
		try {
			UserContext.set(new UserInfo(actorId));
			memberAdminService.changeRole(actorId, targetId, new ChangeRole(role, reason));
		} finally {
			UserContext.clear();
		}
	}
}
