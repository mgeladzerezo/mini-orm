package io.github.mgeladzerezo.miniorm.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Base class: every subclass runs once per {@link Backend}, each test against an empty database.
 * Run {@code -Dminiorm.backends=h2} to skip PostgreSQL (for example when Docker is not available).
 */
@ParameterizedClass(name = "{0}")
@EnumSource(Backend.class)
public abstract class OrmTest {

    @Parameter
    protected Backend backend;

    protected TestDb db;

    @BeforeEach
    void openDatabase() throws Exception {
        String enabled = System.getProperty("miniorm.backends", "h2,postgres");
        Assumptions.assumeTrue(enabled.toLowerCase().contains(backend.name().toLowerCase()),
                backend + " is disabled by -Dminiorm.backends");
        db = TestDb.shared(backend);
    }
}
