package kr.kro.airbob.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.all;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.AbstractClassesTransformer;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * Dependency rules over the whole application, frozen at their current violations.
 * Existing violations are recorded in core/core-api/src/test/resources/archunit_store; a new violation
 * fails the build, and a fixed one is removed from the store on the next run (commit the shrunk store).
 * Violation messages name classes and domains only, so unrelated edits (for example a new constructor
 * parameter) do not re-key recorded violations.
 */
@AnalyzeClasses(
	packages = ArchitectureSlices.ROOT_PACKAGE,
	importOptions = ImportOption.DoNotIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class LegacyDependencyFreezeTest {

	@ArchTest
	static final ArchRule repositoriesStayInsideTheirDomain = FreezingArchRule.freeze(
		classes().should(notUseRepositoriesOfAnotherDomain())
			.as("Classes should not use repositories owned by another domain"));

	@ArchTest
	static final ArchRule domainsAreFreeOfCycles = FreezingArchRule.freeze(
		all(new DomainDependencyEdges()).should(notBePartOfACycle())
			.as("Domain dependencies should be free of cycles"));

	@ArchTest
	static final ArchRule persistenceModelsDoNotDependOnWebOrServiceTypes = FreezingArchRule.freeze(
		classes().that(persistenceModels()).should(notDependOnWebOrServiceTypes())
			.as("Entities and embeddables should not depend on web DTOs, controllers, or services"));

	private static ArchCondition<JavaClass> notUseRepositoriesOfAnotherDomain() {
		return new ArchCondition<>("not use repositories owned by another domain") {
			@Override
			public void check(JavaClass origin, ConditionEvents events) {
				if (ArchitectureSlices.isGeneratedQueryType(origin)) {
					return;
				}
				String owner = ArchitectureSlices.ownerOf(origin);
				targetsOf(origin).stream()
					.filter(ArchitectureSlices::isRepository)
					.filter(target -> !owner.equals(ArchitectureSlices.ownerOf(target)))
					.map(JavaClass::getName)
					.forEach(target -> events.add(SimpleConditionEvent.violated(
						origin, origin.getName() + " uses repository " + target)));
			}
		};
	}

	private static DescribedPredicate<JavaClass> persistenceModels() {
		return DescribedPredicate.describe("are entities or embeddables", javaClass ->
			javaClass.isAnnotatedWith("jakarta.persistence.Entity")
				|| javaClass.isAnnotatedWith("jakarta.persistence.Embeddable")
				|| javaClass.isAnnotatedWith("jakarta.persistence.MappedSuperclass"));
	}

	private static ArchCondition<JavaClass> notDependOnWebOrServiceTypes() {
		return new ArchCondition<>("not depend on web DTOs, controllers, or services") {
			@Override
			public void check(JavaClass origin, ConditionEvents events) {
				targetsOf(origin).stream()
					.filter(ArchitectureSlices::isApplicationClass)
					.filter(target -> isWebOrServiceType(target.getPackageName()))
					.map(JavaClass::getName)
					.forEach(target -> events.add(SimpleConditionEvent.violated(
						origin, origin.getName() + " depends on " + target)));
			}
		};
	}

	private static boolean isWebOrServiceType(String packageName) {
		return packageName.matches(".*\\.(dto|api|service)(\\..*)?")
			|| packageName.startsWith("kr.kro.airbob.core.api");
	}

	/** Application classes this class depends on, excluding itself, sorted for stable messages. */
	private static Set<JavaClass> targetsOf(JavaClass origin) {
		Set<JavaClass> targets = new java.util.TreeSet<>(java.util.Comparator.comparing(JavaClass::getName));
		for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
			JavaClass target = dependency.getTargetClass().getBaseComponentType();
			if (!target.equals(origin) && ArchitectureSlices.isApplicationClass(target)) {
				targets.add(target);
			}
		}
		return targets;
	}

	record DomainEdge(String from, String to, boolean inCycle) {
	}

	/** Domain-level dependency graph (QueryDSL Q-types are not origins), one item per edge. */
	private static final class DomainDependencyEdges extends AbstractClassesTransformer<DomainEdge> {

		private DomainDependencyEdges() {
			super("domain dependency edges");
		}

		@Override
		public Iterable<DomainEdge> doTransform(JavaClasses classes) {
			Map<String, Set<String>> graph = new TreeMap<>();
			for (JavaClass origin : classes) {
				if (ArchitectureSlices.isGeneratedQueryType(origin)) {
					continue;
				}
				Optional<String> from = ArchitectureSlices.domainOf(origin);
				if (from.isEmpty()) {
					continue;
				}
				for (JavaClass target : targetsOf(origin)) {
					ArchitectureSlices.domainOf(target)
						.filter(to -> !to.equals(from.get()))
						.ifPresent(to -> graph.computeIfAbsent(from.get(), key -> new TreeSet<>()).add(to));
				}
			}

			List<DomainEdge> edges = new ArrayList<>();
			graph.forEach((from, targets) -> targets.forEach(to ->
				edges.add(new DomainEdge(from, to, reaches(graph, to, from)))));
			return edges;
		}

		private static boolean reaches(Map<String, Set<String>> graph, String start, String goal) {
			Deque<String> pending = new ArrayDeque<>(List.of(start));
			Set<String> visited = new HashSet<>();
			while (!pending.isEmpty()) {
				String current = pending.pop();
				if (current.equals(goal)) {
					return true;
				}
				if (visited.add(current)) {
					pending.addAll(graph.getOrDefault(current, Set.of()));
				}
			}
			return false;
		}
	}

	private static ArchCondition<DomainEdge> notBePartOfACycle() {
		return new ArchCondition<>("not be part of a cycle") {
			@Override
			public void check(DomainEdge edge, ConditionEvents events) {
				if (edge.inCycle()) {
					events.add(SimpleConditionEvent.violated(
						edge, "Domain dependency cycle edge: " + edge.from() + " -> " + edge.to()));
				}
			}
		};
	}
}
