package io.github.dbonkowska.dscribe.llm;

import io.github.dbonkowska.dscribe.config.LlmConfig;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which model a call goes to, decided in one place. It is the quietest thing here that can be
 * wrong: a run against a model nobody chose still answers, still costs, and reads afterwards as
 * a model being bad at the task rather than as the wrong model having been asked.
 *
 * <p>No exchange is ever made — every assertion is about what the client would send.
 *
 * <p>Fixtures are invented; the names are not models that exist.
 */
class LlmClientTest {

    private record Config(String apiKey, String model, String baseUrl) implements LlmConfig {}

    private static final List<Message> ANYTHING = List.of(new Message(Role.user, "go"));

    private static LlmClient clientConfiguredWith(String model) {
        return new LlmClient(new Config("k", model, "https://llm.test"));
    }

    @Test
    void takesTheCallersModelWhenTheConfigurationNamesNone() {
        assertEquals("caller/pin", clientConfiguredWith(null).defaultModel("caller/pin").model());
    }

    @Test
    void letsTheConfigurationOverrideTheCallersModel() {
        // a command-line flag, an environment variable and a properties file all arrive as this
        assertEquals(
                "config/override",
                clientConfiguredWith("config/override").defaultModel("caller/pin").model());
    }

    @Test
    void treatsABlankConfiguredModelAsNamingNothing() {
        // a key left empty in a properties file is the ordinary way this happens, and silently
        // sending "" as the model is the one outcome nobody wants
        assertEquals("caller/pin", clientConfiguredWith("   ").defaultModel("caller/pin").model());
    }

    @Test
    void keepsTheModelWhenATranscriptIsAttachedAfterwards() {
        // runners pin first and record second; losing the model at the second step would send a
        // whole run somewhere else without changing a line that mentions models
        LlmClient recording = clientConfiguredWith(null)
                .defaultModel("caller/pin")
                .withTranscript((request, response) -> {});

        assertEquals("caller/pin", recording.model());
    }

    @Test
    void refusesToSendWhenNothingHasNamedAModel() {
        LlmClient client = clientConfiguredWith(null);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> client.send(ANYTHING, List.of(), "auto"));

        assertTrue(thrown.getMessage().contains("model"), thrown::getMessage);
    }

    @Test
    void refusesToSendStructuredWhenNothingHasNamedAModel() {
        LlmClient client = clientConfiguredWith(null);

        assertThrows(
                IllegalStateException.class,
                () -> client.sendStructured(ANYTHING, null, Config.class));
    }
}
