package io.github.dbonkowska.dscribe.labs.s03e05;

/**
 * One call to a tool on the hub, with the key merged in by whoever implements it.
 *
 * <p>The seam the tools are tested behind. {@code HubClient#post} satisfies it as a method
 * reference; nothing else implements it in production.
 */
@FunctionalInterface
interface Post {

    String post(String label, String path, Object body);
}
