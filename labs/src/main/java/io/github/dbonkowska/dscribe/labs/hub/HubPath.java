package io.github.dbonkowska.dscribe.labs.hub;

import java.net.URI;

/**
 * Whether a value is safe to hand to {@link HubClient} as a path.
 *
 * <p>Every API call appends the path to the hub's base URL and attaches the hub key, so the path
 * decides where the key goes. Appending is what makes the leading {@code /} the check that
 * matters: without it, {@code @elsewhere/x} turns the base URL's host into user info and sends the
 * request, key included, to {@code elsewhere}. A scheme or a leading {@code //} would be appended
 * harmlessly, but either means the value was written as an address rather than a path, and nothing
 * sensible happens when it is treated as one.
 *
 * <p>For a path that comes from outside, whether a bundle or a reply: one rule, because they all end
 * up in the same call.
 */
public final class HubPath {

    private HubPath() {
    }

    public static boolean isPlain(String value) {
        return value != null
                && value.startsWith("/")
                && !value.startsWith("//")
                && !value.contains("://")
                && parses(value);
    }

    /**
     * A path that cannot be turned into a URL — a space, a control character — would pass the
     * checks above and then fail at call time as a crash. Refused here instead, where it arrives, so
     * it reads as a refusal. The host is a placeholder: only whether the path parses is asked.
     */
    private static boolean parses(String path) {
        try {
            URI.create("https://hub.invalid" + path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
