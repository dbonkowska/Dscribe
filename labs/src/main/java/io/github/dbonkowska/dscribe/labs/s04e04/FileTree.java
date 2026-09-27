package io.github.dbonkowska.dscribe.labs.s04e04;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The batch the hub builds the tree from, and the check it has to pass before it is sent.
 *
 * <p>The protocol names below — {@code action}, {@code path}, {@code content} — are the hub's
 * generic filesystem vocabulary, the same for any tree; the action names themselves, and every
 * directory name, come from the lesson's {@code task.properties}.
 */
final class FileTree {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A markdown link's target, which the hub requires to be a file that already exists. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)]+)\\)");

    private FileTree() {}

    /**
     * In the order the hub needs: a reset so a re-run starts clean, the directories, then the
     * places before anything that links to one — the hub refuses a link to a file not yet created.
     */
    static List<Map<String, Object>> build(
            Extraction extraction, TradeLog tradeLog, TaskParams.Dirs dirs, TaskParams.Actions actions) {

        List<Map<String, Object>> batch = new ArrayList<>();
        batch.add(Map.of("action", actions.reset()));
        for (String dir : List.of(dirs.cities(), dirs.people(), dirs.goods())) {
            batch.add(Map.of("action", actions.createDirectory(), "path", "/" + dir));
        }

        for (Extraction.CityNeeds city : extraction.cities()) {
            Map<String, Integer> needs = new LinkedHashMap<>();
            city.needs().forEach(need -> needs.put(Fold.name(need.good()), need.quantity()));
            batch.add(file(actions, path(dirs.cities(), city.city()), MAPPER.writeValueAsString(needs)));
        }

        for (Extraction.Person person : extraction.people()) {
            String content = Fold.text(person.fullName().strip()) + "\n\n" + link(dirs, person.city());
            batch.add(file(actions, path(dirs.people(), person.fullName()), content));
        }

        // Two trade log spellings of one good fold to one singular, and so to one file listing
        // every place that sells it.
        Map<String, String> singular = new HashMap<>();
        extraction.goods().forEach(form -> singular.put(form.asWritten(), Fold.name(form.singular())));
        Map<String, Set<String>> sellers = new LinkedHashMap<>();
        for (TradeLog.Sale sale : tradeLog.sales()) {
            String good = singular.get(sale.good());
            if (good != null) {
                sellers.computeIfAbsent(good, key -> new LinkedHashSet<>()).add(sale.seller());
            }
        }
        sellers.forEach((good, cities) -> {
            List<String> links = cities.stream().map(city -> link(dirs, city)).toList();
            batch.add(file(actions, "/" + dirs.goods() + "/" + good, String.join("\n", links)));
        });

        return batch;
    }

    /**
     * Every rule the hub stated, checked against the whole batch at once so a refusal lists every
     * problem rather than the first. Empty when the batch can be sent.
     */
    static List<String> check(List<Map<String, Object>> batch, Limits limits, TaskParams.Actions actions) {
        List<String> problems = new ArrayList<>();
        Set<String> created = new HashSet<>();
        Map<String, String> names = new HashMap<>();

        for (Map<String, Object> action : batch) {
            Object pathValue = action.get("path");
            if (pathValue == null) {
                continue;
            }
            String path = (String) pathValue;
            boolean isFile = actions.createFile().equals(action.get("action"));
            String[] segments = path.substring(1).split("/");

            if (segments.length > limits.maxDepth()) {
                problems.add(path + " is " + segments.length + " levels deep; the limit is " + limits.maxDepth());
            }
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                boolean last = i == segments.length - 1;
                int max = last && isFile ? limits.maxFileName() : limits.maxDirectoryName();
                if (!limits.namePattern().matcher(segment).matches()) {
                    problems.add(path + ": \"" + segment + "\" does not match " + limits.namePattern());
                }
                if (segment.length() > max) {
                    problems.add(path + ": \"" + segment + "\" is " + segment.length() + " characters; the limit is " + max);
                }
            }

            String name = segments[segments.length - 1];
            String earlier = names.putIfAbsent(name, path);
            if (limits.uniqueNames() && earlier != null) {
                problems.add(path + " reuses the name of " + earlier);
            }

            if (isFile) {
                String content = String.valueOf(action.get("content"));
                if (!content.chars().allMatch(c -> c < 128)) {
                    problems.add(path + " holds characters outside ASCII: " + content);
                }
                Matcher link = LINK.matcher(content);
                while (link.find()) {
                    if (!created.contains(link.group(1))) {
                        problems.add(path + " links to " + link.group(1) + ", which is not created before it");
                    }
                }
            }
            created.add(path);
        }
        return problems;
    }

    private static Map<String, Object> file(TaskParams.Actions actions, String path, String content) {
        return Map.of("action", actions.createFile(), "path", path, "content", content);
    }

    private static String path(String dir, String name) {
        return "/" + dir + "/" + Fold.name(name);
    }

    /** The model names the place; the address is built here, the same way its file's path is. */
    private static String link(TaskParams.Dirs dirs, String city) {
        return "[" + Fold.text(city) + "](" + path(dirs.cities(), city) + ")";
    }
}
