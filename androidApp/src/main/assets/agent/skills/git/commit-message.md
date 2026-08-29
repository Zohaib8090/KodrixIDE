# Commit Message

Write commit messages in the Conventional Commits format:

```
<type>(<scope>): <subject>

<body>

<footer>
```

Types: `feat` (new feature), `fix` (bug fix), `refactor` (no behavior change),
`docs` (docs only), `test` (tests only), `chore` (tooling, deps, build).

Subject rules:
- imperative mood ("add", not "added")
- lowercase after the type
- no period at the end
- <= 72 chars

Body rules:
- wrap at 72 cols
- explain *what* and *why*, not *how*
- reference files/functions when useful

Footer rules:
- `BREAKING CHANGE:` prefix for anything incompatible
- `Refs: #123` for issue/PR links
