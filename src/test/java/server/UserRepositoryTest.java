package server;

import model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryTest {
    @TempDir Path directory;

    private String legacy() {
        return "[{\"name\":\"João Silva\",\"username\":\"joao\",\"password\":\"12345678\"}]";
    }

    @Test void migrationPreservesSourceAndDoesNotResurrectDeletedUsers() throws Exception {
        Path source = directory.resolve("users.json");
        Path database = directory.resolve("garage.db");
        Files.writeString(source, legacy());
        UserRepository repository = new UserRepository(database);
        assertEquals("João Silva", repository.load().get("joao").getName());
        assertEquals(legacy(), Files.readString(source));
        assertEquals("SQLite format 3", new String(Files.readAllBytes(database), 0, 15, java.nio.charset.StandardCharsets.US_ASCII));
        repository.replace("joao", null);
        assertTrue(new UserRepository(database).load().isEmpty());
        assertEquals(legacy(), Files.readString(source));
    }

    @Test void invalidMigrationRollsBackAndCanBeRetried() throws Exception {
        Path source = directory.resolve("users.json");
        Path database = directory.resolve("garage.db");
        String invalid = legacy().replace("]", ",{\"name\":\"Ana\",\"username\":\"joao\",\"password\":\"12345678\"}]");
        Files.writeString(source, invalid);
        assertThrows(IOException.class, () -> new UserRepository(database).load());
        assertEquals(invalid, Files.readString(source));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('users', 'metadata')")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        Files.writeString(source, legacy());
        assertEquals(1, new UserRepository(database).load().size());
    }

    @Test void malformedMigrationPreservesSource() throws Exception {
        Path source = directory.resolve("users.json");
        Files.writeString(source, "invalid");
        assertThrows(IOException.class, () -> new UserRepository(directory.resolve("garage.db")).load());
        assertEquals("invalid", Files.readString(source));
    }

    @Test void sqliteEnforcesUniqueUsernameAndUpdatesOnlyTarget() throws Exception {
        UserRepository repository = new UserRepository(directory.resolve("garage.db"));
        repository.load();
        repository.create(new User("Ana", "ana", "Abcdef1!"));
        repository.create(new User("José", "jose", "Abcdef1!"));
        assertThrows(IOException.class, () -> repository.create(new User("Outra", "ana", "Abcdef1!")));
        repository.replace("ana", new User("Maria", "ana", "NovaSenha!1"));
        var users = repository.load();
        assertEquals(2, users.size());
        assertEquals("Maria", users.get("ana").getName());
        assertEquals("NovaSenha!1", users.get("ana").getPassword());
        assertEquals("José", users.get("jose").getName());
    }

    @Test void databaseLockDoesNotChangePersistedUser() throws Exception {
        Path database = directory.resolve("garage.db");
        UserRepository repository = new UserRepository(database);
        repository.load();
        repository.create(new User("Ana", "ana", "Abcdef1!"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            assertThrows(IOException.class, () -> repository.replace("ana", new User("Maria", "ana", "NovaSenha!1")));
            statement.execute("ROLLBACK");
        }
        assertEquals("Ana", repository.load().get("ana").getName());
    }
}
