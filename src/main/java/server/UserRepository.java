package server;

import com.google.gson.JsonParseException;
import model.User;
import protocol.JsonProtocol;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQLite via JDBC; uma única instância de servidor deve usar cada banco. */
public final class UserRepository {
    private final Path file;
    private final Path legacyFile;

    public UserRepository(Path file) {
        this(file, file.toAbsolutePath().resolveSibling("users.json"));
    }

    public UserRepository(Path file, Path legacyFile) {
        this.file = file.toAbsolutePath();
        this.legacyFile = legacyFile == null ? null : legacyFile.toAbsolutePath();
        if (this.file.equals(this.legacyFile))
            throw new IllegalArgumentException("O banco SQLite e o JSON de migração devem ter caminhos diferentes.");
    }

    private Connection connect() throws IOException, SQLException {
        Files.createDirectories(file.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    public Map<String, User> load() throws IOException {
        try (Connection connection = connect()) {
            initialize(connection);
            Map<String, User> users = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT name, username, password FROM users ORDER BY username")) {
                while (rows.next()) {
                    User user = new User(rows.getString("name"), rows.getString("username"), rows.getString("password"));
                    validate(user);
                    users.put(user.getUsername(), user);
                }
            }
            return users;
        } catch (SQLException e) {
            throw new IOException("Não foi possível carregar o banco SQLite.", e);
        }
    }

    private void initialize(Connection connection) throws SQLException, IOException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "username TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
            boolean migrated;
            try (ResultSet rows = statement.executeQuery("SELECT value FROM metadata WHERE key = 'jsonMigration'")) {
                migrated = rows.next();
            }
            if (!migrated) {
                boolean empty;
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM users")) {
                    empty = rows.next() && rows.getInt(1) == 0;
                }
                if (empty && legacyFile != null && Files.exists(legacyFile)) {
                    Map<String, User> legacy = readLegacy();
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO users (name, username, password) VALUES (?, ?, ?)")) {
                        for (User user : legacy.values()) {
                            bind(insert, user);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                statement.executeUpdate("INSERT INTO metadata (key, value) VALUES ('jsonMigration', 'done')");
            }
            connection.commit();
        } catch (SQLException | IOException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Map<String, User> readLegacy() throws IOException {
        Map<String, User> users = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(legacyFile, StandardCharsets.UTF_8)) {
            User[] saved = JsonProtocol.GSON.fromJson(reader, User[].class);
            if (saved == null) throw new IOException("JSON de migração inválido.");
            for (User user : saved) {
                validate(user);
                if (users.putIfAbsent(user.getUsername(), user) != null)
                    throw new IOException("Username duplicado no JSON de migração.");
            }
        } catch (JsonParseException e) {
            throw new IOException("JSON de migração inválido.", e);
        }
        return users;
    }

    private static void validate(User user) throws IOException {
        if (user == null || !UserService.validName(user.getName())
                || !UserService.validUsername(user.getUsername())
                || !UserService.validStoredPassword(user.getPassword()))
            throw new IOException("Base de usuários inválida.");
    }

    private static void bind(PreparedStatement statement, User user) throws SQLException {
        statement.setString(1, user.getName());
        statement.setString(2, user.getUsername());
        statement.setString(3, user.getPassword());
    }

    public void create(User user) throws IOException {
        validate(user);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO users (name, username, password) VALUES (?, ?, ?)")) {
            bind(statement, user);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Não foi possível cadastrar o usuário no SQLite.", e);
        }
    }

    public void replace(String username, User user) throws IOException {
        if (user != null) {
            validate(user);
            if (!username.equals(user.getUsername())) throw new IOException("Identificador de usuário inválido.");
        }
        String sql = user == null ? "DELETE FROM users WHERE username = ?"
                : "UPDATE users SET name = ?, password = ? WHERE username = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (user == null) statement.setString(1, username);
            else {
                statement.setString(1, user.getName());
                statement.setString(2, user.getPassword());
                statement.setString(3, username);
            }
            if (statement.executeUpdate() != 1) throw new IOException("Usuário não encontrado no banco SQLite.");
        } catch (SQLException e) {
            throw new IOException("Não foi possível alterar o usuário no SQLite.", e);
        }
    }
}
