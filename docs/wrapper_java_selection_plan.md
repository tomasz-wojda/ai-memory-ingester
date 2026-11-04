# Plan: Java selection in the wrapper and a kept error log in record_turn

Status: proposal, written in PLAN mode on 2026-11-04. Nothing in it is started. Execution needs the owner's approval. Any deviation from it returns to PLAN mode.

## 1. Problem and evidence

One Cursor tab fails every journal write with a Java runtime error. The payload `tmp/journal-turns/turn-2026-11-04-1428-abort-timing.json` and 12 more payloads of the same tab (13:58 to 14:25 CET) were never recorded.

Evidence from 2026-11-04 (details in the session record):
- `dist/ai-memory-ingester.jar` is compiled for Java 27 (class file version 71). Every jar since 2026-10-31 has the same version.
- Under JDK 17, 21 and 25 the jar fails with `java.lang.UnsupportedClassVersionError` (class file version 71.0). Under JDK 27 it works.
- Four JDKs are installed (`/opt/java/openjdk-17`, `-21`, `-25`, `-27`). `/opt/java/current` and `/opt/groovy/current` were switched at 08:00.
- Other Cursor writes succeeded in the same minutes, so the cause is the environment of one tab. The wrapper takes `java` from `PATH` without any check.
- The global command `~/.local/bin/sweep-memory` runs the same wrapper, so it has the same weakness.
- The exact error text of the tab was not seen. The cause is a strong hypothesis until step 1.
- Related weakness: if `groovy` is not on `PATH`, the wrapper exits with status 1 and no message (`set -e` after `command -v groovy`).

## 2. Scope

1. The wrapper `repos/ai-memory-ingester/ai-memory-ingester` selects a suitable Java and Groovy, or stops with a clear message.
2. `repos/ai-vault/skills/worklog-chat-memory/scripts/record_turn.py` keeps the error of a failed write in a file next to the payload.
3. Replay of the 13 stuck payloads after both changes work.

Out of scope: compiling the jar for Java 17 (it would let all four JDKs run it, and needs its own plan and a full test run).

## 3. Specification

### 3.1 Wrapper (`repos/ai-memory-ingester/ai-memory-ingester`)

- **Required Java.** The wrapper reads the integer in `dist/required-java` (one line, the Java major version the jar was built with). If the file is missing, the value is 27. `build-precompiled.sh` writes the file after a successful build from `java.specification.version` of the JDK that ran the build. The first file is created from the class file version of the current jar (71 minus 44 gives 27), without rebuilding the jar.
- **Candidate order.** (1) `AI_MEMORY_JAVA_HOME`, if set; (2) the home of `java` on `PATH`; (3) `JAVA_HOME`; (4) `/opt/java/current`; (5) every `/opt/java/openjdk-*` from the highest version down; (6) on macOS, the answer of `/usr/libexec/java_home -v <required>+` when it exists. The folder for step 5 comes from `AI_MEMORY_JAVA_SEARCH` (default `/opt/java`) so tests can point it elsewhere.
- **Version of a candidate.** Read `JAVA_VERSION` from `<home>/release` (no process start). `1.8.x` style gives major 8, other values give the first number. If the file is unreadable, run `<home>/bin/java -version` once and parse it (slow path only). The home of the `java` on `PATH` is found by following the symlinks of the file with `readlink` (at most 10 hops, no `readlink -f`) and then taking the parent of its `bin` folder.
- **Choice.** The first candidate with a major version at least the required one and an executable `bin/java` wins. An `AI_MEMORY_JAVA_HOME` that is set but unsuitable is an error: it is not skipped.
- **Note on fallback.** If the chosen Java is not the one on `PATH`, print one line to stderr: `ai-memory-ingester: java <version> at <path> is older than the required <N>; using <chosen path>`. Nothing is printed when `PATH` already holds a suitable Java.
- **No suitable Java.** Print to stderr the required version, each candidate checked with its version, and the hint to set `AI_MEMORY_JAVA_HOME`. Exit status 3.
- **Groovy.** `GROOVY_HOME` if set; else the parent of the folder that holds `groovy` on `PATH`; else `/opt/groovy/current`. The folder must contain `lib/`. Otherwise print a clear message and exit 3 (today: silent exit 1).
- **Unchanged.** The launch line (class path, `PrecompiledRunner`, `--enable-native-access=ALL-UNNAMED`), the `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` flags for `query` and `03` only, the missing-jar message, `set -euo pipefail`, and compatibility with the system bash 3.2 (no associative arrays, empty-array idiom kept).
- **Cost.** No new process on the fast path. The only addition is a few file reads.

