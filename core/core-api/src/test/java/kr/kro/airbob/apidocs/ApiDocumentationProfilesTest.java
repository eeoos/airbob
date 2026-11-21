package kr.kro.airbob.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApiDocumentationProfilesTest {
	@Test
	void testAndUnconfiguredProfilesKeepDocumentationDisabled() {
		for (String profiles : new String[] {
			"test", "dev,test", "test,dev", "aws,test", "test,aws", "oci,test", "test,oci", "dev,aws,oci,test",
			"unconfigured"
		}) {
			new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.profiles.active=" + profiles)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).as(profiles).isFalse();
					assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled", Boolean.class)).as(profiles).isFalse();
				});
		}
	}

	@Test
	void developmentAndDeploymentProfilesEnableDocumentation() {
		for (String profiles : new String[] {"dev", "aws", "oci", "dev,aws", "aws,dev", "dev,oci", "oci,dev", "aws,oci"}) {
			new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.profiles.active=" + profiles)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).as(profiles).isTrue();
					assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled", Boolean.class)).as(profiles).isTrue();
				});
		}
	}
}
