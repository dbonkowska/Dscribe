package io.github.dbonkowska.dscribe.labs.s02e05;

import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s02e05/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository, and this lesson adds three
 * things no earlier one had. The command shapes say which overload a written argument selects — a
 * command language where the argument's format chooses the method cannot be described by a name
 * alone. The grid's size is what a reading is checked against, and it is knowledge handed to the
 * run rather than derived by it. And the reserved template renders the one command the runner owns
 * rather than the model.
 *
 * <p>Lists bind by index: {@code dsl.shapes.1.id}, {@code dsl.shapes.1.command},
 * {@code dsl.plainCommands.1}, and so on.
 *
 * <p>Each check refuses at the binding boundary, before the transcript is open and before a
 * delegated call has been paid for. What they prevent is not a crash where the mistake is, but a
 * run that spends and then fails in a way that reads as the model's fault.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex matching the result, used both to spot it and to end the run
 * @param docUrl      where the command language documents itself, fetched once and seeded
 * @param image       the artefact a reading is taken from
 * @param grid        what a reading is checked against
 * @param dsl         the command language, from the side the validator reads it
 * @param submit      the tool that sends a sequence
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String docUrl,
        Image image,
        Grid grid,
        Dsl dsl,
        ToolPrompt submit) {

    public TaskParams {
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        require(docUrl, "docUrl");
        requirePresent(image, "image", "image.file and image.mediaType");
        requirePresent(grid, "grid", "grid.columns, grid.rows and grid.readAttempts");
        requirePresent(dsl, "dsl", "dsl.shapes.1.id, dsl.terminal, dsl.reservedShape and the rest");
        requirePresent(submit, "submit", "submit.name and submit.description");
    }

    /**
     * The artefact the reading is taken from.
     *
     * @param file      what to download from the hub's data path
     * @param mediaType what those bytes are, for the provider — the exercise's format, so it is
     *                  supplied rather than guessed from the name
     */
    public record Image(String file, String mediaType) {
        public Image {
            require(file, "image.file");
            require(mediaType, "image.mediaType");
        }
    }

    /**
     * What a reading is checked against, neither of which is disclosed to the model.
     *
     * <p>There is no landmark here to calibrate against. The one visually distinct feature of this
     * lesson's image is the target itself — its colour was boosted on purpose to make it findable —
     * so a second known position does not exist to be checked. What stands in for it is agreement:
     * each attempt reads twice, and the two must place the target in the same sector.
     *
     * @param readAttempts how many attempts may disagree before the run gives up; each is two
     *                     readings
     */
    public record Grid(int columns, int rows, int readAttempts) {
        public Grid {
            // a dimension nobody wrote binds to zero, and a grid with no columns is one no reading
            // can ever agree with: every attempt is rejected and the run dies having paid for all
            requirePositive(columns, "grid.columns");
            requirePositive(rows, "grid.rows");

            if (readAttempts < 1) {
                throw new IllegalStateException(
                        "grid.readAttempts is " + readAttempts + "; it must be at least 1. Zero reads every"
                                + " reading as already exhausted, so the run fails without ever looking.");
            }
        }
    }

    /**
     * One accepted form of one command.
     *
     * <p>The command language overloads a single name across several meanings and picks between
     * them by the argument's format, so a name on its own says nothing about what a call does. A
     * shape is the pair that does, and its id is how the rest of the file refers to it.
     *
     * @param pattern a regex the argument must match in full
     */
    public record Shape(String id, String command, String pattern) {
        /** Compiled here rather than where it is used, so a typo fails before anything is spent. */
        public Shape {
            require(id, "dsl.shapes.N.id");
            if (command == null || command.isBlank()) {
                throw new IllegalStateException(
                        "dsl.shapes: " + id + " has no command. Set its command in the lesson's"
                                + " task.properties.");
            }
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalStateException(
                        "dsl.shapes: " + id + " has no pattern. A blank one matches only the empty"
                                + " argument, so every real argument would be refused.");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException(
                        "dsl.shapes: the pattern for " + id + " is not a valid regex: " + e.getDescription()
                                + ". Remember every backslash is doubled in a properties file.", e);
            }
        }
    }

    /**
     * The command language, from the side the validator reads it.
     *
     * <p>It is described twice — here, and in the documentation the run fetches for the model.
     * That is deliberate: the model learns the API at run time, and the checks cannot. Where the
     * two disagree this one wins, so every refusal quotes the shapes it accepted, and the
     * disagreement reaches the transcript instead of hiding in a stalled run.
     *
     * @param plainCommands   commands that take no argument
     * @param terminal        the command that begins the action; the ordering rule is written
     *                        around it
     * @param prerequisites   shape ids that must appear before {@code terminal}
     * @param reservedShape   the shape whose value the runner owns rather than the model
     * @param reservedTemplate how that command is rendered, with a slot for each half
     */
    public record Dsl(
            List<Shape> shapes,
            List<String> plainCommands,
            String terminal,
            List<String> prerequisites,
            String reservedShape,
            String reservedTemplate) {

        public Dsl {
            if (shapes == null || shapes.isEmpty()) {
                throw new IllegalStateException(
                        "dsl.shapes must list at least one shape: with none, every argument the model"
                                + " writes matches nothing and every sequence is refused. Set"
                                + " dsl.shapes.1.id, dsl.shapes.1.command, dsl.shapes.1.pattern, ...");
            }
            // two shapes under one id: a prerequisite or the reserved shape naming it means one of
            // them and nothing says which, so the ordering check would silently enforce the first
            Set<String> ids = new HashSet<>();
            for (Shape shape : shapes) {
                if (!ids.add(shape.id())) {
                    throw new IllegalStateException(
                            "dsl.shapes names " + shape.id() + " twice. Each shape id must appear once,"
                                    + " because the rest of the file refers to shapes by it.");
                }
            }

            if (plainCommands == null || plainCommands.isEmpty()) {
                throw new IllegalStateException(
                        "dsl.plainCommands must list at least one command, and one of them is"
                                + " dsl.terminal. Set dsl.plainCommands.1, ...");
            }
            // A name in both lists makes its bare form permanently unreachable: an entry is only
            // read as taking no argument when the name has no shapes at all, so the plain listing
            // would never be consulted and a refusal would offer the name twice.
            for (Shape shape : shapes) {
                if (plainCommands.contains(shape.command())) {
                    throw new IllegalStateException(
                            "dsl.plainCommands lists " + shape.command() + ", which is also the command"
                                    + " of shape " + shape.id() + ". A command either takes an argument"
                                    + " or does not; listed both ways its bare form can never be reached.");
                }
            }
            require(terminal, "dsl.terminal");
            if (!plainCommands.contains(terminal)) {
                throw new IllegalStateException(
                        "dsl.terminal is " + terminal + ", which is not in dsl.plainCommands. The"
                                + " ordering rule is written around it, and a terminal nothing may emit"
                                + " makes that rule unsatisfiable: every sequence would be refused for a"
                                + " precondition no sequence can meet.");
            }

            if (prerequisites == null) {
                throw new IllegalStateException(
                        "dsl.prerequisites is missing. Set dsl.prerequisites.1, ... to the shape ids that"
                                + " must appear before " + terminal + ".");
            }
            for (String id : prerequisites) {
                if (!ids.contains(id)) {
                    throw new IllegalStateException(
                            "dsl.prerequisites names " + id + ", which is not a shape id. Nothing can ever"
                                    + " match it, so the terminal would never be reachable. Known ids: "
                                    + ids + ".");
                }
            }

            require(reservedShape, "dsl.reservedShape");
            if (!ids.contains(reservedShape)) {
                throw new IllegalStateException(
                        "dsl.reservedShape is " + reservedShape + ", which is not a shape id. Nothing"
                                + " would ever be recognised as reserved, and a value the runner owns"
                                + " would pass through the one check written to catch it. Known ids: "
                                + ids + ".");
            }

            require(reservedTemplate, "dsl.reservedTemplate");
            requireTwoSlots(reservedTemplate);

            shapes = List.copyOf(shapes);
            plainCommands = List.copyOf(plainCommands);
            prerequisites = List.copyOf(prerequisites);
        }

        /**
         * Checked by rendering it rather than by looking for {@code %s}: {@code String.formatted}
         * discards an argument it has nowhere to put, so a template that lost a slot renders cleanly
         * and drops half the coordinate without a word — and a search for {@code %s} would be fooled
         * by {@code %%s}, which counts and fills nothing, and by {@code %1$s}, which fills a slot
         * without looking like the others. Two distinct markers go in; both have to come out.
         *
         * <p>It catches a missing slot, not a reversed one. A template that renders both halves in
         * the wrong order still contains both markers, and nothing here would say so.
         */
        private static void requireTwoSlots(String template) {
            String column = "<<column>>";
            String row = "<<row>>";
            String rendered;
            try {
                rendered = template.formatted(column, row);
            } catch (IllegalFormatException e) {
                throw new IllegalStateException(
                        "dsl.reservedTemplate is not a valid format string: " + e.getMessage()
                                + ". Write a literal percent sign as %%.", e);
            }
            if (!rendered.contains(column) || !rendered.contains(row)) {
                throw new IllegalStateException(
                        "dsl.reservedTemplate must contain two %s, one per half of the coordinate;"
                                + " rendering it dropped one. A template with a slot missing renders"
                                + " cleanly and names half a position.");
            }
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {
        public ToolPrompt {
            require(name, "submit.name");
            require(description, "submit.description");
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

    private static void requirePositive(int value, String key) {
        if (value < 1) {
            throw new IllegalStateException(
                    key + " is " + value + "; it must be positive. A number nobody wrote binds to zero"
                            + " rather than failing, and a grid no reading can agree with rejects every"
                            + " attempt.");
        }
    }
}