### 3.2 `record_turn.py` (`repos/ai-vault/skills/worklog-chat-memory/scripts/record_turn.py`)

- Start `record-event` with `stderr` captured. Always write the captured text to this script's own stderr, so the output a person sees does not change. Standard output still goes straight through (it carries the `record-event ...` result line).
- On a non-zero exit code, write `<payload file name>.error.log` next to the payload with: UTC time, exit code, the command and working folder, the payload path, `JAVA_HOME`, the path of `java` found on `PATH`, the first line of `java -version` (best effort, 5 s limit), and the captured stderr (at most 64 KB). It must not contain the payload text.
- The final stderr line becomes `record_turn: record-event failed (exit <N>); details in <log path>; prompt.log not appended`. The phrase `prompt.log not appended` stays.
- On success, delete a stale error log of the same payload.
- Unchanged: the memory lock, exit codes 2, 75 and the pass-through of the ingester's exit code, the rule that `prompt.log` is appended only after a successful `record-event`, and `--keep-payload`.

### 3.3 Replay of the stuck payloads

After both changes are verified, run `record_turn.py --payload <file>` for each stuck payload in chronological order (`devops849`, five `goal` files, `criterion7`, `handoff`, `analyze`, `fix`, `criterion7-abort`, `abort-result`, `abort-timing`). The journal write is idempotent, so `abort-timing` (already journaled as sequence 28853) reports success and still gets its `prompt.log` entry. Some turns may also exist as transcript-derived entries (a reconcile batch at 13:22Z). The journal keeps both with a fidelity rank. The replay needs the owner's confirmation because it appends to `prompt.log`.

## 4. Tests

**Wrapper:** new `repos/ai-memory-ingester/tests/test_wrapper.sh` (bash, same pass and fail summary style as the other suites). It copies the wrapper into a temporary root next to a fake jar, fake JDK homes (a `release` file and a `bin/java` stub that prints its path and arguments) and a fake Groovy home. Cases:
- `PATH` Java at the required version is used and nothing is printed to stderr.
- `PATH` Java 21 with a Java 27 candidate: the 27 is used and the one-line note appears.
- No candidate high enough: exit 3 and the message lists every candidate.
- `AI_MEMORY_JAVA_HOME` valid is used; invalid gives an error.
- `dist/required-java` of 25 accepts a Java 25; a missing file means 27.
- A `release` file with `1.8.0` gives major 8; a Homebrew-style symlinked `java` file resolves to the right home; a home without a `release` file uses the slow path.
- Groovy missing gives exit 3 and a message; `GROOVY_HOME` is used when set.
- `query` and `03` get the two JVM flags and `record-event` does not.
- The whole suite also runs under `/bin/bash` 3.2.

**record_turn:** extend `repos/ai-vault/tests/test_harness_record_turn.py`. The fake ingester helper takes an optional stderr text. New tests: a failure writes `<payload>.error.log` with the exit code, the stderr text and the Java lines; the final message names the log and still contains `prompt.log not appended`; the log does not contain the payload text; a stderr of more than 64 KB is cut; a success removes a stale log; the result line on stdout still passes through; the existing tests keep passing unchanged.

## 5. Regression scope

Must not change: every command of `ai-memory-ingester` with a suitable Java on `PATH` (output and exit codes), the query JVM flags, `sweep-memory` through `~/.local/bin/sweep-memory`, `record_turn.py` behavior on success, and all suites of both repositories.

Checks: the 12 golden queries through the previous and the new wrapper (identical); all 36 command names with `--help` through both wrappers, after removing timing fields (identical; note that `--help` runs `sweep-memory`, `chatlog`, `ingest-chatlog`, `reconcile-transcripts`, `project-journal`, `reconcile-project`, `09` and `decompress`, which were no-ops on 2026-11-04); the real wrapper with `PATH` set to JDK 21 first now succeeds with the note; the 11 Groovy suites and the Python service tests of `ai-memory-ingester`; the full pytest run of `repos/ai-vault/tests`.

