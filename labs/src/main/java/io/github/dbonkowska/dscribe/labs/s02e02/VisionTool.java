package io.github.dbonkowska.dscribe.labs.s02e02;

import io.github.dbonkowska.dscribe.conversation.ContentPart;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A second model, behind a tool boundary: it looks at something the planning model cannot read
 * and hands back words.
 *
 * <p>The delegation is the point. Reading pixels and planning over what was read are different
 * jobs, and one model chosen for both is chosen badly for at least one of them. What crosses the
 * boundary is text, so the planning loop never carries an image and never pays for one twice.
 *
 * <p>The artefact is fetched fresh on every call and sent as content rather than as a URL. Its
 * bytes change underneath the run, so a link would let the provider fetch a state other than the
 * one this call meant to show it — later, and with nothing able to tell afterwards that it had.
 */
final class VisionTool {

    private static final Logger log = LoggerFactory.getLogger(VisionTool.class);

    /**
     * No components: the model calls this to look, and there is nothing for it to choose. The
     * artefact's name is the exercise's, not the model's, so it comes from the bundle.
     */
    record Look() {}

    /**
     * @param dataFile  what to read at the hub
     * @param mediaType what those bytes are, for the provider — the exercise's format, so it is
     *                  supplied rather than guessed from the name
     */
    record Spec(String dataFile, String mediaType) {}

    private final LlmClient llm;
    private final HubClient hub;
    private final String prompt;
    private final Spec spec;

    VisionTool(LlmClient llm, HubClient hub, String prompt, Spec spec) {
        this.llm = llm;
        this.hub = hub;
        this.prompt = prompt;
        this.spec = spec;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Look> tool(String name, String description) {
        return new Tool<>(name, description, Look.class, args -> read());
    }

    /**
     * What comes back is passed on verbatim — unparsed, unchecked, not retried.
     *
     * <p>A retry loop in here would spend model calls the agent's iteration cap cannot see, and
     * that cap is the only thing making a run's cost readable from its own record. A bad reading
     * is therefore the planning model's problem, which is the right place for it: that model can
     * look again, and it is the one that knows whether what it was told makes sense.
     */
    private ToolOutput read() {
        byte[] bytes = hub.downloadBytes(spec.dataFile());
        log.info("read {} · {} bytes", spec.dataFile(), bytes.length);

        // no text part beside the image: a data URI repeated as text would be the whole payload
        // again, in a field nothing reads
        List<Message> conversation = List.of(
                new Message(Role.system, prompt),
                new Message(
                        Role.user,
                        List.of(ContentPart.image(spec.mediaType(), bytes)),
                        null,
                        null));

        // nulls rather than an empty list: this asks for prose, and a request carrying
        // "tools": [] with a tool_choice is a different thing to answer
        return ToolOutput.of(llm.send(conversation, null, null).message().text());
    }
}
