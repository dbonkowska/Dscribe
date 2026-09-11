package io.github.dbonkowska.dscribe.labs.s02e01;

import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
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

        List<Item> items = parse(client.downloadData(spec.dataFile()));
        log.info("cycle over {} row(s)", items.size());

        int submitted = 0;
        int accepted = 0;
        String ending = null;
        String flag = null;

        try {
            for (Item item : items) {
                String prompt = rendering.render(template, item);

                String response = hub.call("row " + item.id(), new Submission(prompt));
                responses.add(response);
                submitted++;
                ending = response;

                log.info("  row {} -> {}", item.id(), oneLine(response));

                if (spec.failure().matcher(response).find()) {
                    // the rest are not sent: the budget is shared across the cycle, and a
                    // candidate already known to be wrong buys nothing by being asked again
                    break;
                }
                accepted++;

                Matcher found = spec.flag().matcher(response);
                if (found.find()) {
                    flag = found.group();
                    break;
                }
            }
        } finally {
            reset();
        }

        return ToolOutput.of(new Verdict(submitted, accepted, ending, flag));
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

    /** Column names are the exercise's, so they arrive in the spec rather than being written here. */
    private List<Item> parse(String csv) {
        List<Item> items = new ArrayList<>();
        try (var parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {

            for (CSVRecord record : parser) {
                items.add(new Item(record.get(spec.idColumn()), record.get(spec.descriptionColumn())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the downloaded rows", e);
        }
        return items;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
