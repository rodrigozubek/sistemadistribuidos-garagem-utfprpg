package server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import model.User;
import protocol.JsonProtocol;
import protocol.Response;
import java.io.IOException;
import java.util.*;

/** Serializa validação e alterações para evitar conflitos entre clientes. */
public final class UserService {
    private final UserRepository repository;
    private Map<String, User> users;
    private final Map<String, String> tokens = new HashMap<>();

    public UserService(UserRepository repository) throws IOException {
        this.repository = repository;
        this.users = repository.load();
    }

    public static boolean validName(String value) {
        return value != null && !value.isBlank() && value.length() <= 60 && value.matches("[\\p{L} ]+");
    }

    public static boolean validUsername(String value) {
        return value != null && value.matches("[a-z0-9._]{3,20}");
    }

    public static boolean validPassword(String value) {
        return validStoredPassword(value)
                && value.matches(".*[A-Z].*")
                && value.matches(".*[a-z].*")
                && value.matches(".*[0-9].*")
                && value.matches(".*[#.*&%$@!()_=+\\-].*");
    }

    /** Permite carregar cadastros anteriores à exigência das quatro categorias. */
    static boolean validStoredPassword(String value) {
        return value != null && value.matches("[A-Za-z0-9#.*&%$@!()_=+\\-]{8,20}");
    }

    public synchronized Response handle(JsonObject request) {
        if (request == null) return response(400, "Requisição inválida");
        String method = JsonProtocol.string(request, "method");
        if (method == null || !Set.of("register", "login", "logout", "getuser",
                "updateusername", "updateuserpassword", "deleteuser").contains(method)) {
            return response(400, "Operação inválida");
        }
        JsonElement element = request.get("data");
        if (element == null || !element.isJsonObject()) return response(400, "Dados da requisição inválidos");
        JsonObject data = element.getAsJsonObject();
        String token = JsonProtocol.string(data, "token");
        // Mesmo operações públicas rejeitam tokens não autorizados quando fornecidos.
        if (data.has("token") && (token == null || !tokens.containsKey(token))) {
            return response(401, "Sessão expirada ou encerrada.");
        }
        try {
            if (method.equals("register")) return register(data);
            if (method.equals("login")) return login(data);
            if (token == null) return response(400, "Token de autenticação não fornecido.");
            if (method.equals("logout")) {
                tokens.remove(token);
                return response(200, "Usuário deslogado com sucesso");
            }
            String username = JsonProtocol.string(data, "username");
            if (username == null) return response(400, "O campo username não está no padrão esperado.");
            if (!username.equals(tokens.get(token))) return response(401, "Operação não autorizada");
            User user = users.get(username);
            if (user == null) return response(404, "Usuário não encontrado");
            switch (method) {
                case "getuser":
                    JsonObject result = new JsonObject();
                    result.addProperty("name", user.getName());
                    result.addProperty("username", username);
                    return new Response(200, "Usuário encontrado com sucesso", result);
                case "updateusername":
                    String name = JsonProtocol.string(data, "name");
                    if (!validName(name)) return response(400, "Nome do usuário fora do padrão");
                    replace(username, new User(name, username, user.getPassword()));
                    return response(200, "Nome do usuário atualizado com sucesso");
                case "updateuserpassword":
                    if (!user.getPassword().equals(JsonProtocol.string(data, "oldPassword"))) {
                        return response(401, "Senha atual incorreta");
                    }
                    String password = JsonProtocol.string(data, "newPassword");
                    if (!validPassword(password)) return response(400, "Senha fora do padrão");
                    replace(username, new User(user.getName(), username, password));
                    return response(200, "Senha atualizada com sucesso");
                case "deleteuser":
                    replace(username, null);
                    tokens.values().removeIf(username::equals);
                    return response(200, "Usuário deletado com sucesso");
                default: return response(400, "Operação inválida");
            }
        } catch (IOException e) {
            System.err.println("[SERVIDOR] Falha ao persistir usuários: " + e.getMessage());
            return response(500, "Erro interno do servidor");
        }
    }

    private Response register(JsonObject data) throws IOException {
        String username = JsonProtocol.string(data, "username");
        if (username != null && users.containsKey(username)) return response(409, "O username já está em uso.");
        String name = JsonProtocol.string(data, "name");
        String password = JsonProtocol.string(data, "password");
        for (String field : new String[]{"name", "username", "password"}) {
            boolean valid = switch (field) {
                case "name" -> validName(name);
                case "username" -> validUsername(username);
                default -> validPassword(password);
            };
            if (!valid) return response(400, "O campo " + field + " não está no padrão esperado.");
        }
        User user = new User(name, username, password);
        repository.create(user);
        users.put(username, user);
        return response(201, "Usuário criado com sucesso");
    }

    private Response login(JsonObject data) {
        String username = JsonProtocol.string(data, "username");
        String password = JsonProtocol.string(data, "password");
        if (username == null || password == null) return response(400, "Dados de login inválidos");
        User user = users.get(username);
        if (user == null || !user.getPassword().equals(password)) return response(401, "Usuario ou senhas inválidos");
        String token = UUID.randomUUID().toString();
        tokens.put(token, username);
        JsonObject result = new JsonObject();
        result.addProperty("token", token);
        return new Response(200, "Sucesso no Login", result);
    }

    private void replace(String username, User user) throws IOException {
        Map<String, User> next = new LinkedHashMap<>(users);
        if (user == null) next.remove(username); else next.put(username, user);
        repository.replace(username, user);
        users = next;
    }

    private static Response response(int code, String message) { return new Response(code, message, null); }
}
