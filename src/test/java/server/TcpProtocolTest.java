package server;

import client.Client;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import protocol.JsonProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TcpProtocolTest {
    @TempDir Path directory;

    private class RunningServer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        final UserService service = new UserService(new UserRepository(directory.resolve("garage.db")));
        final List<Server> workers = new CopyOnWriteArrayList<>();
        final List<Exception> failures = new CopyOnWriteArrayList<>();
        final Thread acceptor = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    Server worker = new Server(listener.accept(), service);
                    workers.add(worker);
                    worker.start();
                }
            } catch (IOException e) {
                if (!listener.isClosed()) failures.add(e);
            }
        });
        RunningServer() throws IOException { acceptor.start(); }
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception {
            listener.close();
            acceptor.join(2000);
            for (Server worker : workers) worker.join(2000);
            assertTrue(failures.isEmpty());
            assertFalse(acceptor.isAlive());
            assertTrue(workers.stream().noneMatch(Thread::isAlive));
        }
    }

    private JsonObject data(String... values) {
        JsonObject data = new JsonObject();
        for (int i = 0; i < values.length; i += 2) data.addProperty(values[i], values[i + 1]);
        return data;
    }

    @Test void clientUsesFreshConnectionsForCompleteProtocol() throws Exception {
        try (RunningServer server = new RunningServer()) {
            Client client = new Client("127.0.0.1", server.port());
            assertEquals(201, client.send("register", data("name", "João Silva", "username", "joao", "password", "$Password123")).get("statusCode").getAsInt());
            JsonObject login = client.send("login", data("username", "joao", "password", "$Password123"));
            JsonObject auth = data("username", "joao", "token", login.getAsJsonObject("data").get("token").getAsString());
            assertEquals("João Silva", client.send("getuser", auth).getAsJsonObject("data").get("name").getAsString());
            auth.addProperty("name", "José Silva");
            assertEquals(200, client.send("updateusername", auth).get("statusCode").getAsInt());
            auth.addProperty("oldPassword", "$Password123");
            auth.addProperty("newPassword", "NovaSenha!1");
            assertEquals(200, client.send("updateuserpassword", auth).get("statusCode").getAsInt());
            assertEquals(200, client.send("logout", auth).get("statusCode").getAsInt());
            assertEquals(401, client.send("getuser", auth).get("statusCode").getAsInt());
            login = client.send("login", data("username", "joao", "password", "NovaSenha!1"));
            auth.addProperty("token", login.getAsJsonObject("data").get("token").getAsString());
            assertEquals(200, client.send("deleteuser", auth).get("statusCode").getAsInt());
            assertEquals(9, server.workers.size());
        }
    }

    @Test void serverClosesConnectionAfterOneResponseAndRejectsMalformedJson() throws Exception {
        try (RunningServer server = new RunningServer()) {
            for (String request : new String[]{"{", "[]", "null", "{method:login}", "{\"method\":12,\"data\":{}}",
                    "{\"method\":\"logout\",\"data\":{}}", "{\"method\":\"login\",\"data\":[]}"}) {
                try (Socket socket = new Socket("127.0.0.1", server.port());
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    socket.setSoTimeout(2000);
                    out.println(request);
                    JsonObject response = JsonProtocol.GSON.fromJson(in.readLine(), JsonObject.class);
                    assertEquals(400, response.get("statusCode").getAsInt(), request);
                    assertTrue(response.get("data").isJsonNull());
                    assertEquals(Set.of("statusCode", "message", "data"), response.keySet());
                    assertNull(in.readLine(), "Servidor deve encerrar após responder");
                }
            }
        }
    }

    @Test void consoleMenuMaintainsSessionAcrossOperations() throws Exception {
        try (RunningServer server = new RunningServer()) {
            InputStream originalIn = System.in;
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String input = String.join("\n", "1", "Ana Silva", "ana", "$Password123", "2", "ana", "$Password123",
                    "3", "4", "Maria Silva", "5", "$Password123", "NovaSenha!1", "7", "2", "ana", "NovaSenha!1",
                    "3", "6", "EXCLUIR", "0", "");
            try {
                System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
                System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
                Client.main(new String[]{"127.0.0.1", String.valueOf(server.port())});
            } finally {
                System.setIn(originalIn);
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("201 - Usuário criado com sucesso"));
            assertTrue(text.contains("200 - Nome do usuário atualizado com sucesso"));
            assertTrue(text.contains("200 - Senha atualizada com sucesso"));
            assertTrue(text.contains("200 - Usuário deslogado com sucesso"));
            assertTrue(text.contains("200 - Usuário deletado com sucesso"));
            assertTrue(text.contains("Maria Silva"));
            assertFalse(text.contains("$Password123"));
            assertFalse(text.contains("NovaSenha!1"));
        }
    }
}
