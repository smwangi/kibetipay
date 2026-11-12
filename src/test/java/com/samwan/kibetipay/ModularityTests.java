package com.samwan.kibetipay;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Architecture as a test. Fails the build if, for example, the risk module reaches into
 * transfer.internal, or if two modules depend on each other in a cycle.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(KibetiPayApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    /** Writes PlantUML component diagrams + module canvases to target/spring-modulith-docs. */
    @Test
    void writesDocumentation() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
