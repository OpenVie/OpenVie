package com.cacanode.api.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class PostgresTestContainer {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("cacanode_test")
                    .withUsername("cacanode")
                    .withPassword("cacanode");

    static {
        POSTGRES.start();
    }

    private PostgresTestContainer() {
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    public static String createDatabase(String prefix) {
        String database = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create PostgreSQL test database", exception);
        }
        int slash = jdbcUrl().lastIndexOf('/');
        return jdbcUrl().substring(0, slash + 1) + database;
    }
}
