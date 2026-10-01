package io.github.dbonkowska.dscribe.labs.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.llm.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record of a run that has already been paid for. Every failure here is one you only notice
 * when you need the file and it is missing, truncated, or — worst — carries an API key into a
 * directory you later share. It also must never be the reason a run dies: a diagnostic that
 * throws takes down the thing it was added to explain.
 *
 * <p>Fixtures are invented; no lesson supplies them.
 */
class RunTranscriptTest {

    private static final Map<String, String> SETTINGS = settings();

    /** A fresh directory per test; {@code root()} is the logs directory inside it. */
    @TempDir
    Path temp;

    private static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", "some/model");
        settings.put("max iterations", "12");
        return settings;
    }

    private Path root() {
        return temp.resolve("logs");
    }

    private RunTranscript open(Path logs) {
        return RunTranscript.open(logs, "x01", SETTINGS, List.of());
    }

    private static String contents(RunTranscript transcript) throws IOException {
        return Files.readString(transcript.file(), StandardCharsets.UTF_8);
    }

    @Test
    void namesTheFileForTheLessonAndTheMomentItRan() {
        String name = open(root()).file().getFileName().toString();

        // x01-yyyy-MM-ddTHH-mm-ss.md — dashes in the time because Windows forbids ':' in a filename
        assertTrue(name.startsWith("x01-"), () -> name);
        assertTrue(name.endsWith(".md"), () -> name);
        assertEquals("x01-yyyy-MM-ddTHH-mm-ss.md".length(), name.length(), () -> name);
    }

    @Test
    void opensWithTheSettingsTheRunWasGiven() throws IOException {
        String written = contents(open(root()));

        assertTrue(written.contains("# x01"), () -> written);
        assertTrue(written.contains("| model | some/model |"), () -> written);
        assertTrue(written.contains("| max iterations | 12 |"), () -> written);
    }

    @Test
    void createsTheLogsDirectoryOnFirstUse() {
        assertTrue(Files.exists(open(root()).file()), "expected the transcript to exist");
    }

    @Test
    void writesUtf8RegardlessOfThePlatformDefault() throws IOException {
        Map<String, String> diacritics = new LinkedHashMap<>();
        diacritics.put("lesson", "zażółć gęślą jaźń");

        RunTranscript transcript = RunTranscript.open(root(), "x01", diacritics, List.of());

        assertTrue(contents(transcript).contains("zażółć gęślą jaźń"), "expected UTF-8 on disk");
    }

    @Test
    void reportsAnUnwritableTargetRatherThanThrowing() throws IOException {
        // a regular file where the logs directory should be: createDirectories cannot win
        Path blocked = Files.writeString(root(), "not a directory", StandardCharsets.UTF_8);

        RunTranscript transcript = RunTranscript.open(blocked, "x01", SETTINGS, List.of());

        assertEquals("not a directory", Files.readString(blocked, StandardCharsets.UTF_8));
        assertTrue(transcript.file().toString().contains("x01-"), "still names a file it wanted");
    }

    @Test
    void writesSecretsAsStarsWhereverTheyAppear() throws IOException {
        Map<String, String> leaky = new LinkedHashMap<>();
        leaky.put("base url", "https://api.test/v1/s3cr3t-key/data");

        RunTranscript transcript =
                RunTranscript.open(root(), "x01", leaky, List.of("s3cr3t-key", "hub-k3y"));

        String written = contents(transcript);
        assertFalse(written.contains("s3cr3t-key"), () -> written);
        assertTrue(written.contains("***"), () -> written);
    }

    @Test
    void ignoresABlankSecretRatherThanRedactingEverything() throws IOException {
        // "".replace() would match at every position and turn the file into stars
        RunTranscript transcript = RunTranscript.open(root(), "x01", SETTINGS, List.of("", "   "));

        assertTrue(contents(transcript).contains("| model | some/model |"), "expected the file intact");
    }

    private static final String TOOL_CALL_RESPONSE =
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                    + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}]}}]}";

    private static final String TEXT_RESPONSE =
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"thinking\"}}]}";

    private static String request(String messages) {
        return "{\"model\":\"some/model\",\"messages\":[" + messages + "],\"tools\":[]}";
    }

    @Test
    void namesEveryToolCallOfTheTurnOnTheSkimLine() throws IOException {
        RunTranscript transcript = open(root());

        transcript.append(request("{\"role\":\"user\",\"content\":\"go\"}"), TOOL_CALL_RESPONSE);

        String written = contents(transcript);
        assertTrue(written.contains("## turn 1 · model"), () -> written);
        assertTrue(written.contains("lookup {\"q\":\"x\"}"), () -> written);
    }

    /**
     * A run now talks to more than one model, so a turn that does not say which one served it
     * leaves the file ambiguous exactly where it matters — a delegated read and the planning
     * turn that asked for it sit next to each other.
     */
    @Test
    void namesTheModelThatActuallyServedTheTurn() throws IOException {
        // the provider can route elsewhere than the request asked, and what answered is the fact
        // worth keeping: a run that reads badly is otherwise blamed on the model nobody called
        RunTranscript transcript = open(root());

        transcript.append(
                "{\"model\":\"asked/model\",\"messages\":[{\"role\":\"user\",\"content\":\"go\"}],"
                        + "\"tools\":[]}",
                "{\"model\":\"served/model\",\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");

        String written = contents(transcript);
        assertTrue(written.contains("## turn 1 · model · served/model"), () -> written);
    }

    @Test
    void fallsBackToTheModelItAskedForWhenTheResponseNamesNone() throws IOException {
        RunTranscript transcript = open(root());

        transcript.append(request("{\"role\":\"user\",\"content\":\"go\"}"), TEXT_RESPONSE);

        String written = contents(transcript);
        assertTrue(written.contains("## turn 1 · model · some/model"), () -> written);
    }

    @Test
    void marksATurnThatProducedNoToolCalls() throws IOException {
        RunTranscript transcript = open(root());

        transcript.append(request("{\"role\":\"user\",\"content\":\"go\"}"), TEXT_RESPONSE);

        assertTrue(contents(transcript).contains("*(no tool calls)*"), "expected the empty turn marked");
    }

    @Test
    void rendersTheSeedConversationReadablyRatherThanAsEscapedJson() throws IOException {
        RunTranscript transcript = open(root());

        transcript.append(
                request("{\"role\":\"system\",\"content\":\"line one\\nline two\"}"), TEXT_RESPONSE);

        String written = contents(transcript);
        assertTrue(written.contains("**system**"), () -> written);
        assertTrue(written.contains("> line one"), () -> written);
        assertTrue(written.contains("> line two"), () -> written);
    }

    @Test
    void rendersOnlyTheMessagesNewToEachTurn() throws IOException {
        RunTranscript transcript = open(root());
        String seed = "{\"role\":\"user\",\"content\":\"first\"}";

        transcript.append(request(seed), TOOL_CALL_RESPONSE);
        transcript.append(
                request(seed + ",{\"role\":\"assistant\",\"content\":null}"
                        + ",{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"42\"}"),
                TEXT_RESPONSE);

        String written = contents(transcript);
        // the seed appears once, not once per turn
        assertEquals(1, written.split("> first", -1).length - 1, () -> written);
        assertTrue(written.contains("**tool**"), () -> written);
        assertTrue(written.contains("> 42"), () -> written);
    }

    private static final String DELEGATED_REQUEST =
            "{\"model\":\"vision/model\",\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"describe it\"},"
                    + "{\"role\":\"user\",\"content\":\"look\"}]}";

    private static final String DELEGATED_RESPONSE =
            "{\"model\":\"vision/model\",\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"it reads as x\"}}]}";

    private static String toolTurn(String id, String content) {
        return "{\"role\":\"assistant\",\"content\":null},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"" + id + "\",\"content\":\"" + content + "\"}";
    }

    /**
     * Two main turns, a delegated exchange, then a third main turn.
     *
     * <p>The second main turn is what makes this sequence able to fail. A delegated call carries
     * two messages; by the time it happens the main conversation has rendered three, so a shared
     * cursor is dragged *backwards* and the next main turn re-renders what it already wrote. With
     * only one main turn first the cursor moves forward by one and the damage is invisible —
     * which it was, in the first version of these tests.
     */
    private RunTranscript interleaved() {
        RunTranscript transcript = open(root());
        String seed = "{\"role\":\"user\",\"content\":\"first\"}";
        String second = seed + "," + toolTurn("call_1", "42");
        String third = second + "," + toolTurn("call_2", "99");

        transcript.append(request(seed), TOOL_CALL_RESPONSE);
        transcript.append(request(second), TOOL_CALL_RESPONSE);
        transcript.delegated("vision").append(DELEGATED_REQUEST, DELEGATED_RESPONSE);
        transcript.append(request(third), TEXT_RESPONSE);

        return transcript;
    }

    @Test
    void recordsADelegatedExchangeUnderTheTurnThatCausedIt() throws IOException {
        String written = contents(interleaved());

        int cause = written.indexOf("## turn 2 · model");
        int delegated = written.indexOf("## turn 2 · vision · vision/model");
        int next = written.indexOf("## turn 3 · model");

        assertTrue(delegated >= 0, () -> written);
        assertTrue(delegated > cause, "the delegated block belongs under the turn that caused it");
        assertTrue(next > delegated, "and before the turn that read its result");
    }

    /**
     * The reason this is a second rendering path rather than a second caller of {@code append}.
     * That method keeps a cursor over one growing conversation; a delegated exchange is a
     * different, shorter conversation, and sharing the cursor rewinds it — after which the main
     * loop's new messages are skipped and never appear in the file at all.
     */
    @Test
    void leavesTheMainConversationsRenderingCursorAlone() throws IOException {
        String written = contents(interleaved());

        // dragged backwards: the turn after re-renders what it already wrote
        assertEquals(1, written.split("> first", -1).length - 1,
                () -> "the seed must be rendered exactly once: " + written);
        assertEquals(1, written.split("> 42", -1).length - 1,
                () -> "an already-written result must not be rendered a second time: " + written);
        // dragged forwards: the turn after is skipped entirely
        assertTrue(written.contains("> 99"),
                "the turn after a delegated exchange must still be rendered");
    }

    @Test
    void showsWhatTheDelegatedCallWasAskedAndWhatItAnswered() throws IOException {
        RunTranscript transcript = open(root());

        transcript.delegated("vision").append(DELEGATED_REQUEST, DELEGATED_RESPONSE);

        String written = contents(transcript);
        assertTrue(written.contains("> describe it"), () -> written);
        assertTrue(written.contains("> look"), () -> written);
        assertTrue(written.contains("> it reads as x"), () -> written);
    }

    private static String delegatedRequestShowing(String url) {
        return "{\"model\":\"vision/model\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + url + "\"}}]}]}";
    }

    /**
     * An inline image is the one payload that can destroy this file's reason for existing. An
     * image of a few hundred kilobytes becomes a megabyte of base64 in every request that shows
     * it, and a transcript nobody can scroll through is worth about as much as no transcript.
     */
    @Test
    void elidesAnEncodedImagePayloadRatherThanWritingItWhole() throws IOException {
        String payload = Base64.getEncoder().encodeToString(new byte[300]);
        RunTranscript transcript = open(root());

        transcript.delegated("vision")
                .append(delegatedRequestShowing("data:image/png;base64," + payload), DELEGATED_RESPONSE);

        String written = contents(transcript);
        assertTrue(written.contains("data:image/png;base64, <300 bytes elided>"), () -> written);
        assertFalse(written.contains(payload), "the payload must not reach the file");
    }

    /**
     * Base64 of {@code size} bytes of 0xFF, escaped the way the hub writes it: every {@code /} as
     * {@code \/}. All-0xFF encodes to slashes, so the escape is on every character, not by chance.
     */
    private static String hubEscapedPayload(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 0xFF);
        return Base64.getEncoder().encodeToString(bytes).replace("/", "\\/");
    }

    /**
     * The hub hands binaries back as a bare Base64 field, not a data URI. Written whole, one
     * listen of a few hundred kilobytes made the probe's file 1.9 MB.
     */
    @Test
    void elidesABareBase64FieldInAHubReply() throws IOException {
        String payload = hubEscapedPayload(3000);
        RunTranscript transcript = open(root());

        transcript.hubCall("listen", "/verify", 200, headers(Map.of()), "{}",
                "{\"meta\":\"audio\\/mpeg\",\"attachment\":\"" + payload + "\"}");

        String written = contents(transcript);
        assertTrue(written.contains("<3000 bytes elided>"), () -> written);
        assertFalse(written.contains(payload.substring(0, 200)), "the payload must not reach the file");
    }

    /** The audio a speech call sends is bare Base64 too, and it goes through the delegated path. */
    @Test
    void elidesTheAudioInADelegatedRequest() throws IOException {
        String payload = Base64.getEncoder().encodeToString(new byte[2000]);
        RunTranscript transcript = open(root());

        transcript.delegated("speech").append(
                "{\"model\":\"stt/m\",\"input_audio\":{\"data\":\"" + payload + "\",\"format\":\"mp3\"}}",
                "{\"text\":\"hi\"}");

        String written = contents(transcript);
        assertTrue(written.contains("<2000 bytes elided>"), () -> written);
        assertFalse(written.contains(payload), "the payload must not reach the file");
    }

    /** Long text is what the hub's transcriptions are; spaces keep it from looking like a payload. */
    @Test
    void leavesALongTextValueInAHubReplyUntouched() throws IOException {
        String text = "zażółć gęślą jaźń ".repeat(120).strip();
        RunTranscript transcript = open(root());

        transcript.hubCall("listen", "/verify", 200, headers(Map.of()), "{}",
                "{\"transcription\":\"" + text + "\"}");

        assertTrue(contents(transcript).contains(text));
    }

    /** An id or a hash is Base64-shaped too, and short; it is the evidence, not the bulk. */
    @Test
    void leavesAShortBase64LookingValueUntouched() throws IOException {
        String id = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MTI";
        RunTranscript transcript = open(root());

        transcript.hubCall("listen", "/verify", 200, headers(Map.of()), "{}", "{\"id\":\"" + id + "\"}");

        assertTrue(contents(transcript).contains(id));
    }

    /**
     * A message whose content is an array of parts used to render as a role heading with nothing
     * under it — {@code content.asString("")} is empty for an array. The next thing written was
     * the model's answer, so it sat directly beneath the {@code user} heading and read as though
     * it were the prompt. Whoever was debugging the run could not tell what had been asked.
     */
    @Test
    void rendersTheContentPartsOfAMessageRatherThanLeavingItBlank() throws IOException {
        RunTranscript transcript = open(root());

        transcript.delegated("vision").append(
                "{\"model\":\"vision/model\",\"messages\":[{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"look at this\"},"
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://e/x.png\"}}]}]}",
                DELEGATED_RESPONSE);

        String written = contents(transcript);
        int user = written.indexOf("**user**");
        int text = written.indexOf("> look at this");
        int image = written.indexOf("https://e/x.png");
        int answer = written.indexOf("> it reads as x");

        assertTrue(text > user, () -> written);
        assertTrue(image > text, "the image belongs with the prompt, not after the answer");
        assertTrue(answer > image, "and the answer still comes last");
    }

    @Test
    void namesAnInlineImageBySizeWhereTheConversationIsRendered() throws IOException {
        String payload = Base64.getEncoder().encodeToString(new byte[300]);
        RunTranscript transcript = open(root());

        transcript.delegated("vision")
                .append(delegatedRequestShowing("data:image/png;base64," + payload), DELEGATED_RESPONSE);

        String written = contents(transcript);
        // before the fold, so this is the rendered conversation rather than the raw block —
        // which already elides, and would otherwise satisfy a bare contains() on its own
        int marker = written.indexOf("<300 bytes elided>");
        int raw = written.indexOf("<details>");

        assertTrue(marker >= 0 && marker < raw, () -> written);
        assertFalse(written.contains(payload), "not in the rendering either, only in the raw block");
    }

    @Test
    void leavesAnOrdinaryImageUrlInTheRecordUntouched() throws IOException {
        // a URL is short, and it is the only handle anyone reading the file has on the image
        RunTranscript transcript = open(root());

        transcript.delegated("vision")
                .append(delegatedRequestShowing("https://e/x.png"), DELEGATED_RESPONSE);

        assertTrue(contents(transcript).contains("https://e/x.png"), "expected the url kept");
    }

    @Test
    void keepsBothRawHalvesVerbatim() throws IOException {
        RunTranscript transcript = open(root());
        String sent = request("{\"role\":\"user\",\"content\":\"go\"}");

        transcript.append(sent, TEXT_RESPONSE);

        String written = contents(transcript);
        assertTrue(written.contains(sent), "the exact request must survive");
        assertTrue(written.contains(TEXT_RESPONSE), "the exact response must survive");
        assertTrue(written.contains("<details>"), () -> written);
    }

    @Test
    void keepsAPayloadItCannotParseRatherThanThrowing() throws IOException {
        // a provider that answers with an HTML error page, or half a body down a dropped
        // connection, is exactly when the record matters most — and it is the moment the parser
        // gives up. The skim line above is lost; the raw block below it must not be.
        RunTranscript transcript = open(root());

        transcript.append("<html>502 Bad Gateway</html>", "<html>502 Bad Gateway</html>");

        String written = contents(transcript);
        assertTrue(written.contains("<html>502 Bad Gateway</html>"), () -> written);
        assertTrue(written.contains("## turn 1"), () -> written);
    }

    @Test
    void recordsTheHubCallAToolHandlerMade() throws IOException {
        RunTranscript transcript = RunTranscript.open(root(), "x01", SETTINGS, List.of("hub-k3y"));

        transcript.hubCall("lookup", "/api/thing", 200, headers(Map.of("X-Limit-Reset", "30")),
                "{\"apikey\":\"hub-k3y\",\"q\":\"x\"}", "[{\"found\":true}]");

        String written = contents(transcript);
        assertTrue(written.contains("lookup → /api/thing"), () -> written);
        assertTrue(written.contains("200"), () -> written);
        assertTrue(written.contains("[{\"found\":true}]"), () -> written);
        // which header carries a rate limit is not known ahead of time, so the file keeps them all
        assertTrue(written.contains("X-Limit-Reset: 30"), () -> written);
        assertFalse(written.contains("hub-k3y"), "the key must never reach disk");
    }

    @Test
    void writesTheOutcomeTheRunReported() throws IOException {
        RunTranscript transcript = open(root());

        transcript.outcome("submitted, hub said fine");
        transcript.close();

        String written = contents(transcript);
        assertTrue(written.contains("## outcome"), () -> written);
        assertTrue(written.contains("submitted, hub said fine"), () -> written);
        assertFalse(written.contains("without a recorded outcome"), "the run reported for itself");
    }

    @Test
    void marksARunThatEndedWithoutReportingAnOutcome() throws IOException {
        // the iteration cap, a 429, a tool blowing up, Ctrl-C: none of them reach outcome(...)
        RunTranscript transcript = open(root());

        transcript.close();

        assertTrue(contents(transcript).contains("without a recorded outcome"), "expected the sentinel");
    }

    @Test
    void closesOnlyOnce() throws IOException {
        RunTranscript transcript = open(root());

        transcript.close();
        transcript.close();

        String written = contents(transcript);
        assertEquals(1, written.split("## outcome", -1).length - 1, () -> written);
    }

    @Test
    void stripsTransportPaddingFromAroundAnExchange() throws IOException {
        // OpenRouter holds a non-streaming connection open with whitespace while the model thinks,
        // which arrives ahead of the payload and is worth nothing to anyone reading the file
        RunTranscript transcript = open(root());

        transcript.append(
                request("{\"role\":\"user\",\"content\":\"go\"}"),
                "\n   \n   \n" + TEXT_RESPONSE + "\n  ");

        assertTrue(
                contents(transcript).contains("```json\n" + TEXT_RESPONSE + "\n```"),
                "expected the payload flush against its fence");
    }

    @Test
    void stripsTransportPaddingFromAroundAHubCall() throws IOException {
        RunTranscript transcript = open(root());

        transcript.hubCall("lookup", "/api/thing", 200, headers(Map.of()),
                "  {\"q\":\"x\"}\n", "\n\n[{\"found\":true}]\n ");

        String written = contents(transcript);
        assertTrue(written.contains("```json\n{\"q\":\"x\"}\n```"), () -> written);
        assertTrue(written.contains("```json\n[{\"found\":true}]\n```"), () -> written);
    }

    @Test
    void showsWhatTheAssistantActuallyWrote() throws IOException {
        // the model's own words are the point of the file; a turn that answers in prose must not
        // read as "(no tool calls)" with the text left inside the raw JSON
        RunTranscript transcript = open(root());

        transcript.append(
                request("{\"role\":\"user\",\"content\":\"go\"}"),
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"first line\\nsecond line\"}}]}");

        String written = contents(transcript);
        assertTrue(written.contains("> first line"), () -> written);
        assertTrue(written.contains("> second line"), () -> written);
    }

    @Test
    void unpacksTheModelsReasoningOutOfTheResponseJson() throws IOException {
        // this model does most of its work here rather than in content, and a JSON string with
        // escaped newlines is unreadable exactly when it is the only record of what it thought
        RunTranscript transcript = open(root());

        transcript.append(
                request("{\"role\":\"user\",\"content\":\"go\"}"),
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":null,\"reasoning\":\"first thought\\nsecond thought\"}}]}");

        String written = contents(transcript);
        assertTrue(written.contains("<summary>reasoning"), () -> written);
        assertTrue(written.contains("> first thought"), () -> written);
        assertTrue(written.contains("> second thought"), () -> written);
    }

    @Test
    void saysNothingAboutReasoningWhenTheProviderSendsNone() throws IOException {
        RunTranscript transcript = open(root());

        transcript.append(request("{\"role\":\"user\",\"content\":\"go\"}"), TEXT_RESPONSE);

        assertFalse(contents(transcript).contains("<summary>reasoning"), "no empty fold");
    }

    /**
     * What the run cost, per model, at the bottom of the file it already writes. Before this the
     * only account of a run's price was a dashboard consulted afterwards and aggregated across
     * every run, which cannot answer "what did *this* one spend".
     */
    @Test
    void totalsEachModelsTokensAndCostWhenItCloses() throws IOException {
        RunTranscript transcript = open(root());

        transcript.usage("a/one", new Usage(100, 20, 120, 0.001));
        transcript.usage("a/one", new Usage(100, 20, 120, 0.001));
        transcript.usage("b/two", new Usage(7, 3, 10, 0.0002));
        transcript.close();

        String written = contents(transcript);
        assertTrue(written.contains("## usage"), () -> written);
        assertTrue(written.contains("| a/one | 200 | 40 | 240 | 0.002000 |"), () -> written);
        assertTrue(written.contains("| b/two | 7 | 3 | 10 | 0.000200 |"), () -> written);
        assertTrue(written.contains("| **total** | 207 | 43 | 250 | 0.002200 |"), () -> written);
    }

    /**
     * The trap this exists for: {@code close} skips its own note once an outcome has been
     * recorded, and every run that finishes records one. A usage table written behind that guard
     * appears only on runs that died — the cost report missing from exactly the runs whose cost
     * was worth having.
     */
    @Test
    void writesTheUsageTableEvenWhenTheRunReportedAnOutcome() throws IOException {
        RunTranscript transcript = open(root());
        transcript.usage("a/one", new Usage(100, 20, 120, 0.001));

        transcript.outcome("done");
        transcript.close();

        String written = contents(transcript);
        assertTrue(written.contains("## usage"), () -> written);
        assertTrue(written.contains("| a/one | 100 | 20 | 120 | 0.001000 |"), () -> written);
    }

    /**
     * A delegated exchange is paid for like any other. The seam's usage method has to be a
     * default so {@code Transcript.NONE} stays a lambda — which means a delegated transcript
     * returned *as* a lambda silently inherits the no-op, and the table goes on calling itself
     * per-model while reporting only the main loop.
     */
    @Test
    void countsADelegatedCallsSpendInTheSameTotals() throws IOException {
        RunTranscript transcript = open(root());

        transcript.usage("a/one", new Usage(100, 20, 120, 0.001));
        transcript.delegated("vision").usage("c/three", new Usage(5, 1, 6, 0.00005));
        transcript.close();

        String written = contents(transcript);
        assertTrue(written.contains("| c/three | 5 | 1 | 6 | 0.000050 |"), () -> written);
        assertTrue(written.contains("| **total** | 105 | 21 | 126 | 0.001050 |"), () -> written);
    }

    /** No section at all rather than an empty table, so a run that called nothing says nothing. */
    @Test
    void writesNoUsageSectionWhenNothingReportedAny() throws IOException {
        RunTranscript transcript = open(root());

        transcript.outcome("done");
        transcript.close();

        String written = contents(transcript);
        assertFalse(written.contains("## usage"), () -> written);
    }

    /**
     * What the run decided, as against what it sent. A pipeline reaches the hub having already
     * made every choice that matters — what the rules caught, whether the gate passed, which unit
     * it trusted — and none of that is visible in a request body.
     */
    @Test
    void recordsANoteUnderTheTurnItHappenedIn() throws IOException {
        RunTranscript transcript = open(root());
        transcript.append(request("{\"role\":\"user\",\"content\":\"go\"}"), TEXT_RESPONSE);

        transcript.note("gate", "30 labels, all agreeing");

        String written = contents(transcript);
        assertTrue(written.contains("## turn 1 · gate"), () -> written);
        assertTrue(written.contains("30 labels, all agreeing"), () -> written);
    }

    /** Before the first exchange there is no turn yet, and a note then is still worth keeping. */
    @Test
    void recordsANoteMadeBeforeAnyModelCall() throws IOException {
        RunTranscript transcript = open(root());

        transcript.note("rule pass", "46 records");

        String written = contents(transcript);
        assertTrue(written.contains("## turn 0 · rule pass"), () -> written);
    }

    /**
     * The reason this is a seam rather than a line in the outcome. A run killed between the gate
     * and the submission is exactly the case the event-by-event rule exists for, and an outcome is
     * written after the hub has answered — too late to say what the run had decided before it
     * spent.
     */
    @Test
    void writesANoteAsItHappensRatherThanWhenTheRunEnds() throws IOException {
        RunTranscript transcript = open(root());

        transcript.note("composition", "agreement 1.0, judging by CLAUSE");

        // no close(), no outcome() — the file already has it
        String written = contents(transcript);
        assertTrue(written.contains("agreement 1.0"), () -> written);
    }

    private static HttpHeaders headers(Map<String, String> values) {
        return TestHeaders.of(values);
    }
}
