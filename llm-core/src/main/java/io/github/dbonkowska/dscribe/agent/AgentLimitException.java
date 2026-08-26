package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;

/**
 * The one way a run ends badly: the model never called the answer tool and the iteration cap
 * ran out. Carries the full transcript, because the only useful question afterwards is what the
 * model was actually doing with those iterations.
 */
public final class AgentLimitException extends RuntimeException {

    private final int iterations;
    private final List<Message> transcript;

    AgentLimitException(int iterations, List<Message> transcript) {
        super("Gave up after " + iterations + " iterations without a call to the answer tool");
        this.iterations = iterations;
        this.transcript = List.copyOf(transcript);
    }

    public int iterations() {
        return iterations;
    }

    public List<Message> transcript() {
        return transcript;
    }
}