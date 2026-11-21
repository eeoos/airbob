package kr.kro.airbob.cursor.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CursorParam {

	int defaultSize() default 20;

	int maxSize() default 50;

	String sizeParam() default "size";

	String cursorParam() default "cursor";

}
