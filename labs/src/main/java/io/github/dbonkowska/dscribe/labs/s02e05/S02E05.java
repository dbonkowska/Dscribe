package io.github.dbonkowska.dscribe.labs.s02e05;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.conversation.ContentPart;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Toolbox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first lesson whose answer is a plan rather than a value.
 *
 * <p>Every runner so far reported something it found. This one emits an ordered sequence of
 * commands for an outside system to execute, in a command language it learns at run time from
 * documentation it fetches. The run never watches the sequence execute, so the hub is the only
 * oracle and its rejections are the only feedback.
 *
 * <p>Two independent things can be wrong — the commands, and a coordinate read off an image — and a
 * rejection distinguishes them for nobody. So the coordinate is taken out of the model's hands: a
 * delegated call reads it into named fields, two checks it cannot see decide whether that reading
 * is believable, and the runner renders the one command carrying it. The model still writes the
 * sequence and chooses its order, which is what the lesson is actually about.
 */
public class S02E05 {

    /**
     * The planning half is ordering a dozen short commands against documentation it has just been
     * handed — following instructions rather than reasoning at length.
     *
     * <p>Earned the flag on the second run, in seventeen turns: sixteen sequences written, eight of
     * them refused here and never sent. One refusal was a command the bundle deliberately keeps out
     * of the allowlist, because it resets the configuration and would have discarded everything set
     * before it. Three were the ordering precondition — the terminal command written before the
     * things it depends on. The reserved command was never contested: every sequence carried it
     * exactly as it was handed over.
     *
     * <p>The first run failed for a reason no guard here addresses. The model described its plan in
     * prose, with a destination it had invented rather than the one it was given, and never called
     * the tool at all — so nothing was validated, because nothing was submitted. Two sentences in
     * {@code system.md} fixed it. That is this lesson's own subject arriving in the one place the
     * framework has no say over.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and
     * {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "google/gemini-3-flash-preview";

    /**
     * The delegated half, and the load-bearing one. Recognising the target is easy; counting which
     * cell of a grid it sits in is not, and a miscount here produces a perfectly well-formed
     * sequence that acts somewhere else. Pinned separately through {@code openrouter.vision.model}
     * so an override meant for the planning model cannot reach it.
     *
     * <p>Read the sector this lesson needed on the first attempt, both times: the two readings
     * agreed with each other and with the grid, so the second one bought nothing either run. It is
     * kept because what it guards against — a reading that is unsteady rather than confidently
     * wrong — is invisible until it happens, and the run that pays for it is the one that would
     * otherwise submit a coordinate nothing confirmed. Worth revisiting on a finer grid than this
     * lesson's, where counting is harder and a disagreement is likelier to be real.
     */
    private static final String VISION_MODEL = "google/gemini-3-flash-preview";

