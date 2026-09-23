package io.github.dbonkowska.dscribe.labs.s03e05;

/**
 * Whether a value is safe to hand to {@code HubClient.post} as a path.
 *
 * <p>{@code post} appends the path to the hub's base URL and merges the hub key into the body, so
 * the path decides where the key goes. Appending is what makes the leading {@code /} the check that
 * matters: without it, {@code @elsewhere/x} turns the base URL's host into user info and sends the
 * request, key included, to {@code elsewhere}. A scheme or a leading {@code //} would be appended
 * harmlessly, but either means the value was written as an address rather than a path, and nothing
 * sensible happens when it is treated as one.
 *
 * <p>Used for the search path from the bundle and for every path a search reply hands back: one
 * rule, because both end up in the same call.
 */
final class HubPath {

    private HubPath() {
    }

    static boolean isPlain(String value) {
        return value != null
                && value.startsWith("/")
                && !value.startsWith("//")
                && !value.contains("://");
    }
}
