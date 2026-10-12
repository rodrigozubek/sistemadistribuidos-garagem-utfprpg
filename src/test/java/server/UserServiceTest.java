package server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protocol.JsonProtocol;
import protocol.Response;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {
    @TempDir Path directory;
    private UserService service;
    private Path file;

    @BeforeEach void setup() throws Exception {
        file = directory.resolve("garage.db");
        service = new UserService(new UserRepository(file));
    }

    private JsonObject data(String... pairs) {
        JsonObject data = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) data.addProperty(pairs[i], pairs[i + 1]);
        return data;
    }
    private Response call(String method, JsonObject data) {
        JsonObject request = new JsonObject();
        request.addProperty("method", method);
        request.add("data", data);
        return service.handle(request);
    }
    private void register(String username) {
        assertEquals(201, call("register", data("name", "João Silva", "username", username, "password", "$Password123")).getStatusCode());
    }
    private String login(String username) {
        Response result = call("login", data("username", username, "password", "$Password123"));
        assertEquals(200, result.getStatusCode());
        String token = ((JsonObject) result.getData()).get("token").getAsString();
        assertDoesNotThrow(() -> UUID.fromString(token));
        return token;
    }

    @Test void completeLifecycleAndPersistence() throws Exception {
        register("joao");
        String token = login("joao");
        JsonObject auth = data("username", "joao", "token", token);
        Response found = call("getuser", auth);
        assertEquals(200, found.getStatusCode());
        assertEquals(Set.of("name", "username"), ((JsonObject) found.getData()).keySet());
        auth.addProperty("name", "Maria Silva");
        assertEquals(200, call("updateusername", auth).getStatusCode());
        auth.addProperty("oldPassword", "$Password123");
        auth.addProperty("newPassword", "NovaSenha!1");
        assertEquals(200, call("updateuserpassword", auth).getStatusCode());
        assertEquals(401, call("login", data("username", "joao", "password", "$Password123")).getStatusCode());
        service = new UserService(new UserRepository(file));
        assertEquals(401, call("getuser", auth).getStatusCode());
        Response relogin = call("login", data("username", "joao", "password", "NovaSenha!1"));
        assertEquals(200, relogin.getStatusCode());
        auth.addProperty("token", ((JsonObject) relogin.getData()).get("token").getAsString());
        assertEquals("Maria Silva", ((JsonObject) call("getuser", auth).getData()).get("name").getAsString());
        assertEquals(200, call("deleteuser", auth).getStatusCode());
        assertEquals(401, call("getuser", auth).getStatusCode());
        service = new UserService(new UserRepository(file));
        assertEquals(401, call("login", data("username", "joao", "password", "NovaSenha!1")).getStatusCode());
    }

    @Test void conflictAndLoginErrorsMatchSpecification() {
        register("joao");
        Response conflict = call("register", data("username", "joao"));
        assertEquals(409, conflict.getStatusCode());
        assertEquals("O username já está em uso.", conflict.getMessage());
        for (String username : new String[]{"joao", "ausente"}) {
            Response error = call("login", data("username", username, "password", "errada"));
            assertEquals(401, error.getStatusCode());
            assertEquals("Usuario ou senhas inválidos", error.getMessage());
            assertNull(error.getData());
        }
    }

    @Test void fieldValidationAndBoundaries() {
        for (String name : new String[]{"", " ", "Ana1", "Ana!", "A".repeat(61)})
            assertEquals(400, call("register", data("name", name, "username", "joao", "password", "$Password123")).getStatusCode());
        for (String username : new String[]{"ab", "Aaa", "joão", "a b", "a".repeat(21)})
            assertEquals(400, call("register", data("name", "Ana", "username", username, "password", "$Password123")).getStatusCode());
        for (String password : new String[]{"1234567", "a".repeat(21), "Abcdef1?", "Abcdef1 ", "Abcdef1ç",
                "$password123", "$PASSWORD123", "$Passwordabc", "Password123", "12345678", "#.*&%$@!()-_=+"})
            assertEquals(400, call("register", data("name", "Ana", "username", "joao", "password", password)).getStatusCode());
        assertTrue(UserService.validName("A"));
        assertTrue(UserService.validName("A".repeat(60)));
        assertTrue(UserService.validUsername("a".repeat(20)));
        assertTrue(UserService.validPassword("Abcdef1!"));
        assertTrue(UserService.validPassword("Ab1!" + "a".repeat(16)));
        assertFalse(UserService.validPassword("Abcde1!"));
        assertFalse(UserService.validPassword("Ab1!" + "a".repeat(17)));
        assertFalse(UserService.validPassword(null));
        for (char symbol : "#.*&%$@!()-_=+".toCharArray())
            assertTrue(UserService.validPassword("Abcdef1" + symbol));
        JsonObject nullName = data("username", "joao", "password", "$Password123");
        nullName.add("name", null);
        assertEquals(400, call("register", nullName).getStatusCode());
        nullName.addProperty("name", 123);
        assertEquals(400, call("register", nullName).getStatusCode());
    }

    @Test void authenticationAndOwnership() {
        register("joao");
        register("maria");
        String token = login("joao");
        for (String method : new String[]{"getuser", "updateusername", "updateuserpassword", "deleteuser"}) {
            assertEquals(401, call(method, data("token", token, "username", "maria")).getStatusCode());
            assertEquals(400, call(method, data("username", "joao")).getStatusCode());
            Response invalid = call(method, data("token", "invalid", "username", "joao"));
            assertEquals(401, invalid.getStatusCode());
            assertEquals("Sessão expirada ou encerrada.", invalid.getMessage());
        }
        assertEquals(400, call("logout", data()).getStatusCode());
        assertEquals(401, call("login", data("token", "invalid", "username", "joao", "password", "$Password123")).getStatusCode());
    }

    @Test void logoutAndDeletionInvalidateTokens() {
        register("joao");
        String first = login("joao"), second = login("joao");
        assertEquals(200, call("logout", data("token", first)).getStatusCode());
        assertEquals(401, call("logout", data("token", first)).getStatusCode());
        String third = login("joao");
        assertEquals(200, call("deleteuser", data("token", second, "username", "joao")).getStatusCode());
        assertEquals(401, call("getuser", data("token", third, "username", "joao")).getStatusCode());
    }

    @Test void updateFailuresPreserveUser() {
        register("joao");
        JsonObject auth = data("token", login("joao"), "username", "joao", "name", "Ana!");
        assertEquals(400, call("updateusername", auth).getStatusCode());
        auth.addProperty("oldPassword", "wrong");
        auth.addProperty("newPassword", "12345678");
        assertEquals(401, call("updateuserpassword", auth).getStatusCode());
        auth.addProperty("oldPassword", "$Password123");
        for (String invalid : new String[]{"short", "$password123", "$PASSWORD123", "$Passwordabc", "Password123"}) {
            auth.addProperty("newPassword", invalid);
            Response error = call("updateuserpassword", auth);
            assertEquals(400, error.getStatusCode());
            assertEquals("Senha fora do padrão", error.getMessage());
            assertNull(error.getData());
        }
        assertEquals("João Silva", ((JsonObject) call("getuser", auth).getData()).get("name").getAsString());
        login("joao");
    }

    @Test void malformedRequestsAndNullResponseData() {
        assertEquals(400, service.handle(null).getStatusCode());
        assertEquals(400, service.handle(new JsonObject()).getStatusCode());
        assertEquals(400, call("Login", data()).getStatusCode());
        assertEquals(400, call("login", null).getStatusCode());
        JsonObject serialized = JsonProtocol.GSON.toJsonTree(call("logout", data())).getAsJsonObject();
        assertEquals(Set.of("statusCode", "message", "data"), serialized.keySet());
        assertTrue(serialized.get("data").isJsonNull());
    }

    @Test void concurrentRegistrationCreatesOnlyOneUser() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 16; i++) requests.add(() -> call("register",
                    data("name", "Ana", "username", "same", "password", "Abcdef1!")).getStatusCode());
            List<Future<Integer>> results = executor.invokeAll(requests);
            int created = 0, conflicts = 0;
            for (Future<Integer> result : results) {
                if (result.get() == 201) created++;
                if (result.get() == 409) conflicts++;
            }
            assertEquals(1, created);
            assertEquals(15, conflicts);
            assertEquals(1, new UserRepository(file).load().size());
        }
    }

    @Test void writeFailureDoesNotCreatePhantomUser() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER block_insert BEFORE INSERT ON users BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        }
        assertEquals(500, call("register", data("name", "Ana", "username", "ana", "password", "Abcdef1!")).getStatusCode());
        assertEquals(401, call("login", data("username", "ana", "password", "Abcdef1!")).getStatusCode());
    }

    @Test void legacyPasswordCanLoginAndMustFollowNewRuleWhenChanged() throws Exception {
        file = directory.resolve("migrated.db");
        Files.writeString(directory.resolve("users.json"), "[{\"name\":\"Ana\",\"username\":\"ana\",\"password\":\"12345678\"}]");
        service = new UserService(new UserRepository(file));
        Response loggedIn = call("login", data("username", "ana", "password", "12345678"));
        assertEquals(200, loggedIn.getStatusCode());
        JsonObject auth = data("token", ((JsonObject) loggedIn.getData()).get("token").getAsString(),
                "username", "ana", "oldPassword", "12345678", "newPassword", "$password123");
        assertEquals(400, call("updateuserpassword", auth).getStatusCode());
        auth.addProperty("newPassword", "$Password123");
        assertEquals(200, call("updateuserpassword", auth).getStatusCode());
        service = new UserService(new UserRepository(file));
        assertEquals(200, call("login", data("username", "ana", "password", "$Password123")).getStatusCode());
    }

    @Test void failedUpdateAndDeletionPreserveDataAndSession() throws Exception {
        register("joao");
        JsonObject auth = data("token", login("joao"), "username", "joao", "name", "Maria");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER block_update BEFORE UPDATE ON users BEGIN SELECT RAISE(ABORT, 'blocked'); END");
            statement.executeUpdate("CREATE TRIGGER block_delete BEFORE DELETE ON users BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        }
        assertEquals(500, call("updateusername", auth).getStatusCode());
        auth.addProperty("oldPassword", "$Password123");
        auth.addProperty("newPassword", "NovaSenha!1");
        assertEquals(500, call("updateuserpassword", auth).getStatusCode());
        assertEquals(500, call("deleteuser", auth).getStatusCode());
        assertEquals("João Silva", ((JsonObject) call("getuser", auth).getData()).get("name").getAsString());
        var persisted = new UserRepository(file).load().get("joao");
        assertEquals("João Silva", persisted.getName());
        assertEquals("$Password123", persisted.getPassword());
        login("joao");
    }

    @Test void corruptDatabaseIsNotSilentlyOverwritten() throws Exception {
        Files.writeString(file, "invalid");
        assertThrows(java.io.IOException.class, () -> new UserService(new UserRepository(file)));
        assertEquals("invalid", Files.readString(file));
    }
}
