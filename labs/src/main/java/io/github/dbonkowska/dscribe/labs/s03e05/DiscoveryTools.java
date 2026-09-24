package io.github.dbonkowska.dscribe.labs.s03e05;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three tools a run starts with: find tools, call one that was found, and submit an answer.
 *
 * <p>Found tools are not registered as tools of their own. Every one of them takes the same single
 * argument, so a separate schema per tool would tell the model nothing a name and a description do
 * not, and would need a toolbox that changes mid-run. They are reached through {@link #call()}
 * instead, by name, and the name is resolved only against what a search returned — so the model
 * never writes an address, and the hub key goes nowhere a search did not point.
 *
 * <p>Replies go back to the model as the hub wrote them. The one addition is a search's refusals,
 * appended below its reply: without them the model would see a tool it cannot call and have no way
 * to learn why.
 */
final class DiscoveryTools {

    /** The whole schema of a search: what to look for, in the hub's own terms. */
    record Search(String query) {}

    /** A found tool, by the name the search gave it, and what to ask it. */
    record Call(String name, String query) {}

    /** The answer exactly as verify expects it. */
    record Submit(List<String> answer) {}

    private final Post post;
    private final ResilientHub verify;
    private final TaskParams task;
    private final ToolRegistry registry = new ToolRegistry();

    DiscoveryTools(Post post, ResilientHub verify, TaskParams task) {
        this.post = post;
        this.verify = verify;
        this.task = task;
    }

    Tool<Search> search() {
        String name = task.search().name();
        return new Tool<>(name, task.search().description(), Search.class, args -> {
            String reply = post.post(name, task.searchPath(), Map.of(ToolRegistry.PARAMETER, args.query()));
            List<String> refusals = registry.register(reply);
            return ToolOutput.of(refusals.isEmpty() ? reply : reply + "\n\n" + String.join("\n", refusals));
        });
    }

    Tool<Call> call() {
        return new Tool<>(task.call().name(), task.call().description(), Call.class, args -> {
            Optional<String> path = registry.pathOf(args.name());
            if (path.isEmpty()) {
                Optional<String> refused = registry.refusalOf(args.name());
                if (refused.isPresent()) {
                    return ToolOutput.of(refused.get());
                }
                return ToolOutput.of("No tool named " + args.name() + " has been found. Known tools: "
                        + (registry.known().isEmpty() ? "none yet" : String.join(", ", registry.known()))
                        + ". Find it with " + task.search().name() + " first.");
            }
            return ToolOutput.of(post.post(args.name(), path.get(), Map.of(ToolRegistry.PARAMETER, args.query())));
        });
    }

    Tool<Submit> submit() {
        String name = task.submit().name();
        return new Tool<>(name, task.submit().description(), Submit.class,
                args -> ToolOutput.of(verify.call(name, args.answer())));
    }
}
