package com.noticedesk.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PrivateSeedLoaderTest {

    private static final String SECRET = "SECRET-CLIENT-TEXT-should-never-be-logged";

    private final ObjectMapper om = new ObjectMapper();

    private PrivateSeedLoader loader(JdbcTemplate jdbc, String dir) {
        AppProperties props = new AppProperties();
        props.setPrivateSeedDir(dir);
        return new PrivateSeedLoader(jdbc, props, om);
    }

    @Test
    void unsetDirectoryIsSkipped(CapturedOutput output) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertNull(loader(jdbc, "").load());
        assertNull(loader(jdbc, null).load());
        verifyNoInteractions(jdbc);
        assertTrue(output.getOut().contains("private seed skipped"));
    }

    @Test
    void missingDirectoryIsSkipped(@TempDir Path tmp) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertNull(loader(jdbc, tmp.resolve("does-not-exist").toString()).load());
        verifyNoInteractions(jdbc);
    }

    @Test
    void loadsCardsTemplatesAndStageTemplatesByUpsert(@TempDir Path tmp, CapturedOutput output) throws Exception {
        Files.createDirectories(tmp.resolve("cards"));
        Files.createDirectories(tmp.resolve("templates"));
        Files.createDirectories(tmp.resolve("stage_templates"));
        Files.writeString(tmp.resolve("cards/a.json"), """
                [{"card_id":"PCARD-1","version":2,"title":"T1","summary":"%s","stages":["scn_73"],"status":"active"},
                 {"card_id":"PCARD-2","title":"T2","summary":"S2"}]
                """.formatted(SECRET));
        Files.writeString(tmp.resolve("templates/t.json"), """
                {"template_id":"PTPL-1","card_id":"PCARD-1","effective_from":"2017-07-01",
                 "blocks":[{"id":"B1","section":4,"html":"<p>%s</p>"}],"documents":["Doc A"]}
                """.formatted(SECRET));
        Files.writeString(tmp.resolve("stage_templates/s.json"), """
                {"stage":"scn_73","version":3,"sections":{"12":"<p>Prayer</p>"}}
                """);
        Files.writeString(tmp.resolve("cards/ignored.txt"), "not json");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PrivateSeedLoader.SeedCounts counts = loader(jdbc, tmp.toString()).load();

        assertEquals(new PrivateSeedLoader.SeedCounts(2, 1, 1), counts);
        verify(jdbc).update(contains("INSERT INTO issue_cards"), eq("PCARD-1"), eq(2), eq("T1"), eq(SECRET),
                eq("[]"), isNull(), isNull(), eq("[\"scn_73\"]"), eq("[]"), eq("[]"), isNull(), eq("active"));
        verify(jdbc).update(contains("INSERT INTO issue_cards"), eq("PCARD-2"), eq(1), eq("T2"), eq("S2"),
                any(), any(), any(), any(), any(), any(), any(), eq("draft"));
        verify(jdbc).update(argThat((String sql) -> sql.contains("INSERT INTO reply_templates") && sql.contains("ON CONFLICT (template_id, version) DO UPDATE")),
                eq("PTPL-1"), eq(1), eq("PCARD-1"), eq("2017-07-01"), isNull(), eq("draft"),
                contains("\"B1\""), eq("[]"), eq("[\"Doc A\"]"), isNull(), eq("[]"));
        verify(jdbc).update(argThat((String sql) -> sql.contains("INSERT INTO stage_templates") && sql.contains("ON CONFLICT (stage, version)")),
                eq("scn_73"), eq(3), isNull(), isNull(), eq("draft"), eq("{\"12\":\"<p>Prayer</p>\"}"));

        assertTrue(output.getOut().contains("private seed loaded: 2 issue card(s), 1 reply template(s), 1 stage template(s)"));
        assertFalse(output.getOut().contains(SECRET), "file contents must never be logged");
        assertFalse(output.getOut().contains(tmp.toString()), "paths are not logged");
    }

    @Test
    void invalidFileFailsBeforeAnyWriteAndDoesNotLeakContent(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("cards"));
        Files.createDirectories(tmp.resolve("templates"));
        Files.writeString(tmp.resolve("cards/a.json"), "{\"card_id\":\"PCARD-1\",\"title\":\"T\",\"summary\":\"S\"}");
        Files.writeString(tmp.resolve("templates/bad.json"), "{\"template_id\": \"" + SECRET + "\" , oops");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> loader(jdbc, tmp.toString()).load());
        assertTrue(ex.getMessage().contains("templates file #1"));
        assertFalse(ex.getMessage().contains(SECRET));
        verifyNoInteractions(jdbc);
    }

    @Test
    void entryWithoutRequiredFieldIsRejected(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("cards"));
        Files.writeString(tmp.resolve("cards/a.json"), "{\"title\":\"T\",\"summary\":\"S\"}");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> loader(jdbc, tmp.toString()).load());
        assertTrue(ex.getMessage().contains("card_id"));
        verifyNoInteractions(jdbc);
    }
}
