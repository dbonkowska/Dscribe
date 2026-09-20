package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s03e01/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository, and this lesson supplies more
 * of it than any before. The channel definitions are not parameters to a rule — they <em>are</em>
 * the rule, so the deterministic pass is entirely a reading of this file. The field names are how
 * a record is read at all. And the stance vocabulary is a closed set that becomes a schema enum,
 * which is what keeps the model from inventing a verdict nothing downstream can interpret.
 *
 * <p>Lists bind by index: {@code channels.1.name}, {@code stance.vocabulary.1}, and so on.
 *
 * <p>Every check refuses at the binding boundary, before the archive is fetched and before a
 * single call is paid for. They matter more here than in any earlier lesson because of what the
 * oracle is: one submission, all or nothing, and a rejection that names nothing. A run configured
 * wrongly does not fail — it succeeds at computing the wrong set, and there is no reply that
 * would tell anyone so.
 *
 * @param verifyTask the task name the hub expects
 * @param flagPattern a regex matching the result, used both to spot it and to validate it
 * @param answerKey the key the answer object carries its list under
 * @param archive where the corpus comes from and how a file name yields an id
 * @param fields how one record is read — the exercise's key names, not ours
 * @param channels the whole of the deterministic pass
 * @param stance the closed vocabulary a judgement answers in, and which member means trouble
 * @param judge how much is spent, and when the cheaper deduplication unit is trusted
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String answerKey,
        Archive archive,
        Fields fields,
        List<Channel> channels,
        Stance stance,
        Judge judge) {

    public TaskParams {
        require(verifyTask, "verifyTask");
        requirePattern(flagPattern, "flagPattern");
        require(answerKey, "answerKey");
        requirePresent(archive, "archive", "archive.url, archive.file and archive.idPattern");
        requirePresent(fields, "fields",
                "fields.type, fields.notes, fields.typeSeparator and fields.clauseSeparator");
        requirePresent(stance, "stance", "stance.vocabulary.1, ... and stance.problem");
        requirePresent(judge, "judge",
                "judge.batchSize, judge.sampleSize and judge.agreementThreshold");

        if (channels == null || channels.isEmpty()) {
            throw new IllegalStateException(
                    "channels must define at least one channel: with none, nothing is ever"
                            + " range-checked, every record reads as clean, and the run submits"
                            + " whatever the notes alone produced — an answer that looks right and"
                            + " is missing a whole category. Set channels.1.name,"
                            + " channels.1.field, channels.1.min, channels.1.max, ...");
        }

        // two channels under one name: the second shadows the first wherever channels are looked
        // up by name, so one configured channel is never checked and nothing says which
        Set<String> names = new HashSet<>();
        for (Channel channel : channels) {
            if (!names.add(channel.name())) {
                throw new IllegalStateException(
                        "channels names " + channel.name() + " twice. Each channel name must appear"
                                + " once, because a record's type field selects channels by it.");
            }
        }
        channels = List.copyOf(channels);

        if (!stance.vocabulary().contains(stance.problem())) {
            throw new IllegalStateException(
                    "stance.problem is " + stance.problem() + ", which is not in stance.vocabulary "
                            + stance.vocabulary() + ". No answer could ever carry it, so the"
                            + " judgement pass would flag nothing whatever the model said.");
        }
    }

    /**
     * @param idPattern a regex whose first group is a record's id, taken from its file name
     */
    public record Archive(String url, String file, String idPattern) {
        public Archive {
            require(url, "archive.url");
            require(file, "archive.file");
            requireCapturingPattern(idPattern, "archive.idPattern");
        }
    }

    /**
     * The exercise's own key names. A record is read through these rather than through a Java
     * record carrying the corpus's field names, which would put task content in the repository.
     */
    public record Fields(String type, String notes, String typeSeparator, String clauseSeparator) {
        public Fields {
            require(type, "fields.type");
            require(notes, "fields.notes");
            require(typeSeparator, "fields.typeSeparator");
            require(clauseSeparator, "fields.clauseSeparator");
        }
    }

    /** One measurement channel: what it is called, where its value sits, and its accepted range. */
    public record Channel(String name, String field, double min, double max) {
        public Channel {
            require(name, "channels.N.name");
            require(field, "channels.N.field");

            // strictly above, which catches the transposed pair and the both-zero pair at once:
            // a bound nobody wrote binds to 0.0, and a [0, 0] range rejects every reading it sees
            if (!(max > min)) {
                throw new IllegalStateException(
                        "channels: " + name + " has max " + max + ", which is not above its min "
                                + min + ". A range that admits nothing flags every record carrying"
                                + " this channel; a range nobody wrote binds to [0.0, 0.0] and does"
                                + " the same.");
            }
        }
    }

    /**
     * The closed vocabulary a judgement answers in. A {@code List<String>} plus a schema enum
     * rather than a Java enum: the constraint survives, the constant does not.
     */
    public record Stance(List<String> vocabulary, String problem) {
        public Stance {
            if (vocabulary == null || vocabulary.isEmpty()) {
                throw new IllegalStateException(
                        "stance.vocabulary must list at least one stance: it narrows the answer"
                                + " schema, and an empty one leaves the model free to invent a"
                                + " verdict nothing downstream can read. Set"
                                + " stance.vocabulary.1, ...");
            }
            require(problem, "stance.problem");
            vocabulary = List.copyOf(vocabulary);
        }
    }

    /**
     * @param batchSize how many inputs go in one call
     * @param sampleSize how many coarse units are judged twice to test the composition rule
     * @param agreementThreshold the share of that sample that must agree before the finer unit is
     *                           trusted for the rest
     */
    public record Judge(int batchSize, int sampleSize, double agreementThreshold) {
        public Judge {
            if (batchSize < 1) {
                throw new IllegalStateException(
                        "judge.batchSize is " + batchSize + "; it must be at least 1. A number"
                                + " nobody wrote binds to zero, and a zero-sized batch sends"
                                + " nothing, comes back empty, and does it again forever.");
            }
            if (sampleSize < 1) {
                throw new IllegalStateException(
                        "judge.sampleSize is " + sampleSize + "; it must be at least 1. With no"
                                + " sample the composition check compares nothing, agrees"
                                + " vacuously, and always picks the cheaper unit — which is the"
                                + " one decision it exists to make carefully.");
            }
            if (agreementThreshold <= 0 || agreementThreshold > 1) {
                throw new IllegalStateException(
                        "judge.agreementThreshold is " + agreementThreshold + "; it must be above 0"
                                + " and at most 1. Zero accepts any disagreement at all; above one"
                                + " can never be met, so the finer unit is never trusted and the"
                                + " sample is paid for every run to no purpose.");
            }
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    key + " is missing or blank. A string key nobody wrote binds to null rather"
                            + " than failing, and this one would fail later and further from"
                            + " here. Set it in the lesson's task.properties.");
        }
    }

    private static void requirePresent(Object value, String key, String keys) {
        if (value == null) {
            throw new IllegalStateException(
                    key + " is missing entirely. Set " + keys + " in the lesson's task.properties.");
        }
    }

    /** Compiled here rather than where it is used, so a typo fails before anything is spent. */
    private static void requirePattern(String pattern, String key) {
        require(pattern, key);
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    key + " is not a valid regex: " + e.getDescription()
                            + ". Remember every backslash is doubled in a properties file.", e);
        }
    }

    /**
     * A pattern that is also read for its first group. Without one it compiles and matches
     * happily, and then throws on the first file of ten thousand — after the archive has been
     * fetched and unpacked.
     */
    private static void requireCapturingPattern(String pattern, String key) {
        requirePattern(pattern, key);
        if (Pattern.compile(pattern).matcher("").groupCount() < 1) {
            throw new IllegalStateException(
                    key + " has no capturing group, and its first group is what names a record."
                            + " Put the id in parentheses, e.g. ^([0-9]+)[.]json$.");
        }
    }
}
