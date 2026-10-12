package client;

import com.google.gson.JsonObject;
import protocol.JsonProtocol;
import util.ConsoleIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class Client {
    private final String host;
    private final int port;
    private String token;
    private String username;

    public Client(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Endereço ou porta inválidos.");
        this.host = host;
        this.port = port;
    }

    /** Uma nova conexão TCP por operação. */
    public JsonObject send(String method, JsonObject data) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("method", method);
        request.add("data", data);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10000);
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.println(JsonProtocol.GSON.toJson(request));
                if (out.checkError()) throw new IOException("Falha ao enviar requisição.");
                String line = in.readLine();
                if (line == null) throw new IOException("Servidor encerrou sem responder.");
                try {
                    JsonObject response = JsonProtocol.GSON.fromJson(line, JsonObject.class);
                    if (response == null || !response.has("statusCode") || !response.has("message") || !response.has("data"))
                        throw new IllegalStateException();
                    int status = response.get("statusCode").getAsInt();
                    if (method.equals("login") && status == 200) {
                        token = response.getAsJsonObject("data").get("token").getAsString();
                        username = JsonProtocol.string(data, "username");
                        System.out.println("[CLIENTE] Login realizado.");
                    } else if (((method.equals("logout") || method.equals("deleteuser")) && status == 200)
                            || (status == 401 && "Sessão expirada ou encerrada.".equals(JsonProtocol.string(response, "message")))) {
                        token = null;
                        username = null;
                        System.out.println("[CLIENTE] Sessão encerrada.");
                    }
                    return response;
                } catch (RuntimeException e) {
                    throw new IOException("Resposta inválida do servidor.", e);
                }
            }
        }
    }

    private JsonObject authenticatedData() {
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("username", username);
        return data;
    }

    public static void main(String[] args) {
        BufferedReader keyboard = ConsoleIO.initialize();
        try {
            String host = args.length > 0 ? args[0] : prompt(keyboard, "IP do servidor: ");
            int port = Integer.parseInt(args.length > 1 ? args[1] : prompt(keyboard, "Porta do servidor: "));
            Client client = new Client(host, port);
            while (true) {
                System.out.println("\n===== SISTEMA DE GARAGEM =====");
                if (client.token == null) System.out.println("1 - Cadastrar usuário\n2 - Login\n0 - Sair");
                else System.out.println("3 - Consultar meus dados\n4 - Alterar nome\n5 - Alterar senha\n6 - Excluir cadastro\n7 - Logout\n0 - Sair");
                String option = prompt(keyboard, "Opção: ");
                if (option == null) return;
                if (option.equals("0")) {
                    if (client.token != null) client.send("logout", client.authenticatedData());
                    return;
                }
                JsonObject data;
                String method;
                if (client.token == null && (option.equals("1") || option.equals("2"))) {
                    data = new JsonObject();
                    if (option.equals("1")) data.addProperty("name", prompt(keyboard, "Nome: "));
                    data.addProperty("username", prompt(keyboard, "Username: "));
                    data.addProperty("password", password(keyboard, "Password: "));
                    method = option.equals("1") ? "register" : "login";
                } else if (client.token != null) {
                    data = client.authenticatedData();
                    switch (option) {
                        case "3": method = "getuser"; break;
                        case "4":
                            method = "updateusername";
                            data.addProperty("name", prompt(keyboard, "Novo nome: "));
                            break;
                        case "5":
                            method = "updateuserpassword";
                            data.addProperty("oldPassword", password(keyboard, "Senha atual: "));
                            data.addProperty("newPassword", password(keyboard, "Nova senha: "));
                            break;
                        case "6":
                            if (!"EXCLUIR".equals(prompt(keyboard, "Digite EXCLUIR para confirmar: "))) continue;
                            method = "deleteuser";
                            break;
                        case "7": method = "logout"; break;
                        default: System.out.println("Opção inválida."); continue;
                    }
                } else {
                    System.out.println("Opção inválida.");
                    continue;
                }
                try {
                    JsonObject response = client.send(method, data);
                    System.out.println(response.get("statusCode") + " - " + response.get("message").getAsString());
                    if (method.equals("getuser") && response.get("statusCode").getAsInt() == 200)
                        System.out.println(JsonProtocol.GSON.toJson(response.get("data")));
                } catch (IOException e) {
                    System.out.println("[CLIENTE] Erro de comunicação: " + e.getMessage());
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[CLIENTE] " + e.getMessage());
        }
    }

    private static String prompt(BufferedReader keyboard, String label) throws IOException {
        System.out.print(label);
        return keyboard.readLine();
    }
    private static String password(BufferedReader keyboard, String label) throws IOException {
        Console console = System.console();
        if (console == null) return prompt(keyboard, label);
        char[] value = console.readPassword("%s", label);
        if (value == null) return null;
        String result = new String(value);
        java.util.Arrays.fill(value, '\0');
        return result;
    }
}
