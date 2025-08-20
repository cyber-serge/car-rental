package com.serge.carrental.report;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Simple annotation to enrich the HTML report
 * with human-friendly test descriptions.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface TestDescription {
    /**
     * A short description of the test purpose and what it validates.
     */
    String value();
}
