package com.noticedesk.api.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;

/**
 * Loads development seed issue cards into the database ONLY when active profile is 'dev'.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevSeedDataLoader implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        log.info("dev_profile_active: Loading development seed issue cards...");

        // issue cards, then the reply / stage templates that reference them
        for (String path : new String[]{"seeds/dev/seed_issue_cards_dev.sql", "seeds/dev/seed_reply_and_stage_templates_dev.sql"}) {
            File seedFile = new File(path);
            Resource resource = seedFile.exists() ? new FileSystemResource(seedFile) : new ClassPathResource(path);
            if (resource.exists()) {
                try (Connection conn = dataSource.getConnection()) {
                    ScriptUtils.executeSqlScript(conn, resource);
                    log.info("dev_profile_active: loaded {}", path);
                }
            } else {
                log.warn("dev_profile_active: Seed SQL script not found at {}", seedFile.getAbsolutePath());
            }
        }
    }
}
