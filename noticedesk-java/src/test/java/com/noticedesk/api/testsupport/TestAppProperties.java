package com.noticedesk.api.testsupport;

import com.noticedesk.api.config.AppProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

/**
 * Binds {@code noticedesk.*} from the real application.yml (env placeholders resolved against the
 * process environment), so runners and tests use the same pricing / effort / max_tokens as the app.
 */
public final class TestAppProperties {

    private TestAppProperties() {}

    public static AppProperties fromApplicationYml() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            StandardEnvironment env = new StandardEnvironment();
            sources.forEach(env.getPropertySources()::addLast);
            return Binder.get(env).bind("noticedesk", AppProperties.class).orElseGet(AppProperties::new);
        } catch (Exception e) {
            throw new IllegalStateException("could not bind application.yml", e);
        }
    }
}
