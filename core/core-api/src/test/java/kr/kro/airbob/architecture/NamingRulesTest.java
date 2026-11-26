package kr.kro.airbob.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Naming rules for application code. See docs/development/coding-convention.md ("이름").
 * {@link NamingRulesFixtureTest} checks that the rules with their own predicates or conditions report the names
 * they are meant to catch; a rule scoped to a package takes it as a parameter so the fixtures can use theirs.
 * Test code is checked by {@link TestNamingRulesTest}, and test method names by a Spotless lint (build.gradle).
 */
@AnalyzeClasses(packages = ArchitectureSlices.ROOT_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class NamingRulesTest {

	static final String CORE_DOMAIN = "kr.kro.airbob.core.domain..";

	private static final String OPTIONAL = "java.util.Optional";
	private static final Set<String> BOOLEANS = Set.of("boolean", "java.lang.Boolean");
	private static final String SWAGGER_TAG = "io.swagger.v3.oas.annotations.tags.Tag";
	private static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
	private static final String INTEGRATION_EVENT = "kr.kro.airbob.core.support.messaging.event.IntegrationEvent";
	/**
	 * Query verbs other than get. A @Service method name alone does not tell a query, so the business layer is checked
	 * against this list and review covers other verbs; @GetMapping methods are checked for get directly.
	 */
	private static final String QUERY_VERBS_OTHER_THAN_GET = "(find|read|search|list|load|fetch|retrieve|query|select|lookup)([A-Z0-9].*)?";
	/** Three capitals in a row: an abbreviation written in capitals (HTTPClient, getURL, mySQLContainer). */
	private static final Pattern CAPITALIZED_ABBREVIATION = Pattern.compile("[A-Z]{3,}");
	/**
	 * Constants, static final fields written in capitals (MAX_URL_LENGTH, enum constants), are left out. A static final
	 * field named in camel case, such as a test container, and a field that is not static final are checked.
	 */
	private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

	@ArchTest
	static final ArchRule readerMethods = readerMethodsIn(CORE_DOMAIN);

	@ArchTest
	static final ArchRule queriesUseGet = queriesUseGetIn(CORE_DOMAIN);

	@ArchTest
	static final ArchRule getEndpointsStartWithGet = methods()
		.that().areAnnotatedWith(GET_MAPPING)
		.should().haveNameMatching("get([A-Z0-9].*)?")
		.as("@GetMapping methods should be named get...");

	@ArchTest
	static final ArchRule componentsHaveSpecificNames = componentsHaveSpecificNamesIn(CORE_DOMAIN);

	@ArchTest
	static final ArchRule configurationNaming = classes()
		.that().areAnnotatedWith(Configuration.class)
		.should().haveSimpleNameEndingWith("Configuration")
		.as("@Configuration classes should be named *Configuration");

	@ArchTest
	static final ArchRule noDtoSuffix = noClasses()
		.should().haveSimpleNameEndingWith("Dto").orShould().haveSimpleNameEndingWith("DTO")
		.as("classes should not be named *Dto; name the role (Request, Response, Condition, ...)");

	@ArchTest
	static final ArchRule controllerNaming = classes()
		.that().areAnnotatedWith(RestController.class)
		.should().haveSimpleNameEndingWith("Controller")
		.as("@RestController classes should be named *Controller");

	@ArchTest
	static final ArchRule swaggerInterfaceNaming = classes()
		.that().areInterfaces().and().areAnnotatedWith(SWAGGER_TAG)
		.should().haveSimpleNameEndingWith("Api")
		.as("Swagger interfaces (@Tag) should be named *Api");

	@ArchTest
	static final ArchRule schedulerNaming = classes()
		.that(DescribedPredicate.describe("declare @Scheduled methods", ArchitectureSlices::isScheduler))
		.should().haveSimpleNameEndingWith("Scheduler")
		.as("classes with @Scheduled methods should be named *Scheduler");

	@ArchTest
	static final ArchRule listenerNaming = classes()
		.that(DescribedPredicate.describe("are Kafka listeners", ArchitectureSlices::isKafkaListener))
		.should().haveSimpleNameEndingWith("Listener")
		.as("@KafkaListener classes should be named *Listener");

	@ArchTest
	static final ArchRule exceptionNaming = classes()
		.that(areExceptions()).and().areNotAnonymousClasses()
		.should().haveSimpleNameEndingWith("Exception")
		.as("exceptions should be named *Exception");

	@ArchTest
	static final ArchRule requestNaming = classes()
		.that().resideInAPackage("..request..").and().areTopLevelClasses()
		.should().haveSimpleNameEndingWith("Request")
		.as("top-level classes in request packages should be named *Request");

	@ArchTest
	static final ArchRule responseNaming = classes()
		.that().resideInAPackage("..response..").and().areTopLevelClasses()
		.should().haveSimpleNameEndingWith("Response")
		.as("top-level classes in response packages should be named *Response");

	@ArchTest
	static final ArchRule integrationEventNaming = classes()
		.that().implement(INTEGRATION_EVENT)
		.should().haveNameMatching(".*[.$][A-Z][A-Za-z0-9]*[a-z]V[1-9][0-9]*")
		.as("integration events should be named <subject><past participle>V<version>, such as "
			+ "AccommodationSearchRefreshRequestedV1");

	@ArchTest
	static final ArchRule abbreviations = abbreviationsIn(DescribedPredicate.alwaysTrue());

	/**
	 * Reader methods that other classes use: read returns a required value or a list (not void), find returns an Optional,
	 * and checks start with is, exists, has or count. Methods called only from inside the class or from tests,
	 * such as a test entry point or a factory for tests, are not part of the reader's vocabulary.
	 */
	static ArchRule readerMethodsIn(String domainPackage) {
		return methods()
			.that().areDeclaredInClassesThat().resideInAPackage(domainPackage)
			.and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Reader")
			.and(areUsedOutsideTheirClass())
			.should(useAReaderVerbForTheirResult())
			.as("Reader methods used by other classes should use read (required value or list), find (Optional), "
				+ "is/exists/has (boolean), count or search");
	}

	static ArchRule queriesUseGetIn(String domainPackage) {
		return noMethods()
			.that().arePublic()
			.and().areDeclaredInClassesThat(JavaClass.Predicates.resideInAPackage(domainPackage)
				.and(annotatedWith(Service.class))
				.or(annotatedWith(RestController.class))
				.or(JavaClass.Predicates.INTERFACES.and(annotatedWith(SWAGGER_TAG))))
			.should().haveNameMatching(QUERY_VERBS_OTHER_THAN_GET)
			.as("public @Service, @RestController and Swagger interface methods should name queries with get");
	}

	static ArchRule componentsHaveSpecificNamesIn(String domainPackage) {
		return noClasses()
			.that().resideInAPackage(domainPackage).and().areAnnotatedWith(Component.class)
			.should().haveNameMatching(".*(Helper|Util|Utils|Common|Misc|Etc)")
			.as("@Component classes in core.domain should name their role, not Helper, Util(s), Common, Misc or Etc");
	}

	static ArchRule abbreviationsIn(DescribedPredicate<? super JavaClass> scope) {
		return classes()
			.that(DescribedPredicate.not(generatedQueryTypes())).and(scope)
			.should(spellAbbreviationsAsWords())
			.as("class, method and field names should capitalize only the first letter of an abbreviation "
				+ "(HttpApiUrl, not HTTPApiURL)");
	}

	private static DescribedPredicate<JavaMethod> areUsedOutsideTheirClass() {
		return DescribedPredicate.describe("are public or used by another class", method -> !method.getModifiers()
			.contains(JavaModifier.SYNTHETIC)
			&& (method.getModifiers().contains(JavaModifier.PUBLIC)
				|| method.getAccessesToSelf().stream()
					.anyMatch(access -> !isInside(access.getOriginOwner(), method.getOwner()))));
	}

	/** Whether the class is the owner or nested in it, such as a lambda or a record declared in the reader. */
	private static boolean isInside(JavaClass javaClass, JavaClass owner) {
		Optional<JavaClass> current = Optional.of(javaClass);
		while (current.isPresent()) {
			if (current.get().equals(owner)) {
				return true;
			}
			current = current.get().getEnclosingClass();
		}
		return false;
	}

	private static ArchCondition<JavaMethod> useAReaderVerbForTheirResult() {
		return new ArchCondition<>("use a Reader verb that matches the result") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				String verb = method.getName().replaceFirst("^([a-z]+).*$", "$1");
				String result = method.getRawReturnType().getName();
				String problem = switch (verb) {
					case "read" -> result.equals(OPTIONAL) ? "read returns Optional; name it find"
						: result.equals("void") ? "read returns nothing" : null;
					case "find" -> result.equals(OPTIONAL) ? null : "find returns " + result + "; find is for Optional";
					case "is", "exists", "has" -> BOOLEANS.contains(result) ? null : verb + " returns " + result;
					case "count", "search" -> null;
					default -> "'" + verb + "' is not a Reader verb";
				};
				events.add(new SimpleConditionEvent(method, problem == null,
					method.getFullName() + (problem == null ? " uses a Reader verb" : ": " + problem)));
			}
		};
	}

	private static ArchCondition<JavaClass> spellAbbreviationsAsWords() {
		return new ArchCondition<>("not have three capitals in a row in their names") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				if (!javaClass.isAnonymousClass()) {
					report(javaClass, javaClass.getSimpleName(), javaClass.getName(), events);
				}
				for (JavaMethod method : javaClass.getMethods()) {
					if (!method.getModifiers().contains(JavaModifier.SYNTHETIC) && !method.getName().contains("$")) {
						report(method, method.getName(), method.getFullName(), events);
					}
				}
				for (JavaField field : javaClass.getFields()) {
					boolean constant = field.getModifiers().containsAll(Set.of(JavaModifier.STATIC, JavaModifier.FINAL))
						&& (CONSTANT_NAME.matcher(field.getName()).matches()
							|| field.getName().equals("serialVersionUID"));
					if (!constant && !field.getModifiers().contains(JavaModifier.SYNTHETIC)
						&& !field.getName().contains("$")) {
						report(field, field.getName(), field.getFullName(), events);
					}
				}
			}

			private void report(Object item, String name, String description, ConditionEvents events) {
				if (CAPITALIZED_ABBREVIATION.matcher(name).find()) {
					events
						.add(SimpleConditionEvent.violated(item, description + " writes an abbreviation in capitals"));
				}
			}
		};
	}

	/**
	 * Library classes are not imported (archunit.properties), so the superclass chain stops at the first library
	 * type, such as RuntimeException or IllegalStateException. That type is loaded from the test classpath. Errors
	 * (AssertionError, ...) are not exceptions and keep their own names.
	 */
	static DescribedPredicate<JavaClass> areExceptions() {
		return DescribedPredicate.describe("are exceptions", javaClass -> {
			Optional<JavaClass> superclass = javaClass.getRawSuperclass();
			while (superclass.isPresent() && superclass.get().isFullyImported()) {
				superclass = superclass.get().getRawSuperclass();
			}
			return superclass.map(library -> isException(library.getName())).orElse(false);
		});
	}

	private static boolean isException(String className) {
		try {
			return Exception.class
				.isAssignableFrom(Class.forName(className, false, NamingRulesTest.class.getClassLoader()));
		} catch (ClassNotFoundException notOnTheClasspath) {
			return false;
		}
	}

	private static DescribedPredicate<JavaClass> generatedQueryTypes() {
		return DescribedPredicate.describe("generated QueryDSL Q-types", ArchitectureSlices::isGeneratedQueryType);
	}

	static JavaClass topLevelOf(JavaClass javaClass) {
		JavaClass topLevel = javaClass;
		while (topLevel.getEnclosingClass().isPresent()) {
			topLevel = topLevel.getEnclosingClass().get();
		}
		return topLevel;
	}
}
