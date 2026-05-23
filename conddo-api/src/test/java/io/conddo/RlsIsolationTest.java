package io.conddo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the central thesis of the platform: tenant isolation is enforced by
 * PostgreSQL Row Level Security, not by application code.
 *
 * <p>Real Postgres (Testcontainers) → migrations applied as the owner →
 * application connects as the non-owner {@code app_user}. We then show that,
 * with only {@code app.tenant_id} changing, a tenant can neither read nor
 * write another tenant's rows.
 *
 * <p>Requires a running Docker engine (Testcontainers). Mirrors how the
 * application sets the tenant per transaction via {@code set_config}.
 */
@Testcontainers
class RlsIsolationTest {

    private static final String OWNER = "conddo_owner";
    private static final String OWNER_PASSWORD = "owner_password";
    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo")
            .withUsername(OWNER)
            .withPassword(OWNER_PASSWORD);

    private static UUID tenantA;
    private static UUID tenantB;

    @BeforeAll
    static void setUp() throws SQLException {
        // 1. Create the non-owner runtime role (mirrors infra/postgres/init).
        try (Connection owner = ownerConnection();
             Statement st = owner.createStatement()) {
            st.execute("CREATE ROLE " + APP_USER + " WITH LOGIN PASSWORD '" + APP_PASSWORD + "'");
            st.execute("GRANT CONNECT ON DATABASE conddo TO " + APP_USER);
            st.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
        }

        // 2. Apply migrations as the owner (V1 schema, V2 RLS + grants).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER, OWNER_PASSWORD)
                .placeholders(Map.of("app_role", APP_USER))
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 3. Seed two tenants (tenants table is not tenant-scoped).
        try (Connection owner = ownerConnection()) {
            tenantA = insertTenant(owner, "Tenant A", "tenant-a");
            tenantB = insertTenant(owner, "Tenant B", "tenant-b");
        }
    }

    @Test
    void tenantCannotSeeOrWriteAnotherTenantsRows() throws SQLException {
        try (Connection app = appConnection()) {
            app.setAutoCommit(false);

            // Tenant A creates a customer; tenant B creates one.
            inTenant(app, tenantA, c -> insertCustomer(c, tenantA, "Alice (A)"));
            inTenant(app, tenantB, c -> insertCustomer(c, tenantB, "Bob (B)"));

            // Each tenant sees exactly its own row.
            assertEquals(1, inTenant(app, tenantA, RlsIsolationTest::countCustomers),
                    "Tenant A should see only its own customer");
            assertEquals(1, inTenant(app, tenantB, RlsIsolationTest::countCustomers),
                    "Tenant B should see only its own customer");

            // With no tenant context, RLS fails closed — zero rows.
            app.rollback();
            assertEquals(0, countCustomers(app),
                    "Unscoped query must return no rows (fail closed)");
            app.commit();
        }
    }

    @Test
    void writeCheckBlocksForgingAnotherTenantId() throws SQLException {
        try (Connection app = appConnection()) {
            app.setAutoCommit(false);
            // While acting as tenant A, try to insert a row stamped for tenant B.
            SQLException ex = assertThrows(SQLException.class, () ->
                    inTenant(app, tenantA, c -> insertCustomer(c, tenantB, "Forged")));
            // PostgreSQL rejects via the WITH CHECK clause of the RLS policy.
            app.rollback();
            org.junit.jupiter.api.Assertions.assertTrue(
                    ex.getMessage().toLowerCase().contains("row-level security"),
                    "Expected an RLS WITH CHECK violation, got: " + ex.getMessage());
        }
    }

    // ----- helpers ---------------------------------------------------------

    @FunctionalInterface
    private interface TxFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    /** Binds the tenant for the current transaction, runs the body, commits. */
    private static <T> T inTenant(Connection c, UUID tenantId, TxFunction<T> body) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
        T result = body.apply(c);
        c.commit();
        return result;
    }

    private static long countCustomers(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM customers")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static UUID insertCustomer(Connection c, UUID tenantId, String fullName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO customers (tenant_id, full_name) VALUES (?::uuid, ?) RETURNING id")) {
            ps.setString(1, tenantId.toString());
            ps.setString(2, fullName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static UUID insertTenant(Connection c, String name, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenants (name, slug) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER, OWNER_PASSWORD);
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }
}
