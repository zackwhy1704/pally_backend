package com.pally.api.centre;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against ambiguous Spring request mappings across the centre
 * controllers — a class of bug that compiles, passes Mockito unit tests, and
 * only crashes at application startup (i.e. on Railway). Two methods that map
 * the same HTTP verb + full path throw {@code IllegalStateException: Ambiguous
 * mapping} when the context boots. This pure-reflection test catches it in CI.
 */
class CentreRouteUniquenessTest {

    private static final Class<?>[] CONTROLLERS = {
            CentreController.class,
            CentreAnalyticsController.class,
            ClassController.class,
    };

    @Test
    void noTwoEndpointsShareTheSameVerbAndFullPath() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (Class<?> controller : CONTROLLERS) {
            String basePath = "";
            RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
            if (classMapping != null && classMapping.value().length > 0) {
                basePath = classMapping.value()[0];
            }

            for (Method method : controller.getDeclaredMethods()) {
                for (String route : routesFor(method, basePath)) {
                    if (!seen.add(route)) {
                        duplicates.add(route + "  (in " + controller.getSimpleName() + "."
                                + method.getName() + ")");
                    }
                }
            }
        }

        assertThat(duplicates)
                .as("Ambiguous mappings would crash Spring at startup")
                .isEmpty();
    }

    /** Normalises a path variable segment to a placeholder so {orgId} == {id}. */
    private static String normalise(String base, String sub) {
        String full = (base + sub).replaceAll("\\{[^/}]+}", "{}");
        // Strip a trailing produces/value cruft is not present here; keep simple.
        return full;
    }

    private static List<String> routesFor(Method method, String basePath) {
        List<String> out = new ArrayList<>();
        addIfPresent(out, "GET", method.getAnnotation(GetMapping.class) != null
                ? method.getAnnotation(GetMapping.class).value() : null, basePath);
        addIfPresent(out, "POST", method.getAnnotation(PostMapping.class) != null
                ? method.getAnnotation(PostMapping.class).value() : null, basePath);
        addIfPresent(out, "PATCH", method.getAnnotation(PatchMapping.class) != null
                ? method.getAnnotation(PatchMapping.class).value() : null, basePath);
        addIfPresent(out, "PUT", method.getAnnotation(PutMapping.class) != null
                ? method.getAnnotation(PutMapping.class).value() : null, basePath);
        addIfPresent(out, "DELETE", method.getAnnotation(DeleteMapping.class) != null
                ? method.getAnnotation(DeleteMapping.class).value() : null, basePath);
        return out;
    }

    private static void addIfPresent(List<String> out, String verb, String[] subs, String base) {
        if (subs == null) return;
        if (subs.length == 0) {
            out.add(verb + " " + normalise(base, ""));
        }
        for (String sub : subs) {
            out.add(verb + " " + normalise(base, sub));
        }
    }
}
