package com.noticedesk.api.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Inserts the minimal SYNTHETIC rows a test-bench notice needs before the live runners write rows that
 * reference it (issue_matches.notice_id is a foreign key to notices): tenant, client, GST registration,
 * matter and notice. Idempotent. Every value is fake test-bench data (names and GSTINs come from the
 * committed bench notices). RLS: the caller's connection must have {@code app.current_tenant} set to
 * {@code tenantId} (see {@link #setTenant}), so every insert passes the tenant_isolation policy.
 */
public final class BenchNoticeRows {

    private BenchNoticeRows() {}

    /** Sets the RLS tenant for the session of a single-connection JdbcTemplate. */
    public static void setTenant(JdbcTemplate jdbc, UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant', ?, false)", String.class, tenantId.toString());
    }

    /**
     * @param gstin       15-character bench GSTIN (fake); the PAN is characters 3-12
     * @param clientName  bench client name
     */
    public static void ensureNotice(JdbcTemplate jdbc, UUID tenantId, UUID noticeId, String benchNoticeId,
                                    String gstin, String clientName, String documentType, String financialYear) {
        jdbc.update("""
                INSERT INTO tenants (tenant_id, legal_name) VALUES (?, 'Test Bench Tenant (synthetic)')
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId);

        String pan = gstin.substring(2, 12);
        UUID clientId = existingOrNew(jdbc,
                "SELECT client_id FROM clients WHERE tenant_id = ? AND pan = ? AND deleted_at IS NULL", tenantId, pan);
        jdbc.update("""
                INSERT INTO clients (client_id, tenant_id, pan, legal_name, entity_type)
                VALUES (?, ?, ?, ?, 'Synthetic test-bench client') ON CONFLICT DO NOTHING
                """, clientId, tenantId, pan, clientName != null && !clientName.isBlank() ? clientName : "Bench Client");

        UUID registrationId = existingOrNew(jdbc,
                "SELECT registration_id FROM client_registrations WHERE tenant_id = ? AND identifier_value = ?", tenantId, gstin);
        jdbc.update("""
                INSERT INTO client_registrations (registration_id, tenant_id, client_id, registration_type, identifier_value,
                                                  state_code, registration_status)
                VALUES (?, ?, ?, 'GST', ?, ?, 'active') ON CONFLICT DO NOTHING
                """, registrationId, tenantId, clientId, gstin, gstin.substring(0, 2));

        UUID matterId = UUID.nameUUIDFromBytes(("bench-matter:" + benchNoticeId).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO matters (matter_id, tenant_id, client_id, registration_id, law, stage, status, financial_year)
                VALUES (?, ?, ?, ?, 'GST', ?, 'open', ?) ON CONFLICT (matter_id) DO NOTHING
                """, matterId, tenantId, clientId, registrationId, documentType, financialYear);

        jdbc.update("""
                INSERT INTO notices (notice_id, tenant_id, matter_id, client_id, registration_id, law, document_type,
                                     notice_number, financial_year, ingest_channel)
                VALUES (?, ?, ?, ?, ?, 'GST', ?, ?, ?, 'web_upload') ON CONFLICT (notice_id) DO NOTHING
                """, noticeId, tenantId, matterId, clientId, registrationId, documentType, benchNoticeId, financialYear);
    }

    private static UUID existingOrNew(JdbcTemplate jdbc, String sql, Object... args) {
        List<UUID> ids = jdbc.queryForList(sql, UUID.class, args);
        return ids.isEmpty() ? UUID.randomUUID() : ids.get(0);
    }
}
