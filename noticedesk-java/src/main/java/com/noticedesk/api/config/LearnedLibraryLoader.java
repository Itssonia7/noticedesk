package com.noticedesk.api.config;

import com.noticedesk.api.service.learned.LearnedLibrary;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reads the learned library ({@code $PRIVATE_SEED_DIR/learned/*.json}) into memory on startup with the
 * {@code private-seed} profile and logs "learned library: N entries (M provisional, K approved)". The entries stay out
 * of the issue card catalogue; see {@link LearnedLibrary}.
 */
@Component
@Profile("private-seed")
@RequiredArgsConstructor
public class LearnedLibraryLoader implements ApplicationRunner {

    private final LearnedLibrary learnedLibrary;

    @Override
    public void run(ApplicationArguments args) {
        learnedLibrary.load();
    }
}
