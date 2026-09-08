package com.zenner.datahub.stats.writer.validation;

import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the shared Draft 2020-12 validator to the Writer as a singleton.
 *
 * <p>The same class {@code stats-input} builds for its envelope gate (D15): the
 * schemas in {@code docs/schemas/} are normative for the whole project, so both gates
 * have to be the same oracle or ADR-007 §5's split becomes two contracts that agree
 * only by coincidence. Construction compiles and caches all six schemas and resolves
 * every {@code $ref} eagerly, so a missing schema fails at startup rather than on the
 * first production record.</p>
 *
 * <p>There is deliberately <strong>no property that disables the payload gate</strong>.
 * The key {@code stats.writer.validation.intended-scope} existed only to record the
 * decided design while the gate was unbuilt, and was named "intended" precisely so it
 * could not be mistaken for a live control (DL-100). Promoting it to a real switch
 * would make "every payload reaches the INSERT unvalidated" a one-line configuration
 * change, and ADR-007 §5 offers no second scope for it to select.</p>
 */
@Configuration
public class WriterValidationConfiguration {

    @Bean
    public StatsSchemaValidator statsSchemaValidator() {
        return new StatsSchemaValidator();
    }
}
