package kr.kro.airbob.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Strict layer rules for code already in the template layout ({@code kr.kro.airbob.core..}).
 * Presentation ({@code core.api}) → Business ({@code @Service}) → Implement ({@code @Component})
 * → Data access (repositories). See docs/architecture/code-structure.md.
 * Rules over packages that are still empty pass until the first domain moves (allowEmptyShould).
 */
@AnalyzeClasses(
	packages = ArchitectureSlices.ROOT_PACKAGE,
	importOptions = ImportOption.DoNotIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class LayeredArchitectureTest {

	private static final String CORE_API = "kr.kro.airbob.core.api..";
	private static final String CORE_DOMAIN = "kr.kro.airbob.core.domain..";
	private static final String CORE_SUPPORT = "kr.kro.airbob.core.support..";

	@ArchTest
	static final ArchRule domainDoesNotDependOnPresentation = noClasses()
		.that().resideInAPackage(CORE_DOMAIN)
		.should().dependOnClassesThat().resideInAPackage(CORE_API)
		.allowEmptyShould(true)
		.as("core.domain should not depend on core.api");

	@ArchTest
	static final ArchRule presentationCallsOnlyBusiness = noClasses()
		.that().resideInAPackage(CORE_API)
		.should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage(CORE_DOMAIN)
			.and(annotatedWith(Component.class).or(ArchitectureSlices.repositories())))
		.allowEmptyShould(true)
		.as("core.api should call core.domain only through @Service business classes");

	@ArchTest
	static final ArchRule businessDoesNotCallBusiness = noClasses()
		.that().resideInAPackage(CORE_DOMAIN).and().areAnnotatedWith(Service.class)
		.should().dependOnClassesThat().areAnnotatedWith(Service.class)
		.allowEmptyShould(true)
		.as("@Service business classes in core.domain should not depend on other @Service classes");

	@ArchTest
	static final ArchRule businessDoesNotSkipToDataAccess = noClasses()
		.that().resideInAPackage(CORE_DOMAIN).and().areAnnotatedWith(Service.class)
		.should().dependOnClassesThat(ArchitectureSlices.repositories())
		.allowEmptyShould(true)
		.as("@Service business classes in core.domain should reach repositories through implement components");

	@ArchTest
	static final ArchRule implementDoesNotCallBusiness = noClasses()
		.that().resideInAPackage(CORE_DOMAIN).and().areAnnotatedWith(Component.class)
		.should().dependOnClassesThat().areAnnotatedWith(Service.class)
		.allowEmptyShould(true)
		.as("@Component implement classes in core.domain should not depend on @Service classes");

	/**
	 * Rule 7 for the flat core.domain packages, where entities share a package with business and implement
	 * classes and the frozen package-name check (dto/api/service) cannot tell them apart.
	 */
	@ArchTest
	static final ArchRule entitiesDoNotDependOnBusinessOrImplement = noClasses()
		.that(JavaClass.Predicates.resideInAPackage(CORE_DOMAIN).and(
			annotatedWith("jakarta.persistence.Entity")
				.or(annotatedWith("jakarta.persistence.Embeddable"))
				.or(annotatedWith("jakarta.persistence.MappedSuperclass"))))
		.should().dependOnClassesThat(annotatedWith(Service.class).or(annotatedWith(Component.class)))
		.allowEmptyShould(true)
		.as("entities in core.domain should not depend on @Service or @Component classes");

	@ArchTest
	static final ArchRule businessNaming = classes()
		.that().resideInAPackage(CORE_DOMAIN).and().areAnnotatedWith(Service.class)
		.should().haveSimpleNameEndingWith("Service")
		.allowEmptyShould(true)
		.as("@Service classes in core.domain should be named *Service");

	@ArchTest
	static final ArchRule implementNaming = classes()
		.that().resideInAPackage(CORE_DOMAIN).and().areAnnotatedWith(Component.class)
		.should().haveSimpleNameNotEndingWith("Service")
		.allowEmptyShould(true)
		.as("@Component implement classes in core.domain should not be named *Service");

	@ArchTest
	static final ArchRule supportIsDomainAgnostic = noClasses()
		.that().resideInAPackage(CORE_SUPPORT)
		.should().dependOnClassesThat().resideInAnyPackage(
			"kr.kro.airbob.domain..", CORE_DOMAIN, CORE_API, "kr.kro.airbob.search..",
			"kr.kro.airbob.messaging..", "kr.kro.airbob.config..")
		.as("core.support should not depend on domains, presentation, or configuration");
}
