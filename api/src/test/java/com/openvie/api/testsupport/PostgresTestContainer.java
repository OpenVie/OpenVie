// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class PostgresTestContainer {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("openvie_test")
                    .withUsername("openvie")
                    .withPassword("openvie");

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
