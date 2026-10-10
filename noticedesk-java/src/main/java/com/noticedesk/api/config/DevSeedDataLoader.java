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

        File seedFile = new File("seeds/dev/seed_issue_cards_dev.sql");
        Resource resource;
        if (seedFile.exists()) {
            resource = new FileSystemResource(seedFile);
        } else {
            resource = new ClassPathResource("seeds/dev/seed_issue_cards_dev.sql");
        }

        if (resource.exists()) {
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, resource);
                log.info("dev_profile_active: Successfully loaded 20 development seed issue cards");
            }
        } else {
            log.warn("dev_profile_active: Seed SQL script not found at {}", seedFile.getAbsolutePath());
        }
    }
}
