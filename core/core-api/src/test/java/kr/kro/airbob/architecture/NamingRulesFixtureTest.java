package kr.kro.airbob.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.core.support.messaging.event.IntegrationEvent;

/**
 * Checks that the naming rules with their own predicates or conditions report the names they are meant to catch
 * and pass the names they allow. Rules scoped to a package run on this package. The fixtures below break the rules
 * on purpose, so {@link TestNamingRulesTest} leaves this class out; Spring's test scanning skips classes nested in
 * test classes.
 */
class NamingRulesFixtureTest {

	private static final String FIXTURE_PACKAGE = "kr.kro.airbob.architecture..";

	@Test
	void readerRuleReportsVerbsThatDoNotMatchTheResult() {
		ArchRule rule = NamingRulesTest.readerMethodsIn(FIXTURE_PACKAGE);

		assertThat(hasViolation(rule, ListFindingReader.class)).isTrue();
		assertThat(hasViolation(rule, OptionalReadingReader.class)).isTrue();
		assertThat(hasViolation(rule, FetchingReader.class)).isTrue();
		assertThat(hasViolation(rule, TextCheckingReader.class)).isTrue();
		assertThat(hasViolation(rule, LoadingReader.class, LoadingReaderCaller.class)).isTrue();
	}

	@Test
	void readerRuleAllowsMatchingVerbsAndMethodsUsedOnlyInsideTheReader() {
		ArchRule rule = NamingRulesTest.readerMethodsIn(FIXTURE_PACKAGE);

		assertThat(hasViolation(rule, WellNamedReader.class, WellNamedReaderCaller.class)).isFalse();
	}

	@Test
	void queryRuleReportsQueryVerbsOtherThanGet() {
		ArchRule rule = NamingRulesTest.queriesUseGetIn(FIXTURE_PACKAGE);

		assertThat(hasViolation(rule, FindingService.class)).isTrue();
		assertThat(hasViolation(rule, SearchingController.class)).isTrue();
		assertThat(hasViolation(rule, LoadingApi.class)).isTrue();
		assertThat(hasViolation(rule, GettingService.class)).isFalse();
	}

	@Test
	void componentRuleReportsVagueSuffixes() {
		ArchRule rule = NamingRulesTest.componentsHaveSpecificNamesIn(FIXTURE_PACKAGE);

		assertThat(hasViolation(rule, AccommodationHelper.class)).isTrue();
		assertThat(hasViolation(rule, AccommodationValidator.class)).isFalse();
	}

	@Test
	void configurationRuleReportsConfigSuffix() {
		assertThat(hasViolation(NamingRulesTest.configurationNaming, RedisConfig.class)).isTrue();
		assertThat(hasViolation(NamingRulesTest.configurationNaming, RedisConfiguration.class)).isFalse();
	}

	@Test
	void entryPointRulesReadMethodAnnotations() {
		assertThat(hasViolation(NamingRulesTest.schedulerNaming, CleanupJob.class)).isTrue();
		assertThat(hasViolation(NamingRulesTest.listenerNaming, PaymentConsumer.class)).isTrue();
	}

	@Test
	void exceptionRuleFollowsTheSuperclassesToTheJdk() {
		assertThat(hasViolation(NamingRulesTest.exceptionNaming, PaymentFailure.class)).isTrue();
		assertThat(hasViolation(NamingRulesTest.exceptionNaming, PaymentFailedException.class)).isFalse();
	}

	/** The rule matches implementations of the imported interface, as in the application import. */
	@Test
	void integrationEventRuleRequiresAVersion() {
		ArchRule rule = NamingRulesTest.integrationEventNaming;

		assertThat(hasViolation(rule, PaymentCompleted.class, IntegrationEvent.class)).isTrue();
		assertThat(hasViolation(rule, PaymentCompletedV1.class, IntegrationEvent.class)).isFalse();
	}

