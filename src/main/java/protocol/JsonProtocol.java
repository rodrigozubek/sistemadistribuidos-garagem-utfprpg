package protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

/** JSON UTF-8, uma mensagem por linha, sempre incluindo data. */
public final class JsonProtocol {
    public static final Gson GSON = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();

    private JsonProtocol() { }

    public static String string(JsonObject object, String field) {
        if (object == null) return null;
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    public static String safeLog(JsonObject message) {
        JsonObject copy = message.deepCopy();
        JsonElement data = copy.get("data");
        if (data != null && data.isJsonObject()) {
            for (String field : new String[]{"password", "oldPassword", "newPassword", "token"}) {
                if (data.getAsJsonObject().has(field)) {
                    data.getAsJsonObject().addProperty(field, "[oculto]");
                }
            }
        }
        return GSON.toJson(copy);
    }
}
