package io.github.eduardo.dbeaver.aichat.core.mention;

import java.util.List;

/**
 * Completion result. When the query reaches a level not loaded yet, items is empty and one of the pending
 * fields names it: pendingContainers = load the children of this path ("" = connection root),
 * pendingTables = load the tables of this schema path ("" = a connection without schemas).
 */
public record Suggestions(List<Suggestion> items, String pendingContainers, String pendingTables) {

    static Suggestions of(List<Suggestion> items) {
        return new Suggestions(items, null, null);
    }
}