    /** Submit, read the refusal, correct, submit again. A dozen rounds is generous for that. */
    private static final int MAX_ITERATIONS = 20;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    /** What the next lesson may read. Written from replies the hub actually returned. */
    record Answer(String flag, String sector, int targetColumn, int targetRow) {}

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);
        LlmClient vision = new LlmClient(labsConfig.llm().vision()).defaultModel(VISION_MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s02e05");
        TaskParams task = lesson.task(TaskParams.class);
        Artifacts artifacts = Artifacts.of(labsConfig.dataDir(), "s02e05");

        // Checked here, before a transcript is open and before anything has been fetched or read:
        // it needs neither real value, and a template that cannot carry them is a run that cannot
        // succeed. Same boundary-refusal discipline TaskParams applies to every key it binds.
        String userTemplate = requireBothSlots(lesson.prompt("user.md"));

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("vision model", vision.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("grid", task.grid().columns() + " x " + task.grid().rows());
        settings.put("read attempts", task.grid().readAttempts() + " x 2 readings");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s02e05",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);
            LlmClient recordedVision = vision.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            // Code's call rather than the model's: there is one page and one moment to read it, and
            // asking for it would cost a round. Both files are cached — HubClient.fetch returns
            // early when the local copy is there — so re-running costs nothing.
            Path docFile = hub.fetch(task.docUrl(), artifacts.file("api.html"));
            String documentation = DocText.strip(read(docFile));

            Path imageFile = hub.fetchData(task.image().file(), artifacts.file(task.image().file()));
            measure(imageFile);

            // Sent as content rather than as a URL. The image is static, which by itself argues for
            // a URL — but that URL carries the hub API key in its path, and a link would hand a
            // credential to the provider and write it into the request body.
            List<Message> look = List.of(
                    new Message(Role.system, lesson.prompt("vision/system.md")),
                    new Message(Role.user, List.of(
                            ContentPart.image(task.image().mediaType(), readBytes(imageFile))), null, null));

            ResponseFormat reading = ResponseFormat.jsonSchema(
                    "reading", SchemaUtils.from(GridReader.Reading.class));

            GridReader.Reading accepted = new GridReader(
                    task.grid(),
                    () -> recordedVision.sendStructured(look, reading, GridReader.Reading.class))
                    .read();

            // Column first, then row, in the order the template's slots are written. The order is
            // fixed here rather than anywhere the model can see it, which is the whole point of
            // assembling this command instead of asking for it.
            String sector = task.dsl().reservedTemplate()
                    .formatted(accepted.targetColumn(), accepted.targetRow());

            System.out.println("Sector: " + sector);

            Commands commands = new Commands(task.dsl(), sector);
            SubmitTool submit = new SubmitTool(resilient::call, commands);

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, userTemplate.formatted(documentation, sector)));

            Toolbox tools = new Toolbox(List.of(
                    submit.tool(task.submit().name(), task.submit().description())));

            System.out.println("Model: " + llm.model() + " · vision: " + vision.model());

            // Ends on the model's own turn, or as soon as a reply has carried the result. Asked of
            // an assistant turn before its calls are dispatched, so a submission the model writes
            // after the hub has already accepted one is never made: there is nothing left to earn,
            // and each of those costs a real call. Leaving its tool calls unanswered is safe here —
            // nothing seeds another run from this conversation.
            Pattern flagPattern = Pattern.compile(task.flagPattern());
            new Agent(recorded, tools, MAX_ITERATIONS).run(seed,
                    turn -> !turn.hasToolCalls() || findFlag(submit.responses(), flagPattern) != null);

            String flag = findFlag(submit.responses(), flagPattern);
            if (flag == null) {
                throw new IllegalStateException(
                        "No reply matched " + task.flagPattern() + " across " + submit.responses().size()
                                + " submission(s). The run ended without the hub accepting a sequence.");
            }

            artifacts.write("answer.json",
                    new Answer(flag, sector, accepted.targetColumn(), accepted.targetRow()));

            System.out.println(flag);
            transcript.outcome("Read sector " + sector + " and earned `" + flag + "` across "
                    + submit.responses().size() + " submission(s).");
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * The result is taken from what the hub returned, never from what the model says about it.
     * Nothing has to be checked against a report, because there is no report: a run that never saw
     * the result in a reply simply has not earned it.
     *
     * @return the result as the hub wrote it, or null where no reply has carried one yet
     */
    private static String findFlag(List<String> responses, Pattern flag) {
        for (String response : responses) {
            Matcher matcher = flag.matcher(response);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    /**
     * Checked by rendering it with markers, never with the real values.
     *
     * <p>{@code String.formatted} discards an argument it has nowhere to put, so a template that
     * lost its second slot renders cleanly and never tells the model the one command it is not
     * allowed to choose — after which every submission it writes is refused for a coordinate it had
     * no way to know.
     *
     * <p>The markers matter more here than anywhere else in this repo. One of the slots is filled
     * with a page of documentation <em>about this command language</em>, and that page carries a
     * worked example of the very command being checked. Testing for the real value would let the
     * documentation satisfy the check on the run where the reading happens to match the example —
     * a live draw, not a theoretical one, on a grid this small. A marker cannot occur by accident
     * in the rendered output; a plausible value can, and here it is likely to.
     *
     * <p>It checks the first slot too, which testing the real value never did: a template written
     * with only the second would hand the model a coordinate and no documentation, and nothing
     * would say so.
     */
    private static String requireBothSlots(String template) {
        String documentation = "<<documentation>>";
        String sector = "<<sector>>";
        String probe;
        try {
            probe = template.formatted(documentation, sector);
        } catch (IllegalFormatException e) {
            throw new IllegalStateException(
                    "user.md is not a valid format string: " + e.getMessage()
                            + ". Write a literal percent sign as %%.", e);
        }
        if (!probe.contains(documentation) || !probe.contains(sector)) {
            throw new IllegalStateException(
                    "user.md must contain two %s: the fetched documentation, then the command the run"
                            + " has already decided. Rendering it dropped one, which no reader of the"
                            + " prompt would see and every submission would then be refused for.");
        }
        return template;
    }

    /**
     * Logged before anything decides about tiling or zooming. A grid counted off a downscaled image
     * is miscounted for a reason nothing in the transcript would otherwise show.
     */
    private static void measure(Path file) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                System.out.println("Image: " + file.getFileName() + " (no reader for this format)");
                return;
            }
            System.out.println("Image: " + image.getWidth() + " x " + image.getHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot measure " + file, e);
        }
    }

    /** UTF-8 named explicitly: the platform default would mangle the documentation's diacritics. */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
