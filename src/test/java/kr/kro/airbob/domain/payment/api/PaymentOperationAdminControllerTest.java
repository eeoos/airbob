package kr.kro.airbob.domain.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionReason;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.repository.PaymentOperationManualReviewQueryRepository;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewQueryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewResult;

@ExtendWith(MockitoExtension.class)
class PaymentOperationAdminControllerTest {

	private static final Long ADMIN_ID = 9L;
	private static final UUID OPERATION_UID =
		UUID.fromString("98283dcc-f24f-44b2-a877-d89983fb7e31");

	@Mock private PaymentOperationManualReviewQueryRepository queryRepository;
	@Mock private PaymentOperationManualReviewCommandService commandService;
	@Mock private MemberRepository memberRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		PaymentOperationManualReviewQueryService queryService =
			new PaymentOperationManualReviewQueryService(queryRepository);
		AdminAuthInterceptor adminAuthInterceptor = new AdminAuthInterceptor(memberRepository);
		mockMvc = MockMvcBuilders.standaloneSetup(
				new PaymentOperationAdminController(queryService, commandService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.addInterceptors(adminAuthInterceptor)
			.build();

		allowAdmin();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	void listsOnlySafeFieldsOldestFirstAndReportsHasMore() throws Exception {
		PaymentOperationManualReviewQueueItem first = new PaymentOperationManualReviewQueueItem(
			OPERATION_UID,
			PaymentOperationType.CONFIRM,
			5,
			2,
			Instant.parse("2026-08-17T00:00:00Z"),
			7L,
			true
		);
		PaymentOperationManualReviewQueueItem extra = new PaymentOperationManualReviewQueueItem(
			UUID.fromString("98283dcc-f24f-44b2-a877-d89983fb7e32"),
			PaymentOperationType.CANCEL,
			6,
			3,
			Instant.parse("2026-08-17T00:01:00Z"),
			8L,
			false
		);
		given(queryRepository.findOldest(2)).willReturn(List.of(first, extra));

		MvcResult result = mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review")
				.param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.has_more").value(true))
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].operation_uid").value(OPERATION_UID.toString()))
			.andExpect(jsonPath("$.data.items[0].operation_type").value("CONFIRM"))
			.andExpect(jsonPath("$.data.items[0].review_required_at").value("2026-08-17T00:00:00Z"))
			.andExpect(jsonPath("$.data.items[0].attempt_count").value(5))
			.andExpect(jsonPath("$.data.items[0].manual_review_count").value(2))
			.andExpect(jsonPath("$.data.items[0].version").value(7))
			.andExpect(jsonPath("$.data.items[0].available_actions[0]").value("REQUEST_RECONCILIATION"))
			.andExpect(jsonPath("$.data.items[0].available_actions[1]").value("MARK_NOT_PAID"))
			.andReturn();

		String responseBody = result.getResponse().getContentAsString();
		assertThat(responseBody)
			.doesNotContain(
				"reservation", "order", "payment_key", "paymentKey", "member", "amount",
				"reason", "evidence", "failure", "not_paid_resolution_eligible",
				"secret-payment-key-value");
		then(queryRepository).should().findOldest(2);
	}

	@Test
	void defaultsTheManualReviewQueueLimitToFifty() throws Exception {
		given(queryRepository.findOldest(51)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.has_more").value(false))
			.andExpect(jsonPath("$.data.items").isEmpty());

		then(queryRepository).should().findOldest(51);
	}

	@Test
	void rejectsManualReviewQueueLimitsOutsideOneToOneHundred() throws Exception {
		mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review").param("limit", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));
		mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review").param("limit", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(queryRepository).shouldHaveNoInteractions();
	}

	@Test
	void acceptsAnInquiryOnlyReconciliationRequest() throws Exception {
		given(commandService.requestReconciliation(OPERATION_UID, ADMIN_ID, 7L))
			.willReturn(new PaymentOperationManualReviewResult(
				OPERATION_UID, PaymentOperationStatus.QUEUED, 8L));

		mockMvc.perform(post(
				"/api/v1/admin/payment-operations/{operationId}/reconciliation", OPERATION_UID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"expected_version":7}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.operation_uid").value(OPERATION_UID.toString()))
			.andExpect(jsonPath("$.data.status").value("QUEUED"))
			.andExpect(jsonPath("$.data.version").value(8));

		then(commandService).should().requestReconciliation(OPERATION_UID, ADMIN_ID, 7L);
	}

	@Test
	void acceptsAnExplicitMarkNotPaidRequestWithClosedEvidence() throws Exception {
		given(commandService.markNotPaid(
			OPERATION_UID,
			ADMIN_ID,
			7L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"toss-dashboard/case:ABC_123-4.5"
		)).willReturn(new PaymentOperationManualReviewResult(
			OPERATION_UID, PaymentOperationStatus.DECLINED, 8L));

		mockMvc.perform(post(
				"/api/v1/admin/payment-operations/{operationId}/mark-not-paid", OPERATION_UID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "expected_version":7,
					  "reason_code":"PROVIDER_PAYMENT_NOT_FOUND",
					  "evidence_reference":"toss-dashboard/case:ABC_123-4.5"
					}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.operation_uid").value(OPERATION_UID.toString()))
			.andExpect(jsonPath("$.data.status").value("DECLINED"))
			.andExpect(jsonPath("$.data.version").value(8));

		then(commandService).should().markNotPaid(
			OPERATION_UID,
			ADMIN_ID,
			7L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"toss-dashboard/case:ABC_123-4.5"
		);
	}

	@Test
	void rejectsUnsafeMarkNotPaidEvidenceBeforeCallingTheCommand() throws Exception {
		mockMvc.perform(post(
				"/api/v1/admin/payment-operations/{operationId}/mark-not-paid", OPERATION_UID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "expected_version":7,
					  "reason_code":"PROVIDER_PAYMENT_NOT_FOUND",
					  "evidence_reference":"https://provider.test/case?id=secret"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(commandService).shouldHaveNoInteractions();
	}

	@Test
	void rejectsAnUnknownMarkNotPaidReasonBeforeCallingTheCommand() throws Exception {
		mockMvc.perform(post(
				"/api/v1/admin/payment-operations/{operationId}/mark-not-paid", OPERATION_UID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "expected_version":7,
					  "reason_code":"ADMIN_DECIDED_TO_MARK_PAID",
					  "evidence_reference":"case/ABC-123"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(commandService).shouldHaveNoInteractions();
	}

	@Test
	void rejectsUnauthenticatedAndNonAdminRequestsButAllowsAnAdmin() throws Exception {
		UserContext.clear();
		mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("M006"));

		UserContext.set(new UserInfo(10L));
		given(memberRepository.existsByIdAndStatusAndRole(
			10L, MemberStatus.ACTIVE, MemberRole.ADMIN)).willReturn(false);
		mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("M006"));

		UserContext.set(new UserInfo(ADMIN_ID));
		given(queryRepository.findOldest(51)).willReturn(List.of());
		mockMvc.perform(get("/api/v1/admin/payment-operations/manual-review"))
			.andExpect(status().isOk());
	}

	private void allowAdmin() {
		UserContext.set(new UserInfo(ADMIN_ID));
		given(memberRepository.existsByIdAndStatusAndRole(
			ADMIN_ID, MemberStatus.ACTIVE, MemberRole.ADMIN)).willReturn(true);
	}
}
