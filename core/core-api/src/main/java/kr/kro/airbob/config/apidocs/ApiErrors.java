package kr.kro.airbob.config.apidocs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import kr.kro.airbob.common.exception.ErrorCode;

/** Major client-visible failures; values and examples come from the application's error catalog. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrors {

	ErrorCode[] value();
}
