package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends the run's result to the hub, taking nothing from the model to do it.
 *
 * <p>The code is read from what the shell replied. A model asked to retype a code it saw a moment
 * ago will sometimes write a slightly different one, and the refusal that follows reads as a wrong
 * answer rather than as a copying slip. So the tool takes no arguments and the runner supplies the
 * value: the model decides <em>when</em> the work is done, and has no say in what is sent.
 *
 * <p>Nothing is shared with an earlier lesson's tool: a type imported from a finished lesson would
 * freeze it.
 */
final class SubmitTool {

    /** The whole schema the model sees: nothing. */
    record Submit() {}

    /** One send to the hub — {@code ResilientHub::call}, and a recording lambda in tests. */
    @FunctionalInterface
    interface Send {
        String send(String label, String answer);
    }

    private final Supplier<List<String>> replies;
    private final Pattern codePattern;
    private final Send hub;

    private final List<String> responses = new ArrayList<>();
    private int submissions;

    /**
     * @param replies     what the shell tool has replied so far, read at each call rather than once
     * @param codePattern what the code looks like inside a reply
     * @param hub         where the code is sent
     */
    SubmitTool(Supplier<List<String>> replies, Pattern codePattern, Send hub) {
        this.replies = replies;
        this.codePattern = codePattern;
        this.hub = hub;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Submit> tool(String name, String description) {
        return new Tool<>(name, description, Submit.class, args -> ToolOutput.of(submit()));
    }

    /** Every reply the hub gave to a submission, in order — what the stop condition reads. */
    List<String> responses() {
        return List.copyOf(responses);
    }

    /** Refuses as text the model can act on: nothing was sent, and what to do about it. */
    private String submit() {
        String code = latestCode()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nothing has produced a code yet. Nothing was sent. Keep working until a reply"
                                + " contains one, then submit."));

        submissions++;
        String response = hub.send("submission " + submissions, code);
        responses.add(response);
        return response;
    }

    /** Newest first: when the environment has printed more than one, the last is the one just earned. */
    private Optional<String> latestCode() {
        List<String> all = replies.get();
        for (int i = all.size() - 1; i >= 0; i--) {
            Matcher match = codePattern.matcher(all.get(i));
            if (match.find()) {
                return Optional.of(match.group());
            }
        }
        return Optional.empty();
    }
}
