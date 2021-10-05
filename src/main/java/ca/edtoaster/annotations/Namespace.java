package ca.edtoaster.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface Namespace {
    String name() default "";
    String description();
}
