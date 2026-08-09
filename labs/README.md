# Labs

Course exercises. Each lesson is a standalone runnable in its own package.

## Setup

Copy `src/main/resources/application.properties.example` to `application.properties` and fill in your values.

`labs.lessons.dir` points at the per-lesson inputs — `system.md` and `task.properties`,
one directory per lesson. They live outside this repo and are not committed.

`task.properties` binds to a record. Scalars are plain keys; lists are indexed from 1, and
the record component is a `List<String>`:

```properties
colour=green
flavours.1=salt
flavours.2=pepper
```

## Data directories

`labs/data/{lesson}/` holds files fetched from the hub. **These appear at runtime** —
`HubClient` creates them on first fetch, and nothing under `labs/data/` is committed. A
fresh clone has no `data/` directory at all; that's expected, not a missing step.

## Running a lesson

From the repo root. `-am` builds `llm-core` too — without it Maven resolves a possibly
stale `llm-core` from `~/.m2` rather than the reactor:

```bash
./mvnw -am -pl labs exec:java -Dexec.mainClass=io.github.dbonkowska.dscribe.labs.s01e01.S01E01
```

Or run the lesson class directly from your IDE.

## Lessons

| Lesson | Concept |
|--------|---------|
| s01e01 | Structured output — closed tag vocabulary enforced by a JSON Schema enum |
