package io.github.dbonkowska.dscribe.labs.s03e05;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools a search has found, by name, and the only place a name becomes an address.
 *
 * <p>The model never writes an address: it names a tool, and the name is looked up here. So what
 * this accepts is exactly the set of places the hub key can be sent, and every check sits on the
 * way in, where a refused tool has cost nothing yet, rather than on the way out.
 *
 * <p>A refusal is a line of text, not an exception. The search that carried the tool still
 * succeeded, and the model reads the refusal beside the reply, so it knows why a name it can see
 * will not resolve.
 *
 * <p>Nothing here reads the reply's status code. What a miss or an error looks like has not been
 * seen yet, so a reply without a list of tools registers nothing and the model reads it as it
 * came.
 */
final class ToolRegistry {

    /** The one argument every call sends. A tool that reads another would receive nothing. */
    static final String PARAMETER = "query";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Insertion-ordered, so the names the model is shown follow the order it found them in. */
    private final Map<String, String> pathByName = new LinkedHashMap<>();

    /** Names a search returned but this refused, with the reason — see {@link #refusalOf}. */
    private final Map<String, String> refusalByName = new HashMap<>();

    /** Registers every acceptable tool in a search reply, and says why each other one was not. */
    List<String> register(String reply) {
        JsonNode tools;
        try {
            tools = MAPPER.readTree(reply).path("tools");
        } catch (JacksonException e) {
            return List.of();
        }
        if (!tools.isArray()) {
            return List.of();
        }

        List<String> refusals = new ArrayList<>();
        for (JsonNode tool : tools) {
            refusal(tool).ifPresent(refusals::add);
        }
        return refusals;
    }

    Optional<String> pathOf(String name) {
        return Optional.ofNullable(pathByName.get(name));
    }

    /**
     * Why a name a search did return was not registered. A call by that name is answered with this
     * rather than "search first", which would be untrue and would end in the same refusal.
     */
    Optional<String> refusalOf(String name) {
        return Optional.ofNullable(refusalByName.get(name));
    }

    List<String> known() {
        return List.copyOf(pathByName.keySet());
    }

    /** Registers one tool, or says why not. Checked in the order that decides where the key goes. */
    private Optional<String> refusal(JsonNode tool) {
        // Kept exactly as the reply wrote it: the model copies the name it was shown, and that
        // string is the one that has to resolve.
        String name = tool.path("name").asString("");
        String url = tool.path("url").asString("");
        String parameter = tool.path("parameter").asString("");

        if (name.isBlank()) {
            return Optional.of("A tool at " + url + " was not registered: it has no name to call it by.");
        }
        if (!HubPath.isPlain(url)) {
            return refused(name, name + " was not registered: its url " + url + " is not a plain path"
                    + " on the hub, and only those are called.");
        }
        if (!PARAMETER.equals(parameter)) {
            return refused(name, name + " was not registered: it takes the parameter \"" + parameter
                    + "\", and every call sends \"" + PARAMETER + "\".");
        }

        refusalByName.remove(name);
        String known = pathByName.putIfAbsent(name, url);
        if (known != null && !known.equals(url)) {
            return Optional.of(name + " was found again at " + url + ", but is already registered at "
                    + known + ". The first address is kept.");
        }
        return Optional.empty();
    }

    private Optional<String> refused(String name, String reason) {
        refusalByName.put(name, reason);
        return Optional.of(reason);
    }
}
