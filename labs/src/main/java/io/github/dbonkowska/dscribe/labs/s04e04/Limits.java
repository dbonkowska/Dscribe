package io.github.dbonkowska.dscribe.labs.s04e04;

import tools.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * What the hub says a name may be, read from its help reply rather than written here: the rules
 * are the hub's, and a copy of them in Java would go on passing names after the hub changed.
 *
 * @param namePattern      what every file and directory name must match, whole
 * @param maxFileName      the longest a file name may be
 * @param maxDirectoryName the longest a directory name may be
 * @param maxDepth         how many levels a path may have below the root
 * @param uniqueNames      whether a name may appear only once anywhere in the tree
 */
record Limits(Pattern namePattern, int maxFileName, int maxDirectoryName, int maxDepth, boolean uniqueNames) {

    /** A field the reply lacks stops the run here, before the model is called. */
    static Limits from(JsonNode help) {
        JsonNode limits = help.path("limits");
        return new Limits(
                Pattern.compile(text(limits, "allowed_name_pattern")),
                number(limits, "max_file_name_length"),
                number(limits, "max_directory_name_length"),
                number(limits, "max_directory_depth"),
                flag(limits, "global_unique_names"));
    }

    private static String text(JsonNode limits, String field) {
        return present(limits, field).asString();
    }

    private static int number(JsonNode limits, String field) {
        return present(limits, field).asInt();
    }

    private static boolean flag(JsonNode limits, String field) {
        return present(limits, field).asBoolean();
    }

    private static JsonNode present(JsonNode limits, String field) {
        JsonNode value = limits.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    "The help reply has no limits." + field + ", so a name cannot be checked against it"
                            + " before the batch is sent. Help said: " + limits);
        }
        return value;
    }
}
