# Dscribe

Custom Java AI agent framework, built from scratch. Maven multi-module, Java 26.

- **`llm-core`** — reusable LLM integration over OpenRouter. This is the library; treat it
  as one, and test it properly.
- **`labs`** — AI_Devs 4 course exercises. Each runs once to earn a flag, then is never
  revisited.

## Never put course task content in this repo

The repo is public. Prompts, task parameters, filter criteria, target names, expected
answers — none of it belongs here, in any form.

Those live in per-lesson bundles **outside** the repo, under `labs.lessons.dir` (set in
`.claude/dbbon-sdd.md`). `Lesson.of(config, "s01e01")` resolves the folder; the runner
reads `system.md` and binds `task.properties` to a record. It hardcodes neither.

When a new lesson needs a prompt or a criterion, it goes in that lesson's bundle — **not**
in a constant, not in a test fixture, not in a comment that restates the task. If you find
yourself typing a city name, a date range, or a person's name into a `.java` file, stop:
it belongs in `task.properties`.

This rule has no exceptions and nothing softens it.

## Running

Maven is not on PATH. Use the wrapper:

```bash
./mvnw -am -pl labs test
./mvnw -am -pl labs test -Dtest=SchemaUtilsTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -am -pl labs exec:java -Dexec.mainClass=io.github.dbonkowska.dscribe.labs.s01e01.S01E01
```

`-am` matters — without it Maven resolves `llm-core` from `~/.m2` instead of the reactor,
so you silently test against a stale jar.

`-Dsurefire.failIfNoSpecifiedTests=false` matters whenever you pass `-Dtest=`. `llm-core`
has no tests, so the filter matches nothing there and surefire aborts the reactor before
`labs` ever runs. Note the `surefire.` prefix — bare `-DfailIfNoSpecifiedTests` is
silently ignored.

## Testing

Test what the next lesson depends on; don't test what the hub already verifies. Shared
infrastructure (`SchemaUtils`, `LabsConfig`, `HubClient`, `Lesson`) fails silently and is
reused, so it gets tests. Per-lesson runners are verified by `hub.verify` rejecting a wrong
answer, so they don't. Full reasoning in `Atelier/Dscribe/Constitution.md`.