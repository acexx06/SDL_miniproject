package com.hms.web;

import com.hms.domain.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or every method of a controller) as needing a logged-in user.
 * With no roles listed any logged-in user may call it; otherwise the user must hold one of them.
 * The session is then available to the method as {@code @RequestAttribute(AuthInterceptor.SESSION) Session}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuth {

    Role[] value() default {};
}
