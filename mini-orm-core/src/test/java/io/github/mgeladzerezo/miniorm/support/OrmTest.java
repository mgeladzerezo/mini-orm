package io.github.mgeladzerezo.miniorm.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/** Base class: every subclass runs once per {@link Backend}, each test against a fresh database. */
@ParameterizedClass(name = "{0}")
@EnumSource(Backend.class)
public abstract class OrmTest {

    @Parameter
    protected Backend backend;

    protected TestDb db;

    @BeforeEach
    void openDatabase() throws Exception {
        db = TestDb.shared(backend);
    }

}
