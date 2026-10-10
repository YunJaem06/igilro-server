package com.igilro.server.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

@AnalyzeClasses(packages = ["com.igilro.server"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    val domainIsPure = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideOutsideOfPackages(
            "..domain..", "java..", "kotlin..", "org.jetbrains.annotations..", "org.jspecify.annotations..",
        )

    @ArchTest
    val applicationDependsInwards = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideOutsideOfPackages(
            "..application..", "..domain..", "java..", "kotlin..", "org.jetbrains.annotations..", "org.jspecify.annotations..",
        )

    @ArchTest
    val apiDoesNotUseInfrastructure = noClasses().that().resideInAPackage("..api..")
        .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "org.springframework.jdbc..", "java.sql..")

    @ArchTest
    val infrastructureDoesNotUseApi = noClasses().that().resideInAPackage("..infrastructure..")
        .should().dependOnClassesThat().resideInAPackage("..api..")

    @ArchTest
    val featuresHaveNoCycles = slices().matching("com.igilro.server.(*)..")
        .should().beFreeOfCycles()
}
