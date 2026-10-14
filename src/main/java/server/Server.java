package server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import protocol.JsonProtocol;
import protocol.Response;
import util.ConsoleIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Cada conexão recebe exatamente uma requisição. */
public class Server extends Thread {
    private final Socket clientSocket;
    private final UserService service;
    private static UserService defaultService;

    public Server(Socket socket, UserService service) {
        this.clientSocket = socket;
        this.service = service;
    }
    public Server(Socket socket) { this(socket, getDefaultService()); }

    private static synchronized UserService getDefaultService() {
        if (defaultService == null) {
            try {
                Path database = Path.of(System.getProperty("garage.databaseFile", "data/garage.db"));
                Path legacy = Path.of(System.getProperty("garage.legacyUsersFile",
                        System.getProperty("garage.usersFile", database.toAbsolutePath().resolveSibling("users.json").toString())));
                defaultService = new UserService(new UserRepository(database, legacy));
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível carregar os usuários", e);
            }
        }
        return defaultService;
    }

    public static void main(String[] args) {
        BufferedReader keyboard = ConsoleIO.initialize("server");
        try {
            String value;
            if (args.length > 0) value = args[0];
            else {
                System.out.print("Digite a porta do servidor: ");
                value = keyboard.readLine();
            }
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            UserService service = getDefaultService();
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("[SERVIDOR] Iniciado na porta " + port);
                while (true) new Server(serverSocket.accept(), service).start();
            }
        } catch (BindException e) {
            System.err.println("[SERVIDOR] Porta já está sendo utilizada.");
        } catch (IllegalArgumentException e) {
            System.err.println("[SERVIDOR] Porta inválida (1 a 65535).");
        } catch (IOException | IllegalStateException e) {
            System.err.println("[SERVIDOR] " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try (Socket socket = clientSocket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(30000);
            String line = in.readLine();
            if (line == null) return;
            Response response;
            try {
                JsonObject request = JsonProtocol.GSON.fromJson(line, JsonObject.class);
                System.out.println("[SERVIDOR] Requisição " + (request == null ? "null" : JsonProtocol.safeLog(request)));
                response = service.handle(request);
            } catch (JsonParseException | IllegalStateException e) {
                response = new Response(400, "JSON inválido", null);
            } catch (RuntimeException e) {
                response = new Response(500, "Erro interno do servidor", null);
            }
            JsonObject json = JsonProtocol.GSON.toJsonTree(response).getAsJsonObject();
            System.out.println("[SERVIDOR] Resposta " + JsonProtocol.safeLog(json));
            out.println(JsonProtocol.GSON.toJson(json));
        } catch (IOException e) {
            System.err.println("[SERVIDOR] Conexão encerrada: " + e.getMessage());
        }
    }
}