	@Test
	void abbreviationRuleReportsCapitalizedAbbreviations() {
		ArchRule rule = NamingRulesTest.abbreviationsIn(DescribedPredicate.alwaysTrue());

		assertThat(hasViolation(rule, HTTPClientFixture.class)).isTrue();
		assertThat(hasViolation(rule, UrlField.class)).isTrue();
		assertThat(hasViolation(rule, UrlMethod.class)).isTrue();
		assertThat(hasViolation(rule, UrlContainerField.class)).isTrue();
		assertThat(hasViolation(rule, WellSpelledNames.class)).isFalse();
	}

	@Test
	void testClassRuleReportsTestClassesWithoutTheSuffix() {
		ArchRule rule = TestNamingRulesTest.testClassNamingIn(DescribedPredicate.alwaysTrue());

		assertThat(hasViolation(rule, ChecksWithoutSuffix.class)).isTrue();
		assertThat(hasViolation(rule, ChecksTest.class)).isFalse();
	}

	private static boolean hasViolation(ArchRule rule, Class<?>... fixtures) {
		return rule.evaluate(new ClassFileImporter().importClasses(fixtures)).hasViolation();
	}

	static class ListFindingReader {
		public List<String> findNames() {
			return List.of();
		}
	}

	static class OptionalReadingReader {
		public Optional<String> readName() {
			return Optional.empty();
		}
	}

	static class FetchingReader {
		public String fetchName() {
			return "";
		}
	}

	static class TextCheckingReader {
		public String hasName() {
			return "";
		}
	}

	static class LoadingReader {
		List<String> loadNames() {
			return List.of();
		}
	}

	static class LoadingReaderCaller {
		List<String> names(LoadingReader reader) {
			return reader.loadNames();
		}
	}

	static class WellNamedReader {
		static WellNamedReader withTicker() {
			return new WellNamedReader();
		}

		public String read() {
			return buildQuery();
		}

		public List<String> readAll() {
			return List.of();
		}

		public Optional<String> find() {
			return Optional.empty();
		}

		public boolean exists() {
			return false;
		}

		public long count() {
			return 0;
		}

		String buildQuery() {
			return "";
		}
	}

	static class WellNamedReaderCaller {
		String name(WellNamedReader reader) {
			return reader.read();
		}
	}

	@Service
	static class FindingService {
		public String findName() {
			return "";
		}
	}

	@RestController
	static class SearchingController {
		public String searchNames() {
			return "";
		}
	}

	@Tag(name = "fixture")
	interface LoadingApi {
		String loadName();
	}

	@Service
	static class GettingService {
		public String getName() {
			return findCachedName();
		}

		public void createName() {}

		private String findCachedName() {
			return "";
		}
	}

	@Component
	static class AccommodationHelper {}

	@Component
	static class AccommodationValidator {}

	@Configuration(proxyBeanMethods = false)
	static class RedisConfig {}

	@Configuration(proxyBeanMethods = false)
	static class RedisConfiguration {}

	static class CleanupJob {
		@Scheduled(fixedDelay = 1000)
		void run() {}
	}

	static class PaymentConsumer {
		@KafkaListener(topics = "fixture")
		void consume(String message) {}
	}

	static class PaymentFailure extends RuntimeException {}

	static class PaymentFailedException extends IllegalStateException {}

	abstract static class PaymentCompleted implements IntegrationEvent {}

	abstract static class PaymentCompletedV1 implements IntegrationEvent {}

	static class HTTPClientFixture {}

	static class UrlField {
		String httpURL;
	}

	static class UrlContainerField {
		private static final Object sharedURL = new Object();
	}

	static class UrlMethod {
		String toURL() {
			return "";
		}
	}

	static class WellSpelledNames {
		private static final long serialVersionUID = 1L;
		static final String MAX_URL_LENGTH = "2048";
		String httpUrl;

		String toUrl() {
			return httpUrl;
		}
	}

	/** Private, so JUnit does not run it. */
	private static class ChecksWithoutSuffix {
		@Test
		void checks() {}
	}

	private static class ChecksTest {
		@Test
		void checks() {}
	}
}
