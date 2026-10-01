package com.cacanode.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModularMonolithArchitectureTest {
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "ai", "auth", "chat", "document", "notification", "tenant");

    private static final ImportOption MAIN_OUTPUT_ONLY = location -> {
        String path = location.asURI().getPath();
        return path == null || !path.contains("/test-classes/");
    };

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(MAIN_OUTPUT_ONLY)
            .importPackages("com.cacanode.api");
    private final JavaClasses businessClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(MAIN_OUTPUT_ONLY)
            .importPackages(
            BUSINESS_MODULES.stream().map(module -> "com.cacanode.api." + module)
                    .toArray(String[]::new));

    @Test
    void businessModulesUseOnlyPublishedBoundaries() {
        for (String source : BUSINESS_MODULES) {
            for (String target : BUSINESS_MODULES) {
                if (source.equals(target)) {
                    continue;
                }
                DescribedPredicate<JavaClass> targetInternals =
                        JavaClass.Predicates.resideInAPackage(
                                        "com.cacanode.api." + target + "..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage(
                                        "com.cacanode.api." + target + ".api.."));
                ArchRule rule = noClasses()
                        .that().resideInAPackage("com.cacanode.api." + source + "..")
                        .should().dependOnClassesThat(targetInternals);
                rule.check(classes);
            }
        }
    }

    @Test
    void apiContractsDoNotLeakInternals() {
        noClasses().that().resideInAPackage("com.cacanode.api.*.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cacanode.api.*.model..",
                        "com.cacanode.api.*.repository..",
                        "com.cacanode.api.*.service..",
                        "com.cacanode.api.*.query..",
                        "com.cacanode.api.*.dto..")
                .check(classes);
    }

    @Test
    void commonDoesNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage("com.cacanode.api.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BUSINESS_MODULES.stream()
                                .map(module -> "com.cacanode.api." + module + "..")
                                .toArray(String[]::new))
                .check(classes);
    }

    @Test
    void businessModuleGraphIsAcyclic() {
        slices().matching("com.cacanode.api.(*)..")
                .should().beFreeOfCycles()
                .check(businessClasses);
    }

    @Test
    void moduleApisAreInterfaces() {
        classes().that().haveSimpleNameEndingWith("Api")
                .and().resideInAPackage("com.cacanode.api.*.api..")
                .should().beInterfaces()
                .check(classes);
    }

    @Test
    void jdbcAccessLivesInRepositoryOrQueryPackages() {
        noClasses().that().resideInAPackage("com.cacanode.api.*..")
                .and().resideOutsideOfPackages(
                        "com.cacanode.api.*.repository..",
                        "com.cacanode.api.*.query..",
                        "com.cacanode.api.common..",
                        "com.cacanode.api.bootstrap..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "jakarta.persistence.EntityManager")
                .check(classes);
    }
}
