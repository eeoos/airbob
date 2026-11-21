package kr.kro.airbob.domain.accommodation.entity;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PreRemove;

@DisplayName("AccommodationAmenity 삭제 생명주기 계약 특성 테스트")
class AccommodationAmenityLifecycleCharacterizationTest {

	@Test
	@DisplayName("entity 삭제 callback과 cascade에 의존하지 않는다")
	void hasNoDeleteCallbackOrCascadeContract() throws Exception {
		Method[] methods = AccommodationAmenity.class.getDeclaredMethods();
		assertThat(Arrays.stream(methods).anyMatch(method -> method.isAnnotationPresent(PreRemove.class)))
			.isFalse();
		assertThat(Arrays.stream(methods).anyMatch(method -> method.isAnnotationPresent(PostRemove.class)))
			.isFalse();
		assertThat(Arrays.stream(AccommodationAmenity.class.getSuperclass().getDeclaredMethods())
			.anyMatch(method -> method.isAnnotationPresent(PreRemove.class)
				|| method.isAnnotationPresent(PostRemove.class)))
			.isFalse();
		EntityListeners listeners = AccommodationAmenity.class.getSuperclass()
			.getAnnotation(EntityListeners.class);
		assertThat(listeners.value()).containsExactly(AuditingEntityListener.class);
		assertThat(Arrays.stream(AuditingEntityListener.class.getDeclaredMethods())
			.anyMatch(method -> method.isAnnotationPresent(PreRemove.class)
				|| method.isAnnotationPresent(PostRemove.class)))
			.isFalse();

		Field accommodation = AccommodationAmenity.class.getDeclaredField("accommodation");
		ManyToOne relation = accommodation.getAnnotation(ManyToOne.class);
		assertThat(relation).isNotNull();
		assertThat(relation.cascade()).isEmpty();
	}
}
