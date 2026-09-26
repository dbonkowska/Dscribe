package io.github.dbonkowska.dscribe.labs.s04e02;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * What a collection gathered: one result per expected key, and every result that belonged to no
 * expected key, kept for the transcript.
 */
public record Collected<K>(Map<K, JsonNode> results, List<JsonNode> unexpected) {}
