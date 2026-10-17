package kr.kro.airbob.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = NotBlankWhenPresent.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotBlankWhenPresent {
	String message() default "값이 있으면 공백만으로 구성할 수 없습니다.";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<NotBlankWhenPresent, CharSequence> {
		@Override
		public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
			return value == null || value.toString().codePoints()
				.anyMatch(character -> !Character.isWhitespace(character) && !Character.isSpaceChar(character));
		}
	}
}
