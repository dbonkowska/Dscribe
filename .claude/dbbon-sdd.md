# dbbon-sdd — Dscribe

```
vault_folder:   Dscribe
title_prefixes: ai_devs4, core, setup
issue_body:     brief
gh_project:     Dscribe
test_single:    ./mvnw test -Dtest={test} -Dsurefire.failIfNoSpecifiedTests=false
test_all:       ./mvnw test
```

**`title_prefixes`** — these name **what drove the work**, not which module it touched.
Most lessons land in both `llm-core` and `labs`, so a module-based prefix wouldn't
partition anything.

- `ai_devs4` — driven by that course. A later edition or a different course gets its own
  prefix, and old titles stay accurate forever.
- `core` — self-driven framework work: the RAG / streaming / memory / ReAct roadmap.
  Reads as "not course-driven".
- `setup` — build, publishing, tooling.

If an issue ever seems to need two prefixes, the axis is wrong — use GitHub labels for
secondary dimensions like which module it touches.

**`issue_body: brief`** — this repo mixes framework work with AI_Devs course exercises.
Course task content stays in the vault; GitHub Issues get a capability brief describing
what the framework gained. Lead the folder name with the lesson code where one applies,
e.g. `7 - s01e02 {short name}`. The lesson code publishes; the name it goes by does not,
so keep the descriptive half generic — it names a capability, not the task.

**Maven is not on PATH.** Use `./mvnw` — the wrapper was generated 2026-08-05 and pins
Maven 3.9.14. Before that this repo had no wrapper at all (`.mvn/` was an empty
directory), and the only working invocation was the full path into the wrapper dist,
which contained a hash directory that breaks whenever the dist re-downloads.

**`test_single` needs `-Dsurefire.failIfNoSpecifiedTests=false`.** `llm-core` has no tests,
so a `-Dtest=` filter matches nothing there and surefire aborts the whole reactor before
`labs` runs. The prefix matters — bare `-DfailIfNoSpecifiedTests` is silently ignored.

**Before running `labs`**, `llm-core` must be installed — `mvn -pl labs` resolves it from
`~/.m2`, not the reactor, so a stale jar is used otherwise. Use `-am`, or install first.
