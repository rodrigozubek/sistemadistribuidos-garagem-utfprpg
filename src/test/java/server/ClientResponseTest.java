package server;

import client.Client;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ClientResponseTest {
    private JsonObject receive(String method, String response) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            FutureTask<Void> reply = new FutureTask<>(() -> {
                try (Socket socket = listener.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    socket.setSoTimeout(2000);
                    assertNotNull(in.readLine());
                    out.println(response);
                }
                return null;
            });
            Thread worker = new Thread(reply);
            worker.start();
            try {
                return new Client("127.0.0.1", listener.getLocalPort()).send(method, new JsonObject());
            } finally {
                reply.get(3, TimeUnit.SECONDS);
                worker.join(1000);
            }
        }
    }

    @Test void registrationAcceptsResponsesWithoutData() throws Exception {
        JsonObject response = receive("register", "{\"statusCode\":201,\"message\":\"Usuário criado com sucesso\"}");
        assertEquals(201, response.get("statusCode").getAsInt());
        assertEquals("Usuário criado com sucesso", response.get("message").getAsString());
        assertTrue(response.get("data").isJsonNull());
    }

    @Test void registrationPreservesServerErrorsWithoutData() throws Exception {
        JsonObject response = receive("register", "{\"statusCode\":409,\"message\":\"Username já cadastrado\"}");
        assertEquals(409, response.get("statusCode").getAsInt());
        assertEquals("Username já cadastrado", response.get("message").getAsString());
    }

    @Test void invalidResponsesExplainTheProblem() {
        assertTrue(assertThrows(IOException.class, () -> receive("register", "não é JSON"))
                .getMessage().contains("não é um JSON válido"));
        assertTrue(assertThrows(IOException.class, () -> receive("register", "[]"))
                .getMessage().contains("objeto JSON"));
        assertTrue(assertThrows(IOException.class, () -> receive("register", "{\"message\":\"Erro\"}"))
                .getMessage().contains("'statusCode' ausente"));
        assertTrue(assertThrows(IOException.class, () -> receive("register", "{\"statusCode\":201}"))
                .getMessage().contains("'message'"));
        assertTrue(assertThrows(IOException.class, () -> receive("register", "{\"statusCode\":200.5,\"message\":\"OK\"}"))
                .getMessage().contains("número inteiro"));
    }

    @Test void successfulLoginStillRequiresAToken() {
        assertTrue(assertThrows(IOException.class, () -> receive("login", "{\"statusCode\":200,\"message\":\"OK\"}"))
                .getMessage().contains("data.token"));
    }
}
