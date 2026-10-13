package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;
import java.util.stream.Collectors;

final class ConnectionResolver {

    private ConnectionResolver() {
    }

    /** Matches by exact id first, then by name ignoring case; falls back to the default connection. */
    static ConnectionInfo resolve(DatabaseGateway gateway, String requested, String defaultId) throws ToolException {
        List<ConnectionInfo> all = gateway.listConnections();
        String key = requested != null ? requested : defaultId;
        if (key == null) {
            throw new ToolException("Nenhuma conexão informada e nenhuma conexão padrão selecionada. "
                    + "Conexões disponíveis: " + names(all));
        }
        for (ConnectionInfo c : all) {
            if (c.id().equals(key)) {
                return c;
            }
        }
        for (ConnectionInfo c : all) {
            if (c.name().equalsIgnoreCase(key)) {
                return c;
            }
        }
        throw new ToolException("Conexão não encontrada: " + key + ". Conexões disponíveis: " + names(all));
    }

    private static String names(List<ConnectionInfo> all) {
        return all.isEmpty() ? "(nenhuma)" : all.stream().map(ConnectionInfo::name).collect(Collectors.joining(", "));
    }
}
