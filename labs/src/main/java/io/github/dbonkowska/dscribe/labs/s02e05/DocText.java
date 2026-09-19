package io.github.dbonkowska.dscribe.labs.s02e05;

/**
 * Turns a page written for a browser into the text a model should read.
 *
 * <p>The failure this prevents is quiet rather than loud. A stylesheet left in place is a page of
 * selectors and colour values that the model pays for on every round of the loop, reads as part of
 * its instructions, and can find nothing to do with. Nothing errors — the run simply costs more and
 * reasons over noise.
 *
 * <p>The prose is left whole. Trimming to the parts that look like a command table would risk
 * cutting the sentence stating which commands must precede which, and the model needs to know why
 * an order matters rather than only that a submission was refused.
 */
final class DocText {

    private DocText() {
    }

    static String strip(String html) {
        // whole blocks first: a stylesheet survives tag-stripping as ordinary text, because its
        // content sits between the tags rather than inside them
        String text = html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", "");

        // a space, not nothing: a table row written on one line would otherwise run its cells
        // together into a word that was never in the page
        text = text.replaceAll("(?s)<[^>]*>", " ");

        text = text.replaceAll("[ \\t]{2,}", " ");
        text = text.replaceAll("(?m)^[ \\t]+|[ \\t]+$", "");

        // every tag-only line has become an empty one, and blank runs are paid for as tokens
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.strip();
    }
}
