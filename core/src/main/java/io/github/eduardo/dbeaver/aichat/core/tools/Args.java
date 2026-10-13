package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class Args {

    private Args() {
    }

    static String optString(JsonObject args, String key) throws ToolException {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive()) {
            throw new ToolException("Parâmetro '" + key + "' deve ser texto.");
        }
        String value = e.getAsString();
        return value.isBlank() ? null : value;
    }

    static String requireString(JsonObject args, String key) throws ToolException {
        String value = optString(args, key);
        if (value == null) {
            throw new ToolException("Parâmetro obrigatório ausente: '" + key + "'.");
        }
        return value;
    }

    static Integer optInt(JsonObject args, String key) throws ToolException {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            throw new ToolException("Parâmetro '" + key + "' deve ser um número inteiro.");
        }
    }
}
