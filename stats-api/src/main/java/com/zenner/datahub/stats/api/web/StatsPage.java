package com.zenner.datahub.stats.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * One page of a keyset walk (openapi.yaml {@code StatsPage}). {@code nextCursor} is
 * always present and {@code null} on the last page — the only signal that a walk is
 * finished; a short page is not one.
 */
public record StatsPage(
        List<ObjectNode> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor, int limit) {

    public StatsPage {
        items = List.copyOf(items);
    }

    static StatsPage empty(int limit) {
        return new StatsPage(List.of(), null, limit);
    }
}
