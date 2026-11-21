package kr.kro.airbob.domain.accommodation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

@DisplayName("Accommodation 엔티티 매핑 테스트")
class AccommodationMappingTest {

	@Test
	@DisplayName("여러 숙소가 한 회원을 소유자로 참조하므로 member는 지연 로딩 다대일 관계다")
	void member_연관관계는_지연_로딩_다대일이다() throws NoSuchFieldException {
		Field memberField = Accommodation.class.getDeclaredField("member");

		ManyToOne manyToOne = memberField.getAnnotation(ManyToOne.class);
		JoinColumn joinColumn = memberField.getAnnotation(JoinColumn.class);

		assertThat(manyToOne).isNotNull();
		assertThat(manyToOne.fetch()).isEqualTo(FetchType.LAZY);
		assertThat(manyToOne.optional()).isFalse();
		assertThat(joinColumn).isNotNull();
		assertThat(joinColumn.name()).isEqualTo("member_id");
		assertThat(joinColumn.nullable()).isFalse();
		assertThat(memberField.isAnnotationPresent(OneToOne.class)).isFalse();
	}
}
