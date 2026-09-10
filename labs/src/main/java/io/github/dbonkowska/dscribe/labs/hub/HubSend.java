package io.github.dbonkowska.dscribe.labs.hub;

/**
 * One attempt at the hub — the seam {@link ResilientHub} retries behind.
 *
 * <p>Narrow on purpose, so a test can drive the loop from a queue of responses instead of a
 * server. {@link HubClient#send} satisfies it as a method reference; nothing else implements it
 * in production.
 */
@FunctionalInterface
public interface HubSend {

    HubResponse send(String label, String taskName, Object answer);
}
