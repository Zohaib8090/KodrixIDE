# Kodrix IDE — `libs` Branch

This branch contains **only registry and marketplace data** for the Kodrix IDE app.

> ⚠️ The app source code lives in the [`main`](https://github.com/Zohaib8090/KodrixIDE/tree/main) branch.

---

## Files

| File | Purpose |
|------|---------|
| `versions.json` | Node.js runtime registry — lists all downloadable versions by ABI |
| `extensions.json` | Marketplace extension registry — lists all available extensions |
| `icons/` | Extension and runtime icons used in the marketplace |
| `packages/` | Future: hosted library/tool zip packages |

---

## How the App Uses This Branch

The Kodrix IDE app fetches data from this branch at runtime:

```
https://raw.githubusercontent.com/Zohaib8090/KodrixIDE/libs/versions.json
https://raw.githubusercontent.com/Zohaib8090/KodrixIDE/libs/extensions.json
```

To add a new Node.js version or extension, simply edit the JSON files in this branch — **no app rebuild needed**.
