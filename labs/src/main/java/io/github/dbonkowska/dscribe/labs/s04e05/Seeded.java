package io.github.dbonkowska.dscribe.labs.s04e05;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/**
 * The orders that existed before the run: read once, right after the startup reset, and handed to
 * {@link ApiTool} as the ids the model may not delete.
 *
 * <p>Strict on purpose. A reply read leniently comes back as an empty set, which protects nothing
 * and looks exactly like a start with no seeded orders, so the guard would pass every delete
 * without anything saying why. The only honest empty result is an empty list.
 */
final class Seeded {

    /** A protocol name, like {@code tool} and {@code action}: the same one the delete takes. */
    private static final String ID = "id";

    private Seeded() {}

    /**
     * @param reply     the list reply, parsed
     * @param listField the field holding the orders, from {@code task.properties}
     */
    static Set<String> ids(JsonNode reply, String listField) {
        JsonNode list = reply.get(listField);
        if (list == null || !list.isArray()) {
            throw new IllegalStateException(
                    "The seeded orders could not be read: the reply has no " + listField + " array. Check"
                            + " orders.list in the lesson's task.properties against the reply in the"
                            + " transcript.");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode order : list) {
            JsonNode id = order.get(ID);
            if (id == null || !id.isString()) {
                throw new IllegalStateException(
                        "The seeded orders could not be read: an entry in " + listField + " has no text "
                                + ID + ": " + order + ". Without every id, the delete guard would leave"
                                + " that order unprotected.");
            }
            ids.add(id.asString());
        }
        return ids;
    }
}
