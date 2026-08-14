package kr.kro.airbob.domain.payment;

import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.APPLIED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.DECLINED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.EXECUTING;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.MANUAL_REVIEW;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.OUTCOME_UNKNOWN;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.READY;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.RETRY_WAIT;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.CONFIRMED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.EXPIRED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.PAYMENT_PENDING;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.PAYMENT_PROCESSING;
import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;
import static kr.kro.airbob.outbox.EventType.RESERVATION_CHANGED;
import static kr.kro.airbob.outbox.EventType.RESERVATION_CONFIRMED;
import static kr.kro.airbob.outbox.EventType.RESERVATION_EXPIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.coupon.service.CouponTimeProvider;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.payment.api.PaymentController;
import kr.kro.airbob.domain.payment.api.PaymentOperationController;
import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.domain.payment.service.PaymentOperationFinalizer;
import kr.kro.airbob.domain.payment.service.PaymentOperationLeaseService;
import kr.kro.airbob.domain.payment.service.PaymentOperationQueryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService;
import kr.kro.airbob.domain.payment.service.PaymentRetryBackoff;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationCommand;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationGateway;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.kafka.consumer.PaymentOperationEventsConsumer;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.outbox.entity.Outbox;
import kr.kro.airbob.outbox.repository.OutboxRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	CouponTimeProvider.class,
	CouponUsageService.class,
	OutboxEventPublisher.class,
	DebeziumEventParser.class,
	PaymentOperationCommandService.class,
	PaymentOperationLeaseService.class,
	PaymentOperationExecutor.class,
	PaymentOperationFinalizer.class,
	PaymentOperationQueryService.class,
	PaymentOperationRecoveryService.class,
	PaymentOperationFlowIntegrationTest.FlowTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationFlowIntegrationTest {

	private static final UUID RESERVATION_UID =
		UUID.fromString("3aa4adf4-a748-46a8-90ec-a24c75591bcb");
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("79709a85-260d-44bb-9877-4c43825c30a7");
	private static final String PAYMENT_KEY = "task-ten-provider-payment-key";
	private static final long AMOUNT = 90_000L;
	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
	private static final Duration RETRY_MAX_DELAY = Duration.ofMinutes(5);
	private static final int MAX_ATTEMPTS = 5;
	private static final List<Duration> UNKNOWN_RETRY_DELAYS = List.of(
		Duration.ofSeconds(10),
		Duration.ofSeconds(20),
		Duration.ofSeconds(40),
		Duration.ofSeconds(80)
	);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_operation_flow")
		.withCommand("--log-bin-trust-function-creators=1");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdjustableClock clock;
	@Autowired private ScriptedPaymentGateway gateway;
	@Autowired private PaymentOperationCommandService commandService;
	@Autowired private PaymentOperationExecutor executor;
	@Autowired private PaymentOperationQueryService queryService;
	@Autowired private PaymentOperationRecoveryService recoveryService;
	@Autowired private PaymentOperationRepository operationRepository;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationHistoryRepository historyRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private PaymentTransactionRepository transactionRepository;
	@Autowired private OutboxRepository outboxRepository;
	@Autowired private DebeziumEventParser parser;

	private MockMvc mockMvc;
	private PaymentOperationEventsConsumer consumer;
	private long ownerId;
	private long nonOwnerId;
	private long reservationId;
	private long memberCouponId;
	private final Map<UUID, String> acceptedStatusUrls = new HashMap<>();

	@BeforeEach
	void setUp() {
		dropFailureTriggers();
		clearFixtureRows();
		clock.set(NOW);
		gateway.reset();
		acceptedStatusUrls.clear();
		insertPendingReservationFixture();

		PaymentController paymentController = new PaymentController(commandService, null);
		PaymentOperationController operationController = new PaymentOperationController(queryService);
		mockMvc = MockMvcBuilders.standaloneSetup(paymentController, operationController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
		consumer = new PaymentOperationEventsConsumer(parser, executor, null);
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
		dropFailureTriggers();
	}

	@Test
	void ownerRequest_thenApproved() throws Exception {
		MvcResult accepted = accept(ownerId, status().isAccepted());
		UUID operationUid = acceptedOperationUid(accepted);
		assertThat(responseData(accepted).path("status").asText()).isEqualTo("PENDING");
		assertNoSecret(accepted.getResponse().getContentAsString());
		assertDurableState(operationUid, READY, PAYMENT_PROCESSING, 0, 0, List.of());

		gateway.enqueueConfirm(approved());
		deliverLatestExecutionEvent();

		assertDurableState(operationUid, APPLIED, CONFIRMED, 1, 1,
			List.of(RESERVATION_CONFIRMED, RESERVATION_CHANGED));
		assertLedgerType(operationUid, PaymentTransactionType.CONFIRM);
		assertOperationPrivacy(operationUid, "SUCCEEDED");
	}

	@Test
	void outboxFailureDuringAcceptance() throws Exception {
		createOutboxFailureTrigger();

		MvcResult failed = accept(ownerId, status().is5xxServerError());

		assertThat(operationRepository.count()).isZero();
		assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
			.isEqualTo(PAYMENT_PENDING);
		assertThat(paymentRepository.count()).isZero();
		assertThat(transactionRepository.count()).isZero();
		assertTerminalOutboxTypes(List.of());
		assertThat(outboxRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertNoSecret(failed.getResponse().getContentAsString());
	}

	@Test
	void duplicateHttpAndKafkaDelivery() throws Exception {
		MvcResult first = accept(ownerId, status().isAccepted());
		MvcResult duplicate = accept(ownerId, status().isAccepted());
		UUID operationUid = acceptedOperationUid(first);
		assertThat(acceptedOperationUid(duplicate)).isEqualTo(operationUid);
		assertThat(operationRepository.count()).isOne();
		assertThat(executionOutboxes()).hasSize(1);

		gateway.enqueueConfirm(approved());
		String message = latestExecutionPayload();
		deliver(message);
		deliver(message);

		assertThat(gateway.calls()).containsExactly("confirm");
		assertDurableState(operationUid, APPLIED, CONFIRMED, 1, 1,
			List.of(RESERVATION_CONFIRMED, RESERVATION_CHANGED));
		assertLedgerType(operationUid, PaymentTransactionType.CONFIRM);
		assertOperationPrivacy(operationUid, "SUCCEEDED");
	}

	@Test
	void connectFailure_thenRetry_thenApproved() throws Exception {
		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		gateway.enqueueConfirm(new PaymentGatewayResult.RetryableFailure(
			"CONNECTION_FAILED", "provider connection failed"));
		deliverLatestExecutionEvent();

		assertDurableState(operationUid, RETRY_WAIT, PAYMENT_PROCESSING, 0, 0, List.of());
		assertOperationPrivacy(operationUid, "PENDING");

		clock.advance(RETRY_DELAY.plusSeconds(1));
		assertThat(recoveryService.recoverDue().enqueued()).isOne();
		gateway.enqueueConfirm(approved());
		deliverLatestExecutionEvent();

		assertThat(gateway.calls()).containsExactly("confirm", "confirm");
		assertConfirmCommands(operationUid, 2);
		assertDurableState(operationUid, APPLIED, CONFIRMED, 1, 1,
			List.of(RESERVATION_CONFIRMED, RESERVATION_CHANGED));
		assertOperationPrivacy(operationUid, "SUCCEEDED");
	}

	@Test
	void readTimeout_thenInquiryApproved() throws Exception {
		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		gateway.enqueueConfirm(new PaymentGatewayResult.OutcomeUnknown(
			"READ_TIMEOUT", "provider response unavailable"));
		deliverLatestExecutionEvent();

		assertDurableState(operationUid, OUTCOME_UNKNOWN, PAYMENT_PROCESSING, 0, 0, List.of());
		assertOperationPrivacy(operationUid, "PROCESSING");

		clock.advance(RETRY_DELAY.plusSeconds(1));
		assertThat(recoveryService.recoverDue().enqueued()).isOne();
		gateway.enqueueInquiry(approved());
		deliverLatestExecutionEvent();

		assertThat(gateway.calls()).containsExactly("confirm", "inquire");
		assertDurableState(operationUid, APPLIED, CONFIRMED, 1, 1,
			List.of(RESERVATION_CONFIRMED, RESERVATION_CHANGED));
		assertOperationPrivacy(operationUid, "SUCCEEDED");
	}

	@Test
	void successResponse_thenDbRollback_thenInquiry() throws Exception {
		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		gateway.enqueueConfirm(approved());
		createLedgerFailureTrigger();

		assertThatThrownBy(this::deliverLatestExecutionEvent)
			.isInstanceOf(RuntimeException.class);
		dropLedgerFailureTrigger();

		assertThat(gateway.calls()).containsExactly("confirm");
		assertDurableState(operationUid, EXECUTING, PAYMENT_PROCESSING, 0, 0, List.of());
		assertOperationPrivacy(operationUid, "PROCESSING");

		clock.advance(Duration.ofSeconds(31));
		assertThat(recoveryService.recoverDue().enqueued()).isOne();
		assertDurableState(operationUid, OUTCOME_UNKNOWN, PAYMENT_PROCESSING, 0, 0, List.of());
		gateway.enqueueInquiry(approved());
		deliverLatestExecutionEvent();

		assertThat(gateway.calls()).containsExactly("confirm", "inquire");
		assertDurableState(operationUid, APPLIED, CONFIRMED, 1, 1,
			List.of(RESERVATION_CONFIRMED, RESERVATION_CHANGED));
		assertLedgerType(operationUid, PaymentTransactionType.CONFIRM);
		assertOperationPrivacy(operationUid, "SUCCEEDED");
	}

	@Test
	void delayedApprovalAfterAcceptedDeadline() throws Exception {
		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		clock.advance(Duration.ofMinutes(2));
		gateway.enqueueConfirm(approved());

		deliverLatestExecutionEvent();

		assertThat(gateway.calls()).containsExactly("confirm");
		assertDurableState(operationUid, APPLIED, CONFIRMED, 1, 1,
			List.of(RESERVATION_CONFIRMED, RESERVATION_CHANGED));
		assertThat(outboxEventTypes()).doesNotContain(
			EventType.PAYMENT_CANCELLATION_REQUESTED.name());
		assertThat(isCouponUsed()).isTrue();
		assertOperationPrivacy(operationUid, "SUCCEEDED");
	}

	@Test
	void finalDeclineWithCoupon() throws Exception {
		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		gateway.enqueueConfirm(new PaymentGatewayResult.Declined(
			"REJECT_CARD_PAYMENT", "issuer declined"));

		deliverLatestExecutionEvent();

		assertDurableState(operationUid, DECLINED, EXPIRED, 0, 1,
			List.of(RESERVATION_EXPIRED));
		assertLedgerType(operationUid, PaymentTransactionType.FAIL);
		assertThat(isCouponUsed()).isFalse();
		assertThat(jdbc.queryForObject(
			"SELECT reservation_id FROM member_coupon WHERE id = ?",
			Long.class,
			memberCouponId
		)).isEqualTo(reservationId);
		assertThat(jdbc.queryForObject(
			"SELECT used_at FROM member_coupon WHERE id = ?",
			java.time.LocalDateTime.class,
			memberCouponId
		)).isNull();
		assertOperationPrivacy(operationUid, "FAILED");
	}

	@Test
	void exhaustedUnknown() throws Exception {
		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		gateway.enqueueConfirm(new PaymentGatewayResult.OutcomeUnknown(
			"READ_TIMEOUT", "provider response unavailable"));
		deliverLatestExecutionEvent();

		assertDurableState(operationUid, OUTCOME_UNKNOWN, PAYMENT_PROCESSING, 0, 0, List.of());
		assertThat(operationRepository.findByOperationUid(operationUid).orElseThrow().getAttemptCount())
			.isOne();
		assertOperationPrivacy(operationUid, "PROCESSING");

		for (int attempt = 2; attempt <= MAX_ATTEMPTS; attempt++) {
			Duration retryDelay = UNKNOWN_RETRY_DELAYS.get(attempt - 2);
			clock.advance(retryDelay.minusSeconds(1));
			assertThat(recoveryService.recoverDue().enqueued()).isZero();
			clock.advance(Duration.ofSeconds(2));
			assertThat(recoveryService.recoverDue().enqueued()).isOne();

			gateway.enqueueInquiry(new PaymentGatewayResult.RetryableFailure(
				"CONNECTION_FAILED", "provider inquiry unavailable"));
			deliverLatestExecutionEvent();

			assertThat(gateway.confirmCommands()).hasSize(1);
			assertThat(gateway.inquiryCommands()).hasSize(attempt - 1);
			assertThat(gateway.calls().subList(1, gateway.calls().size())).containsOnly("inquire");
			assertThat(operationRepository.findByOperationUid(operationUid).orElseThrow().getAttemptCount())
				.isEqualTo(attempt);

			if (attempt < MAX_ATTEMPTS) {
				assertDurableState(operationUid, OUTCOME_UNKNOWN, PAYMENT_PROCESSING, 0, 0, List.of());
				assertOperationPrivacy(operationUid, "PROCESSING");
			}
		}

		assertThat(gateway.calls()).containsExactly(
			"confirm", "inquire", "inquire", "inquire", "inquire");
		assertConfirmCommands(operationUid, 1);
		assertDurableState(operationUid, MANUAL_REVIEW, PAYMENT_PROCESSING, 0, 0, List.of());
		assertThat(operationRepository.findByOperationUid(operationUid).orElseThrow().getFailureCode())
			.isEqualTo("CONNECTION_FAILED");
		assertOperationPrivacy(operationUid, "REQUIRES_REVIEW");
	}

	@Test
	void nonOwnerCreateAndRead() throws Exception {
		PersistenceSnapshot beforeForbiddenCreate = persistenceSnapshot();
		MvcResult forbiddenCreate = accept(nonOwnerId, status().isForbidden());

		assertThat(persistenceSnapshot()).isEqualTo(beforeForbiddenCreate);
		assertForbiddenResponseHasNoDataOrDetails(forbiddenCreate);
		assertNoSecret(forbiddenCreate.getResponse().getContentAsString());

		UUID operationUid = acceptedOperationUid(accept(ownerId, status().isAccepted()));
		PersistenceSnapshot beforeForbiddenRead = persistenceSnapshot();
		MvcResult forbiddenRead = readOperation(operationUid, nonOwnerId, status().isForbidden());

		assertThat(persistenceSnapshot()).isEqualTo(beforeForbiddenRead);
		assertForbiddenResponseHasNoDataOrDetails(forbiddenRead);
		assertThat(forbiddenRead.getResponse().getContentAsString())
			.doesNotContain(PAYMENT_KEY)
			.doesNotContain(operationUid.toString())
			.doesNotContain(RESERVATION_UID.toString());
		assertDurableState(operationUid, READY, PAYMENT_PROCESSING, 0, 0, List.of());
		assertOperationPrivacy(operationUid, "PENDING");
	}

	private MvcResult accept(long memberId, org.springframework.test.web.servlet.ResultMatcher expectedStatus)
		throws Exception {
		UserContext.set(new UserInfo(memberId));
		try {
			return mockMvc.perform(post("/api/v1/payments/confirm")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new PaymentRequest.Confirm(
						PAYMENT_KEY, RESERVATION_UID.toString(), Math.toIntExact(AMOUNT)))))
				.andExpect(expectedStatus)
				.andReturn();
		} finally {
			UserContext.clear();
		}
	}

	private MvcResult readOperation(
		UUID operationUid,
		long memberId,
		org.springframework.test.web.servlet.ResultMatcher expectedStatus
	) throws Exception {
		String statusUrl = acceptedStatusUrls.get(operationUid);
		assertThat(statusUrl)
			.as("polling must use the status_url returned by the acceptance response")
			.isNotNull();
		UserContext.set(new UserInfo(memberId));
		try {
			return mockMvc.perform(get(statusUrl))
				.andExpect(expectedStatus)
				.andReturn();
		} finally {
			UserContext.clear();
		}
	}

	private UUID acceptedOperationUid(MvcResult result) throws Exception {
		JsonNode data = responseData(result);
		UUID operationUid = UUID.fromString(data.path("operation_id").asText());
		String expectedStatusUrl = "/api/v1/payment-operations/" + operationUid;
		String returnedStatusUrl = data.path("status_url").asText();
		assertThat(returnedStatusUrl).isEqualTo(expectedStatusUrl);
		acceptedStatusUrls.put(operationUid, returnedStatusUrl);
		return operationUid;
	}

	private JsonNode responseData(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
	}

	private void deliverLatestExecutionEvent() {
		deliver(latestExecutionPayload());
	}

	private void deliver(String message) {
		consumer.handle(message, () -> { });
	}

	private String latestExecutionPayload() {
		return executionOutboxes().stream()
			.max(java.util.Comparator.comparing(Outbox::getId))
			.orElseThrow()
			.getPayload();
	}

	private List<Outbox> executionOutboxes() {
		return outboxRepository.findAll().stream()
			.filter(outbox -> outbox.getEventType().equals(PAYMENT_EXECUTION_REQUESTED_V1.name()))
			.toList();
	}

	private void assertDurableState(
		UUID operationUid,
		PaymentOperationStatus operationStatus,
		ReservationStatus reservationStatus,
		long paymentCount,
		long ledgerCount,
		List<EventType> terminalOutboxTypes
	) {
		PaymentOperation operation = operationRepository.findByOperationUid(operationUid).orElseThrow();
		assertThat(operationRepository.count()).isOne();
		assertThat(operation.getStatus()).isEqualTo(operationStatus);
		assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
			.isEqualTo(reservationStatus);
		assertThat(paymentRepository.count()).isEqualTo(paymentCount);
		assertThat(transactionRepository.countByPaymentOperationId(operation.getId()))
			.isEqualTo(ledgerCount);
		assertTerminalOutboxTypes(terminalOutboxTypes);
	}

	private void assertTerminalOutboxTypes(List<EventType> expected) {
		List<String> expectedNames = expected.stream().map(Enum::name).toList();
		List<String> actual = outboxEventTypes().stream()
			.filter(type -> !type.equals(PAYMENT_EXECUTION_REQUESTED_V1.name()))
			.toList();
		assertThat(actual).containsExactlyInAnyOrderElementsOf(expectedNames);
	}

	private void assertLedgerType(UUID operationUid, PaymentTransactionType expected) {
		Long operationId = operationRepository.findByOperationUid(operationUid).orElseThrow().getId();
		assertThat(transactionRepository.findAll())
			.filteredOn(transaction -> operationId.equals(transaction.getPaymentOperationId()))
			.extracting(transaction -> transaction.getTransactionType())
			.containsExactly(expected);
	}

	private void assertOperationPrivacy(UUID operationUid, String expectedStatus) throws Exception {
		MvcResult statusResponse = readOperation(operationUid, ownerId, status().isOk());
		String serializedStatus = statusResponse.getResponse().getContentAsString();
		assertThat(responseData(statusResponse).path("status").asText()).isEqualTo(expectedStatus);
		assertNoSecret(serializedStatus);

		List<Outbox> executionOutboxes = executionOutboxes();
		assertThat(executionOutboxes).isNotEmpty();
		for (Outbox outbox : executionOutboxes) {
			assertExecutionOutboxContract(outbox, operationUid);
			assertNoSecret(outbox.getPayload());
		}
		assertGatewayCommandsCorrelated(operationUid);
	}

	private void assertExecutionOutboxContract(Outbox outbox, UUID operationUid) throws Exception {
		assertThat(outbox.getAggregateId()).isEqualTo(RESERVATION_UID.toString());
		JsonNode payload = objectMapper.readTree(outbox.getPayload()).path("payload");
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder("operation_uid", "reservation_uid");
		assertThat(payload.path("operation_uid").asText()).isEqualTo(operationUid.toString());
		assertThat(payload.path("reservation_uid").asText()).isEqualTo(RESERVATION_UID.toString());
	}

	private void assertGatewayCommandsCorrelated(UUID operationUid) {
		PaymentConfirmationCommand expected = expectedGatewayCommand(operationUid);
		assertThat(gateway.invocations())
			.extracting(GatewayInvocation::command)
			.allSatisfy(command -> assertThat(command).isEqualTo(expected));
	}

	private void assertConfirmCommands(UUID operationUid, int expectedCount) {
		PaymentConfirmationCommand expected = expectedGatewayCommand(operationUid);
		assertThat(gateway.confirmCommands())
			.hasSize(expectedCount)
			.allSatisfy(command -> assertThat(command).isEqualTo(expected));
	}

	private PaymentConfirmationCommand expectedGatewayCommand(UUID operationUid) {
		return new PaymentConfirmationCommand(
			operationUid,
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			"airbob-confirm-" + operationUid
		);
	}

	private void assertForbiddenResponseHasNoDataOrDetails(MvcResult result) throws Exception {
		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(response.path("success").asBoolean()).isFalse();
		assertThat(fieldNames(response)).containsExactlyInAnyOrder("success", "error");
		assertThat(response.findValues("data")).isEmpty();
		assertThat(response.findValues("detail")).isEmpty();
		assertThat(response.findValues("operation_id")).isEmpty();
		assertThat(response.findValues("order_id")).isEmpty();
		assertThat(response.findValues("status_url")).isEmpty();
		assertThat(response.findValues("failure_code")).isEmpty();
		assertThat(response.findValues("updated_at")).isEmpty();
	}

	private List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private void assertNoSecret(String serialized) {
		assertThat(serialized)
			.doesNotContain(PAYMENT_KEY)
			.doesNotContain("paymentKey")
			.doesNotContain("payment_key");
	}

	private List<String> outboxEventTypes() {
		return outboxRepository.findAll().stream().map(Outbox::getEventType).toList();
	}

	private PersistenceSnapshot persistenceSnapshot() {
		List<PaymentOperation> operations = operationRepository.findAll();
		PaymentOperation operation = operations.size() == 1 ? operations.getFirst() : null;
		var reservation = reservationRepository.findById(reservationId).orElseThrow();
		return new PersistenceSnapshot(
			operations.size(),
			operation == null ? null : operation.getStatus(),
			operation == null ? null : operation.getAttemptCount(),
			operation == null ? null : operation.getVersion(),
			operation == null ? null : operation.getUpdatedAt(),
			reservationRepository.count(),
			reservation.getStatus(),
			reservation.getUpdatedAt(),
			historyRepository.count(),
			paymentRepository.count(),
			transactionRepository.count(),
			outboxRepository.count()
		);
	}

	private PaymentGatewayResult approved() {
		ConfirmedPayment confirmed = new ConfirmedPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			AMOUNT,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			clock.instant(),
			null
		);
		return new PaymentGatewayResult.Approved(confirmed);
	}

	private boolean isCouponUsed() {
		return Boolean.TRUE.equals(jdbc.queryForObject(
			"SELECT used FROM member_coupon WHERE id = ?", Boolean.class, memberCouponId));
	}

	private void createOutboxFailureTrigger() {
		jdbc.execute("""
			CREATE TRIGGER task10_reject_outbox
			BEFORE INSERT ON outbox
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced outbox failure'
			""");
	}

	private void createLedgerFailureTrigger() {
		jdbc.execute("""
			CREATE TRIGGER task10_reject_payment_ledger
			BEFORE INSERT ON payment_transaction
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced ledger failure'
			""");
	}

	private void dropFailureTriggers() {
		jdbc.execute("DROP TRIGGER IF EXISTS task10_reject_outbox");
		dropLedgerFailureTrigger();
	}

	private void dropLedgerFailureTrigger() {
		jdbc.execute("DROP TRIGGER IF EXISTS task10_reject_payment_ledger");
	}

	private void clearFixtureRows() {
		jdbc.update("DELETE FROM payment_transaction");
		jdbc.update("DELETE FROM payment");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM outbox");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM coupon");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private void insertPendingReservationFixture() {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('task10-owner@test.com', 'task10-owner', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		ownerId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('task10-non-owner@test.com', 'task10-non-owner', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		nonOwnerId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", ownerId, ACCOMMODATION_UID.toString());
		long accommodationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, '2026-08-15', '2026-08-16',
			  '2026-08-15 15:00:00', '2026-08-16 11:00:00', 'UTC', 2, ?, 10000,
			  'PAYMENT_PENDING', 'TASK100001', NOW(6), '2026-08-14 00:01:00', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, ownerId, AMOUNT);
		reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO coupon (
			  discount_value, is_active, min_payment_price, usable_from, usable_until,
			  issue_start_at, issue_end_at, name, discount_type, total_quantity,
			  issued_quantity, updated_at
			) VALUES (
			  10000, true, 0, '2026-01-01', '2027-01-01',
			  '2026-01-01', '2027-01-01', 'task10 coupon', 'FIXED_AMOUNT', 1,
			  1, NOW(6)
			)
			""");
		long couponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO member_coupon (
			  member_id, coupon_id, used, used_at, reservation_id, updated_at
			) VALUES (?, ?, true, NOW(6), ?, NOW(6))
			""", ownerId, couponId, reservationId);
		memberCouponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private record PersistenceSnapshot(
		long operationCount,
		PaymentOperationStatus operationStatus,
		Integer operationAttemptCount,
		Long operationVersion,
		LocalDateTime operationUpdatedAt,
		long reservationCount,
		ReservationStatus reservationStatus,
		LocalDateTime reservationUpdatedAt,
		long reservationHistoryCount,
		long paymentCount,
		long ledgerCount,
		long outboxCount
	) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FlowTestConfiguration {
		@Bean
		AdjustableClock paymentOperationFlowClock() {
			return new AdjustableClock(NOW);
		}

		@Bean
		ObjectMapper paymentOperationFlowObjectMapper() {
			return new ObjectMapper()
				.findAndRegisterModules()
				.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		}

		@Bean
		PaymentOperationProperties paymentOperationFlowProperties() {
			return new PaymentOperationProperties(
				Duration.ofSeconds(30),
				Duration.ofSeconds(10),
				100,
				MAX_ATTEMPTS,
				RETRY_DELAY,
				RETRY_MAX_DELAY,
				Duration.ofSeconds(10)
			);
		}

		@Bean
		PaymentRetryBackoff paymentOperationFlowBackoff(PaymentOperationProperties properties) {
			return new PaymentRetryBackoff(
				properties.retryInitialDelay(), properties.retryMaxDelay());
		}

		@Bean
		ScriptedPaymentGateway paymentOperationFlowGateway() {
			return new ScriptedPaymentGateway();
		}
	}

	static final class AdjustableClock extends Clock {
		private Instant instant;

		AdjustableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	static final class ScriptedPaymentGateway implements PaymentConfirmationGateway {
		private final Deque<PaymentGatewayResult> confirmations = new ArrayDeque<>();
		private final Deque<PaymentGatewayResult> inquiries = new ArrayDeque<>();
		private final List<GatewayInvocation> invocations = new ArrayList<>();

		void reset() {
			confirmations.clear();
			inquiries.clear();
			invocations.clear();
		}

		void enqueueConfirm(PaymentGatewayResult result) {
			confirmations.addLast(result);
		}

		void enqueueInquiry(PaymentGatewayResult result) {
			inquiries.addLast(result);
		}

		List<String> calls() {
			return invocations.stream().map(GatewayInvocation::method).toList();
		}

		List<GatewayInvocation> invocations() {
			return List.copyOf(invocations);
		}

		List<PaymentConfirmationCommand> confirmCommands() {
			return commandsFor("confirm");
		}

		List<PaymentConfirmationCommand> inquiryCommands() {
			return commandsFor("inquire");
		}

		@Override
		public PaymentGatewayResult confirm(PaymentConfirmationCommand command) {
			validate(command);
			invocations.add(new GatewayInvocation("confirm", command));
			return next(confirmations, "confirm");
		}

		@Override
		public PaymentGatewayResult inquire(PaymentConfirmationCommand command) {
			validate(command);
			invocations.add(new GatewayInvocation("inquire", command));
			return next(inquiries, "inquire");
		}

		private List<PaymentConfirmationCommand> commandsFor(String method) {
			return invocations.stream()
				.filter(invocation -> invocation.method().equals(method))
				.map(GatewayInvocation::command)
				.toList();
		}

		private void validate(PaymentConfirmationCommand command) {
			if (!PAYMENT_KEY.equals(command.paymentKey())
				|| !RESERVATION_UID.toString().equals(command.orderId())
				|| command.amount() != AMOUNT
				|| command.operationUid() == null
				|| command.providerIdempotencyKey() == null) {
				throw new AssertionError("payment gateway received an uncorrelated command");
			}
		}

		private PaymentGatewayResult next(Deque<PaymentGatewayResult> results, String method) {
			PaymentGatewayResult result = results.pollFirst();
			if (result == null) {
				throw new AssertionError("unexpected gateway " + method + " call");
			}
			return result;
		}
	}

	private record GatewayInvocation(String method, PaymentConfirmationCommand command) {
	}
}
