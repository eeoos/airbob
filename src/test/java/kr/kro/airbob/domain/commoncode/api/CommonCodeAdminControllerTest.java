package kr.kro.airbob.domain.commoncode.api;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupResponse;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupDuplicateException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.service.CommonCodeAdminService;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 관리자 API 테스트")
class CommonCodeAdminControllerTest {

	@Mock
	private CommonCodeAdminService adminService;

	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(new CommonCodeAdminController(adminService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@Test
	@DisplayName("관리자 그룹 목록을 200으로 반환한다")
	void getGroups() throws Exception {
		given(adminService.getGroups()).willReturn(List.of(
			new CommonCodeGroupResponse("AMENITY_TYPE", "편의시설", null, true)));

		mockMvc.perform(get("/api/v1/admin/common-code-groups"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].group_code").value("AMENITY_TYPE"))
			.andExpect(jsonPath("$.data[0].group_name").value("편의시설"));
	}

	@Test
	@DisplayName("관리자 그룹 생성은 201을 반환한다")
	void createGroup() throws Exception {
		CommonCodeGroupRequest.Create request = new CommonCodeGroupRequest.Create(
			"payment_method", "결제 수단", null, null);
		given(adminService.createGroup(request)).willReturn(
			new CommonCodeGroupResponse("PAYMENT_METHOD", "결제 수단", null, true));

		mockMvc.perform(post("/api/v1/admin/common-code-groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.group_code").value("PAYMENT_METHOD"))
			.andExpect(jsonPath("$.data.active").value(true));

		then(adminService).should().createGroup(request);
	}

	@Test
	@DisplayName("그룹 수정 경로를 대문자로 정규화하고 200을 반환한다")
	void updateGroup() throws Exception {
		CommonCodeGroupRequest.Update request = new CommonCodeGroupRequest.Update("새 이름", null, false);
		given(adminService.updateGroup("PAYMENT_METHOD", request)).willReturn(
			new CommonCodeGroupResponse("PAYMENT_METHOD", "새 이름", "기존 설명", false));

		mockMvc.perform(patch("/api/v1/admin/common-code-groups/payment_method")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.group_code").value("PAYMENT_METHOD"))
			.andExpect(jsonPath("$.data.group_name").value("새 이름"))
			.andExpect(jsonPath("$.data.active").value(false));

		then(adminService).should().updateGroup("PAYMENT_METHOD", request);
	}

	@Test
	@DisplayName("필수 그룹 생성 값이 없으면 C001 400을 반환한다")
	void rejectMissingCreateValues() throws Exception {
		mockMvc.perform(post("/api/v1/admin/common-code-groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(adminService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("허용되지 않는 그룹 코드 형식은 C001 400을 반환한다")
	void rejectMalformedGroupCode() throws Exception {
		mockMvc.perform(post("/api/v1/admin/common-code-groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"group_code\":\"PAYMENT-METHOD\",\"group_name\":\"결제 수단\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(adminService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("공백으로만 된 그룹 이름 수정은 C001 400을 반환한다")
	void rejectBlankUpdateName() throws Exception {
		mockMvc.perform(patch("/api/v1/admin/common-code-groups/PAYMENT_METHOD")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"group_name\":\"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(adminService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("중복 그룹 생성은 CC004 409를 반환한다")
	void rejectDuplicateGroup() throws Exception {
		CommonCodeGroupRequest.Create request = new CommonCodeGroupRequest.Create(
			"AMENITY_TYPE", "중복 그룹", null, true);
		given(adminService.createGroup(request)).willThrow(new CommonCodeGroupDuplicateException());

		mockMvc.perform(post("/api/v1/admin/common-code-groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CC004"));
	}

	@Test
	@DisplayName("존재하지 않는 그룹 수정은 CC001 404를 반환한다")
	void rejectMissingGroupUpdate() throws Exception {
		CommonCodeGroupRequest.Update request = new CommonCodeGroupRequest.Update("새 이름", null, null);
		given(adminService.updateGroup("MISSING_GROUP", request))
			.willThrow(new CommonCodeGroupNotFoundException());

		mockMvc.perform(patch("/api/v1/admin/common-code-groups/MISSING_GROUP")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("CC001"));
	}
}
