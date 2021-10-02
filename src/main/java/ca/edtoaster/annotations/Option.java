package ca.edtoaster.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface Option {
    String name();
    String description();
    boolean required() default true;
}
