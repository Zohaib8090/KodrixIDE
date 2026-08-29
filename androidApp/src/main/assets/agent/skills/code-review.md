# Code Review

When asked to review code, do a structured pass focused on:

1. **Correctness** — bugs, off-by-one errors, edge cases, null handling
2. **Safety** — resource leaks, uncaught exceptions, race conditions
3. **Style** — naming, structure, consistency with the surrounding code
4. **Performance** — only if there's an obvious hot path; don't over-optimize

For each finding, give:
- file path + line number
- the issue in 1 sentence
- the suggested fix in 1 sentence
- severity: blocker | major | minor | nit

Don't just list problems — propose a concrete patch. Keep the review short
(<= 5 highest-impact items); if the file is fine, say so explicitly.
