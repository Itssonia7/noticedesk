package com.noticedesk.api.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Offline: notice file lookup order and the dummy-card guard rule (no database, no network). */
class PrivateSetSupportTest {

    @Test
    void dummyCardGuard_onlyWithPrivateSeedDir() {
        assertNull(PrivateSetSupport.dummyCardRefusal(List.of("CARD-001"), null), "no private catalogue: dummy cards expected");
        assertNull(PrivateSetSupport.dummyCardRefusal(List.of("CARD-001"), "  "));
        assertNull(PrivateSetSupport.dummyCardRefusal(List.of(), "/private"));
        String refusal = PrivateSetSupport.dummyCardRefusal(List.of("CARD-001", "CARD-002"), "/private");
        assertNotNull(refusal);
        assertTrue(refusal.startsWith("REFUSED: dummy cards (CARD-*) present in the database alongside a private catalogue; delete them first"), refusal);
        assertTrue(refusal.contains("2 found"), refusal);
    }

    @Test
    void resolveNotice_classpathFirst_thenPrivateDir_thenNull(@TempDir Path tmp) throws IOException {
        Path notices = Files.createDirectories(tmp.resolve("notices"));
        Files.writeString(notices.resolve("notice_mix_01.json"), "{}");
        // a private file with the name of a committed bench notice must not shadow the classpath copy
        Files.writeString(notices.resolve("notice_01_fullmatch_sec73.json"), "{\"shadow\": true}");
        String seedDir = tmp.toString();

        Resource bench = PrivateSetSupport.resolveNotice("notice_01_fullmatch_sec73.json", seedDir);
        assertNotNull(bench);
        assertFalse(bench.isFile() && bench.getFile().toPath().startsWith(tmp), "classpath copy wins");

        Resource priv = PrivateSetSupport.resolveNotice("notice_mix_01.json", seedDir);
        assertNotNull(priv);
        assertEquals(notices.resolve("notice_mix_01.json").toFile(), priv.getFile());

        assertNull(PrivateSetSupport.resolveNotice("notice_mix_01.json", null), "no private dir, not on classpath");
        assertNull(PrivateSetSupport.resolveNotice("does_not_exist.json", seedDir));
        assertNull(PrivateSetSupport.resolveNotice(" ", seedDir));

        Resource absolute = PrivateSetSupport.resolveNotice(notices.resolve("notice_mix_01.json").toAbsolutePath().toString(), null);
        assertNotNull(absolute, "absolute path accepted without PRIVATE_SEED_DIR");
        assertNull(PrivateSetSupport.resolveNotice(tmp.resolve("missing.json").toAbsolutePath().toString(), null));
    }

    @Test
    void privateNotices_sortedJsonOnly_orEmpty(@TempDir Path tmp) throws IOException {
        assertTrue(PrivateSetSupport.privateNotices(null).isEmpty());
        assertTrue(PrivateSetSupport.privateNotices(tmp.resolve("nope").toString()).isEmpty());

        Path notices = Files.createDirectories(tmp.resolve("notices"));
        Files.writeString(notices.resolve("notice_real_04D.json"), "{}");
        Files.writeString(notices.resolve("notice_mix_01.json"), "{}");
        Files.writeString(notices.resolve("README.txt"), "not a notice");
        List<Resource> all = PrivateSetSupport.privateNotices(tmp.toString());
        assertEquals(List.of("notice_mix_01.json", "notice_real_04D.json"), all.stream().map(Resource::getFilename).toList());

        assertNotNull(PrivateSetSupport.privateNotice(tmp.toString(), "notice_real_04D.json"));
        assertNull(PrivateSetSupport.privateNotice(tmp.toString(), "notice_real_99.json"));
        assertNull(PrivateSetSupport.privateNotice(null, "notice_real_04D.json"));
    }
}