## IMPLEMENTATION CHECKLIST

1. [ ] OWNER: in the failing tab run `java -version`, `echo $JAVA_HOME` and `repos/ai-memory-ingester/ai-memory-ingester datasets 2>&1 | head -3`, and send the output. If it is not `UnsupportedClassVersionError`, return to PLAN. _Substitute check done 2026-11-04: under `JAVA_HOME` and `PATH` pinned to JDK 17, 21 or 25 the previous wrapper fails with `UnsupportedClassVersionError` and the new wrapper succeeds with the note. The failing tab's own output was not seen, so the cause is still not confirmed for that tab._
2. [x] Run the Groovy suites, the Python service tests and `pytest repos/ai-vault/tests` and save the pass counts. Stop on any failure.
3. [x] Save the current wrapper outside the repository for the comparison. Capture the 12 golden outputs with it.
4. [x] Create `dist/required-java` with 27, derived from the class file version of the current jar.
5. [x] Change `build-precompiled.sh` to write `dist/required-java` after a successful build.
6. [x] Change the wrapper as in section 3.1.
7. [x] Create `tests/test_wrapper.sh` with the cases of section 4 and run it under the current bash and `/bin/bash` 3.2. _41 of 41 pass under bash, `/bin/bash` 3.2 and `sh`._
8. [x] Run the 12 golden queries through the saved wrapper and the new one. They must be identical.
9. [x] Run all 36 command names with `--help` through both wrappers. They must be identical after removing timing fields. _33 of 36 identical. The other three (`chatlog`, `ingest-chatlog`, `sweep-memory`) really run with `--help` and other tabs were writing, so the state moved between the two runs (for example `appended=2` against `appended=1`). The wrapper is not the cause. `--help` on these commands has side effects: avoid it._
10. [x] Run the new wrapper with `PATH` starting with `/opt/java/openjdk-21/bin`. It must succeed and print the one-line note. Run it with `AI_MEMORY_JAVA_SEARCH` pointing to an empty folder and `PATH` Java 21: it must exit 3 with the message.
11. [x] Run `~/.local/bin/sweep-memory` once and confirm it behaves as before.
12. [x] Change `record_turn.py` as in section 3.2.
13. [x] Extend `tests/test_harness_record_turn.py` with the tests of section 4 and run the whole file and the full `tests/` folder of `repos/ai-vault`. _`ai-vault`: 113 tests pass (105 before, 8 new)._
14. [x] Run all Groovy suites and the Python service tests of `ai-memory-ingester` again and compare the counts with step 2.
15. [x] Render the commit handoff once for `ai-memory-ingester` (wrapper, `build-precompiled.sh`, `dist/required-java`, `tests/test_wrapper.sh`) and once for `ai-vault` (`record_turn.py`, the test file).
16. [x] OWNER: confirm the replay of the 13 stuck payloads, then replay them in order (section 3.3). Check each result line and that each payload file was deleted. _Done 2026-11-04 on the owner's go-ahead: 18 stuck payloads (5 more than the 13 listed, from the same tab until 14:59 CET) replayed in event-time order, all with exit 0 and all files deleted. New journal sequences 29005 to 29021. `abort-timing` was already journaled (28853) and got its `prompt.log` entry. No error logs were written._
17. [x] Record the turn through the governed worklog writer.

Any deviation from this plan returns to PLAN mode.

## Execution notes (2026-11-04)

- All steps are done. Step 1 is confirmed only by the substitute check, not by the failing tab's own output.
- Small additions to the specification: the variable `AI_MEMORY_GROOVY_DEFAULT` (default `/opt/groovy/current`) for the Groovy fallback, so the missing-Groovy case can be tested.
- Not in the plan, flagged for the owner: `repos/ai-vault` requires a skill version bump when a skill changes (`VERSIONING.md`). `skills/worklog-chat-memory/SKILL.md` is at 1.5.0. A minor bump to 1.6.0 follows the precedent of the lock change (1.4.0). It was not done because it is outside this plan.
- `./scripts/validate-skills.sh` in `repos/ai-vault` exits 0 with the changed script.
- Test notes: the wrapper test suite needs a resolved temporary folder on macOS (`/tmp` is a link to `/private/tmp`), and in POSIX mode a variable set before a function call stays set, so the test resets it.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it.
