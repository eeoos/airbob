package kr.kro.airbob.domain.member;

import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.kro.airbob.domain.common.BaseControllerDocumentationTest;
import kr.kro.airbob.domain.member.api.MemberController;
import kr.kro.airbob.domain.member.dto.MemberRequestDto.SignupMemberRequestDto;
import kr.kro.airbob.domain.member.service.MemberService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(RestDocumentationExtension.class)
@AutoConfigureRestDocs
@WebMvcTest(MemberController.class)
public class MemberControllerTest extends BaseControllerDocumentationTest {

    @Autowired
    private MemberController memberController;
    @MockitoBean
    private MemberService memberService;

    @Override
    protected Object getController() {
        return memberController;
    }

    @Test
    @DisplayName("사용자가 새로운 계정을 생성한다")
    void createNewMember() throws Exception {
        // given
        SignupMemberRequestDto request = SignupMemberRequestDto.builder()
                .email("test@example.com")
                .password("securePassword123")
                .nickname("테스터")
                .thumbnailImageUrl("http://testImage.com")
                .build();

        // when
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
        // then
                .andExpect(status().isCreated())

        // REST Docs 문서화
                .andDo(document("회원가입-성공",
                        requestFields(
                                fieldWithPath("email").type(JsonFieldType.STRING).description("회원 이메일"),
                                fieldWithPath("password").type(JsonFieldType.STRING).description("회원 비밀번호"),
                                fieldWithPath("nickname").type(JsonFieldType.STRING).description("회원 닉네임"),
                                fieldWithPath("thumbnailImageUrl").type(JsonFieldType.STRING).description("썸네일 이미지 url")
                        )
                ));
    }
}
