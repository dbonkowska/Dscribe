package io.github.dbonkowska.dscribe.tool;

/**
 * What a handler produces: what the model reads back, and optionally an image it should be
 * shown.
 *
 * <p>The two are separate because a {@code role=tool} message cannot carry image content — the
 * provider's schema has no place for it — so an image has to reach the model as a message of its
 * own. A handler says only that there is one; {@link Toolbox} decides what that message looks
 * like.
 *
 * @param result what the model reads back, serialised to JSON unless it is already a String
 * @param image  an image to show alongside it, or null
 */
public record ToolOutput(Object result, ImageRef image) {

    /** The ordinary case: something to read, nothing to look at. */
    public static ToolOutput of(Object result) {
        return new ToolOutput(result, null);
    }
}
