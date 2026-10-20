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
    void defectCardIds_fromOptionalFile_orNull(@TempDir Path tmp) throws IOException {
        assertNull(PrivateSetSupport.defectCardIds(null), "no PRIVATE_SEED_DIR: no list");
        assertNull(PrivateSetSupport.defectCardIds(" "));
        assertNull(PrivateSetSupport.defectCardIds(tmp.toString()), "file absent: count skipped");

        Files.writeString(tmp.resolve(PrivateSetSupport.DEFECT_CARDS_FILE), """
                # procedural / visible-defect cards
                NDK-141
                ndk-143   # unsigned SCN
                  NDK-104

                NDK-141
                """);
        assertEquals(java.util.Set.of("NDK-141", "NDK-143", "NDK-104"), PrivateSetSupport.defectCardIds(tmp.toString()));
        assertEquals(java.util.Set.of(), PrivateSetSupport.parseDefectCardIds(List.of("", "   ", "# only a comment")));
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

    @Test
    void readCards_objectOrArray_statusDefaultsToDraft_catalogueOrder(@TempDir Path tmp) throws IOException {
        Path cards = Files.createDirectories(tmp.resolve("cards"));
        Files.writeString(cards.resolve("b.json"), """
                [{"card_id": "T-002", "version": 1, "title": "Two", "summary": "s", "keywords": ["Section 73"], "stages": ["scn_73"],
                  "not_this_if": ["use T-001"], "status": "active"},
                 {"card_id": "T-001", "version": 2, "title": "One v2", "summary": "s", "status": "active"}]
                """);
        Files.writeString(cards.resolve("a.json"), """
                {"card_id": "T-001", "title": "One v1", "summary": "s", "sections": "Section 16"}
                """);
        Files.writeString(cards.resolve("notes.txt"), "ignored");

        List<com.noticedesk.api.model.matching.IssueCard> read =
                PrivateSetSupport.readCards(cards.toFile(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals(List.of("T-001", "T-001", "T-002"), read.stream().map(c -> c.cardId()).toList());
        assertEquals(2, read.get(0).version(), "latest version first");
        assertEquals("draft", read.get(1).status(), "no status: draft, as the private seed loader");
        assertEquals(List.of("Section 16"), read.get(1).sections(), "a single value becomes a one-item list");
        assertEquals(List.of("Section 73"), read.get(2).keywords());
        assertEquals(List.of("use T-001"), read.get(2).notThisIf());
        assertTrue(PrivateSetSupport.readCards(tmp.resolve("absent").toFile(), new com.fasterxml.jackson.databind.ObjectMapper()).isEmpty());

        Files.writeString(cards.resolve("c.json"), "{\"title\": \"no id\"}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PrivateSetSupport.readCards(cards.toFile(), new com.fasterxml.jackson.databind.ObjectMapper()));
        assertEquals("cards file #3 has a card without card_id", e.getMessage());
    }
}
