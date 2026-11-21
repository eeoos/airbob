package kr.kro.airbob.domain.coupon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.service.CouponService;
import kr.kro.airbob.domain.coupon.service.CouponStockPreparationService;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, CouponService.class,
	CouponStockPreparationService.class, CouponIssueUnitStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponAdministrationIntegrationTest {

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_coupon_administration");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired CouponService service;
	@Autowired CouponStockPreparationService preparation;
	@Autowired CouponIssueUnitStore units;
	@Autowired JdbcTemplate jdbc;
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
		.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private MockMvc mvc;
	private LocalDateTime start;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM coupon_issue_unit");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM coupon");
		start = units.databaseNow().plusDays(1).withNano(123456000);
		mvc = MockMvcBuilders.standaloneSetup(new CouponAdminController(service, preparation))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
	}

	@Test
	void creationReturnsTheIdentityNeededToPrepareStock() throws Exception {
		String body = mvc.perform(post("/api/v1/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(valid().toString()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = mapper.readTree(body).at("/data/id").asLong();
		assertThat(id).isPositive();
		assertThat(jdbc.queryForObject("SELECT id FROM coupon", Long.class)).isEqualTo(id);
		assertThat(jdbc.queryForObject("SELECT stock_prepared_at FROM coupon WHERE id = ?", LocalDateTime.class, id)).isNull();
		mvc.perform(post("/api/v1/admin/coupons/{id}/stock/prepare", id)).andExpect(status().isOk());
		assertThat(units.count(id)).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "description"})
	void rejectsOversizedTextOnCreationWithoutWritingRows(String field) throws Exception {
		assertInvalidCreate(valid().put(field, "가".repeat(256)));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "description"})
	void rejectsOversizedPatchAndKeepsPreparedStockAndMetadata(String field) throws Exception {
		long id = create();
		preparation.prepare(id);
		ObjectNode patch = mapper.createObjectNode().put(field, "가".repeat(256)).put("total_quantity", 5);
		mvc.perform(patch("/api/v1/admin/coupons/{id}", id).contentType(MediaType.APPLICATION_JSON).content(patch.toString()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CP013"));
		assertThat(jdbc.queryForMap("SELECT name,description,total_quantity FROM coupon WHERE id = ?", id))
			.containsEntry("name", "쿠폰").containsEntry("description", "안내").containsEntry("total_quantity", 2);
		assertThat(units.count(id)).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0999-12-31T23:59:59Z", "+10000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z", "9999-12-31T23:59:59.999999500Z"})
	void rejectsDatesOutsideMysqlRangeBeforePersistence(String time) throws Exception {
		String field = time.startsWith("0999") ? "usable_from" : "usable_until";
		assertInvalidCreate(valid().put(field, time));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero();
		long id = create();
		mvc.perform(patch("/api/v1/admin/coupons/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.createObjectNode().put(field, time).toString()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CP013"));
	}

	@Test
	void validatesIntervalsAtThePrecisionActuallyStoredInMysql() throws Exception {
		ObjectNode request = valid().put("issue_start_at", start.plusNanos(100).toInstant(ZoneOffset.UTC).toString())
			.put("issue_end_at", start.plusNanos(200).toInstant(ZoneOffset.UTC).toString());
		assertInvalidCreate(request);
	}

	@Test
	void acceptsMaximumUnicodeTextAndRoundsSubMicrosecondInstants() throws Exception {
		ObjectNode request = valid().put("name", "😀".repeat(255)).put("description", "가".repeat(255))
			.put("issue_start_at", start.plusNanos(789).toInstant(ZoneOffset.UTC).toString());
		mvc.perform(post("/api/v1/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
			.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(name) FROM coupon", Integer.class)).isEqualTo(255);
		assertThat(jdbc.queryForObject("SELECT issue_start_at FROM coupon", LocalDateTime.class)).isEqualTo(start.plusNanos(1000));
	}

	@Test
	void patchNullsLeaveFieldsUnchangedAndStartedCampaignOnlyLocksIssuanceFields() throws Exception {
		long id = create();
		preparation.prepare(id);
		mvc.perform(patch("/api/v1/admin/coupons/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\":null,\"total_quantity\":null,\"max_discount_amount\":null}"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForMap("SELECT description,max_discount_amount FROM coupon WHERE id = ?", id))
			.containsEntry("description", "안내").containsEntry("max_discount_amount", 5000);
		jdbc.update("UPDATE coupon SET issue_start_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id = ?", id);
		mvc.perform(patch("/api/v1/admin/coupons/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"수정 이름\",\"description\":\"\",\"discount_value\":2000,\"total_quantity\":2}"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForMap("SELECT name,description,discount_value FROM coupon WHERE id = ?", id))
			.containsEntry("name", "수정 이름").containsEntry("description", "").containsEntry("discount_value", 2000);
		mvc.perform(patch("/api/v1/admin/coupons/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.createObjectNode().put("issue_end_at", start.plusDays(1).plusNanos(100).toInstant(ZoneOffset.UTC).toString()).toString()))
			.andExpect(status().isOk());
		for (String request : List.of("{\"total_quantity\":3}", "{\"is_active\":false}",
			"{\"issue_end_at\":\"" + start.plusDays(2).toInstant(ZoneOffset.UTC) + "\"}")) {
			mvc.perform(patch("/api/v1/admin/coupons/{id}", id).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CP014"));
		}
		mvc.perform(patch("/api/v1/admin/coupons/{id}/quantity", id).contentType(MediaType.APPLICATION_JSON).content("{\"total_quantity\":2}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CP014"));
		assertThat(units.count(id)).isEqualTo(2);
	}

	@Test
	void rejectsInvalidFinalConfigurationWithDomainError() throws Exception {
		for (ObjectNode request : List.of(mapper.createObjectNode(), valid().put("name", " "),
			valid().putNull("discount_type"), valid().put("discount_value", 0),
			valid().put("discount_type", "PERCENTAGE").put("discount_value", 101),
			valid().put("min_payment_price", -1), valid().put("max_discount_amount", -1),
			valid().putNull("is_active"), valid().putNull("total_quantity"), valid().put("total_quantity", 0),
			valid().put("issue_end_at", start.toInstant(ZoneOffset.UTC).toString()),
			valid().put("usable_until", start.toInstant(ZoneOffset.UTC).toString()),
			valid().put("usable_until", start.plusHours(1).toInstant(ZoneOffset.UTC).toString()),
			valid().put("issue_start_at", units.databaseNow().minusSeconds(1).toInstant(ZoneOffset.UTC).toString()))) {
			assertInvalidCreate(request);
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero();
	}

	@Test
	void storesSupportedDateBoundariesWithoutDatabaseTruncation() throws Exception {
		ObjectNode request = valid().put("usable_from", "1000-01-01T00:00:00Z")
			.put("usable_until", "9999-12-31T23:59:59.999999Z");
		mvc.perform(post("/api/v1/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
			.andExpect(status().isCreated());
		assertThat(jdbc.queryForObject("SELECT usable_from FROM coupon", LocalDateTime.class))
			.isEqualTo(LocalDateTime.parse("1000-01-01T00:00:00"));
		assertThat(jdbc.queryForObject("SELECT usable_until FROM coupon", LocalDateTime.class))
			.isEqualTo(LocalDateTime.parse("9999-12-31T23:59:59.999999"));
	}

	private long create() throws Exception {
		mvc.perform(post("/api/v1/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(valid().toString()))
			.andExpect(status().isCreated());
		return jdbc.queryForObject("SELECT MAX(id) FROM coupon", Long.class);
	}

	private void assertInvalidCreate(ObjectNode body) throws Exception {
		mvc.perform(post("/api/v1/admin/coupons").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CP013"));
	}

	private ObjectNode valid() {
		return mapper.createObjectNode().put("name", "쿠폰").put("description", "안내")
			.put("discount_type", "FIXED_AMOUNT").put("discount_value", 1000)
			.put("max_discount_amount", 5000).put("is_active", true).put("total_quantity", 2)
			.put("issue_start_at", start.toInstant(ZoneOffset.UTC).toString())
			.put("issue_end_at", start.plusDays(1).toInstant(ZoneOffset.UTC).toString())
			.put("usable_from", start.toInstant(ZoneOffset.UTC).toString())
			.put("usable_until", start.plusDays(30).toInstant(ZoneOffset.UTC).toString());
	}
}
