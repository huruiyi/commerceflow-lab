package vip.fairy.commerceflow.commerce.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
  packages = "vip.fairy.commerceflow.commerce",
  importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleArchitectureTest {

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..api..", "..application..", "..infrastructure..")
    .because("domain types must remain independent from delivery, orchestration and persistence");

  @ArchTest
  static final ArchRule API_DOES_NOT_ACCESS_INFRASTRUCTURE = noClasses()
    .that().resideInAPackage("..api..")
    .should().dependOnClassesThat()
    .resideInAPackage("..infrastructure..")
    .because("API adapters must delegate persistence work to the application layer");

  @ArchTest
  static final ArchRule REST_CONTROLLERS_STAY_IN_API_PACKAGES = classes()
    .that().areAnnotatedWith(RestController.class)
    .should().resideInAPackage("..api..");

  @ArchTest
  static final ArchRule MAPPER_INTERFACES_STAY_IN_INFRASTRUCTURE_PACKAGES = classes()
    .that().areInterfaces()
    .and().haveSimpleNameEndingWith("Mapper")
    .should().resideInAPackage("..infrastructure..");
}
