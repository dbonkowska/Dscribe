package io.github.dbonkowska.dscribe.labs.s02e01;

import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One call, one whole evaluation cycle: read the inputs, submit the candidate against each row in
 * turn, stop at the first objection, and clear the judge's state afterwards.
 *
 * <p>Everything in that sentence could have been a separate tool, and that is the point of it not
 * being. A cycle is a dozen exchanges with the hub; if the model drove them itself, all twelve
 * would land in the conversation and it would spend every later turn re-reading responses it has
 * no use for. Here the fan-out is absorbed and what comes back is one verdict.
 *
 * <p>The inputs are re-read inside every cycle rather than carried between them. They change
 * underneath the run, so a cached copy is silently right the first time and wrong afterwards.
 */
final class CycleTool {

    private static final Logger log = LoggerFactory.getLogger(CycleTool.class);

    /**
     * The facts about the exercise this needs, all of them supplied from outside the repository.
     *
     * <p>Grouped into a record rather than spread across the constructor so that the runner's
     * wiring reads as one thing, and so that nothing about the exercise's shape — the file it
     * reads, the columns it expects, the words the judge answers in — is a constant in here.
     */
    record Spec(
            String dataFile,
            String idColumn,
            String descriptionColumn,
            String resetPrompt,
            Pattern failure,
            Pattern flag) {}

    private final HubClient client;
    private final ResilientHub hub;
    private final Rendering rendering;
    private final Spec spec;

    /** Every body the hub returned, so a reported result can be checked against what was said. */
    private final List<String> responses = new ArrayList<>();

    CycleTool(HubClient client, ResilientHub hub, Rendering rendering, Spec spec) {
        this.client = client;
        this.hub = hub;
        this.rendering = rendering;
        this.spec = spec;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Candidate> tool(String name, String description) {
        return new Tool<>(name, description, Candidate.class, args -> evaluate(args.template()));
    }

    List<String> responses() {
        return List.copyOf(responses);
    }

    /**
     * Anything thrown here becomes a tool result the model reads, so a candidate that cannot be
     * sent costs one iteration and nothing from the budget.
     */
    private ToolOutput evaluate(String template) {
        // before a single byte is spent: a template with nowhere to put a row would otherwise be
        // sent once per row, identical every time, and rejected far downstream of the mistake
        rendering.requirePlaceholders(template);

        List<Item> items = Rows.parse(
                client.downloadData(spec.dataFile()), spec.idColumn(), spec.descriptionColumn());
        log.info("cycle over {} row(s)", items.size());

        if (items.isEmpty()) {
            // Reachable: a file of headers and nothing else parses fine. Returning the bare
            // Verdict(0, null, null) would say nothing at all — no rows, no message, no way to
            // tell "there was nothing to send" from "something failed quietly".
            return ToolOutput.of(new Verdict(
                    0, "The downloaded file had no rows, so nothing was sent.", null));
        }

        // Rendered in full before anything is sent. Row descriptions differ in length, so a
        // candidate can fit for the first four rows and not the fifth — and rendering inside the
        // submission loop meant discovering that after four rows were already paid for, with the
        // throw escaping before any Verdict was built. The model would read a size complaint and
        // nothing about what it had just spent. Rendering is pure and costs a dozen token counts,
        // so doing it all up front makes the check total as well: a candidate that is over cap
        // only on the last row is now caught before the first submission rather than never.
        List<String> prompts = new ArrayList<>(items.size());
        for (Item item : items) {
            prompts.add(rendering.render(template, item));
        }

        int submitted = 0;
        String ending = null;
        String flag = null;

        try {
            for (int row = 0; row < items.size(); row++) {
                Item item = items.get(row);

                String response = hub.call("row " + item.id(), new Submission(prompts.get(row)));
                responses.add(response);
                submitted++;
                ending = response;

                log.info("  row {} -> {}", item.id(), oneLine(response));

                if (spec.failure().matcher(response).find()) {
                    // The rest are not sent, and the runs show why that is not merely thrift: one
                    // wrong classification zeroes the judge's balance and its progress counter
                    // together. Everything after it would submit into a dead session.
                    break;
                }

                Matcher found = spec.flag().matcher(response);
                if (found.find()) {
                    flag = found.group();
                    break;
                }
            }
        } finally {
            reset();
        }

        return ToolOutput.of(new Verdict(submitted, ending, flag));
    }

    /**
     * Cleanup rather than precondition — it runs on the success path, the rejection path and the
     * exception path alike, so the next cycle always starts from a known state.
     *
     * <p>Its own failure is swallowed on purpose. A {@code finally} that throws replaces whatever
     * was already propagating, which would turn a legible rejection into a confusing one; and a
     * reset that did not take announces itself soon enough, as the next cycle being refused for a
     * budget it did not spend.
     */
    private void reset() {
        try {
            responses.add(hub.call("reset", new Submission(spec.resetPrompt())));
        } catch (RuntimeException e) {
            log.warn("reset failed, the next cycle may start dirty: {}", e.getMessage());
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
