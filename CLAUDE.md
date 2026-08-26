# Dscribe

Custom Java AI agent framework, built from scratch. Maven multi-module, Java 26.

- **`llm-core`** — reusable LLM integration over OpenRouter. This is the library; treat it
  as one, and test it properly.
- **`labs`** — AI_Devs 4 course exercises. Each runs to earn a flag; its logic is then left
  alone, though it still moves when shared infrastructure changes under it.

## Never put course task content in this repo

The repo is public. Prompts, task parameters, filter criteria, target names, expected
answers — none of it belongs here, in any form.

Those live in per-lesson bundles **outside** the repo, under `labs.lessons.dir` (set in
`.claude/dbbon-sdd.md`). `Lesson.of(config, "s01e01")` resolves the folder; the runner
reads `system.md` (and `user.md`, where the lesson has one) and binds `task.properties` to a
record. It hardcodes none of them.

When a new lesson needs a prompt or a criterion, it goes in that lesson's bundle — **not**
in a constant, not in a test fixture, not in a comment that restates the task. If you find
yourself typing a city name, a date range, or a person's name into a `.java` file, stop:
it belongs in `task.properties`.

This rule has no exceptions and nothing softens it.

## Running

Maven is not on PATH. Use the wrapper:

```bash
./mvnw test                                                          # whole reactor
./mvnw test -Dtest=AgentTest -Dsurefire.failIfNoSpecifiedTests=false # one class
./mvnw -am -pl labs test                                             # labs only, from source
```

`-am` matters whenever you narrow to `-pl labs` — without it Maven resolves `llm-core` from
`~/.m2` instead of the reactor, so you silently test against a stale jar.

**`-Dsurefire.failIfNoSpecifiedTests=false` is mandatory with `-Dtest=`.** Surefire fails a
module when the *pattern* matches nothing there — not when the module has no tests — and it
checks every module in the reactor. A test class lives in one module, so the other one always
matches zero and aborts the build: `-Dtest=AgentTest` gets through `llm-core` and dies on
`labs`, `-Dtest=ArtifactsTest` dies on `llm-core` before `labs` is reached. Both modules having
tests changed nothing. Note the `surefire.` prefix — bare `-DfailIfNoSpecifiedTests` is
silently ignored.

### Running a lesson

```bash
./mvnw -am -pl labs install -DskipTests
./mvnw -pl labs exec:java -Dexec.mainClass=io.github.dbonkowska.dscribe.labs.s01e02.S01E02
```

Two traps, both of which have cost real model calls:

**`exec:java` cannot be combined with `-am -pl labs`.** The goal then runs on every selected
project, starting with the root aggregator, whose classpath has no lesson classes — the run
dies with `ClassNotFoundException` before `labs` is reached. Install first, then exec on `labs`
alone; the install is what keeps `llm-core` fresh.

**Never pass `-q` to a lesson run.** Quiet mode swallows SLF4J output under `exec:java` while
letting `System.out` through, so the agent's tool-call trace vanishes and the run looks like it
did nothing. The transcript file still records everything, but you lose the live view.

Every run writes a transcript to `labs/data/logs/{lesson}-{timestamp}.md` — the conversation,
the hub calls, the model's reasoning where the provider returns it, and how the run ended.
That file is the first place to look when a run does something surprising; it is gitignored,
and secrets are redacted before anything is written.

## Testing

Test what the next lesson depends on; don't test what the hub already verifies. Shared
infrastructure (`SchemaUtils`, `LabsConfig`, `HubClient`, `Lesson`) fails silently and is
reused, so it gets tests. Per-lesson runners are verified by `hub.verify` rejecting a wrong
answer, so they don't. Full reasoning in `Atelier/Dscribe/Constitution.md`.