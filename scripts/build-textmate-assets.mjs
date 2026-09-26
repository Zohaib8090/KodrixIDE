// Builds androidApp/src/main/assets/textmate/ for the experimental (Sora) editor from
// VS Code's MIT-licensed built-in grammars, language configurations and Dark Modern theme.
//
// Usage (Node 18+, from the repo root):
//   node scripts/build-textmate-assets.mjs
//
// Pinned to a VS Code commit so rebuilding gives the same files. To update, change REF.
import fs from 'node:fs';
import path from 'node:path';

const REF = process.env.VSCODE_REF || '90da900128e93064765cc94c989059265443b389';
const BASE = `https://raw.githubusercontent.com/microsoft/vscode/${REF}`;
const OUT = path.resolve('androidApp/src/main/assets/textmate');

async function get(p) {
  const r = await fetch(`${BASE}/${p}`);
  if (!r.ok) throw new Error(`${p}: HTTP ${r.status}`);
  return r.text();
}

// [Kodrix name, grammar path in vscode/extensions, language-configuration path or null]
const LANGS = [
  ['javascript', 'javascript/syntaxes/JavaScript.tmLanguage.json', 'javascript/javascript-language-configuration.json'],
  ['typescript', 'typescript-basics/syntaxes/TypeScript.tmLanguage.json', 'typescript-basics/language-configuration.json'],
  ['typescriptreact', 'typescript-basics/syntaxes/TypeScriptReact.tmLanguage.json', 'typescript-basics/language-configuration.json'],
  ['html', 'html/syntaxes/html.tmLanguage.json', 'html/language-configuration.json'],
  ['css', 'css/syntaxes/css.tmLanguage.json', 'css/language-configuration.json'],
  ['json', 'json/syntaxes/JSON.tmLanguage.json', 'json/language-configuration.json'],
  ['markdown', 'markdown-basics/syntaxes/markdown.tmLanguage.json', 'markdown-basics/language-configuration.json'],
  ['python', 'python/syntaxes/MagicPython.tmLanguage.json', 'python/language-configuration.json'],
  ['c', 'cpp/syntaxes/c.tmLanguage.json', 'cpp/language-configuration.json'],
  ['cpp', 'cpp/syntaxes/cpp.tmLanguage.json', 'cpp/language-configuration.json'],
  ['rust', 'rust/syntaxes/rust.tmLanguage.json', 'rust/language-configuration.json'],
  ['shellscript', 'shellscript/syntaxes/shell-unix-bash.tmLanguage.json', 'shellscript/language-configuration.json'],
  ['java', 'java/syntaxes/java.tmLanguage.json', 'java/language-configuration.json'],
  ['yaml', 'yaml/syntaxes/yaml.tmLanguage.json', 'yaml/language-configuration.json'],
  // Included by the YAML grammar by scope name; no file type of their own.
  ['yaml-1.0', 'yaml/syntaxes/yaml-1.0.tmLanguage.json', null],
  ['yaml-1.1', 'yaml/syntaxes/yaml-1.1.tmLanguage.json', null],
  ['yaml-1.2', 'yaml/syntaxes/yaml-1.2.tmLanguage.json', null],
  ['yaml-1.3', 'yaml/syntaxes/yaml-1.3.tmLanguage.json', null],
  ['yaml-embedded', 'yaml/syntaxes/yaml-embedded.tmLanguage.json', null],
];

// VS Code themes are JSONC and chain through "include"; Sora needs one plain JSON file.
function jsonc(text) {
  let out = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {                       // copy strings verbatim ("//" can appear in URLs)
      let j = i + 1;
      while (j < text.length && text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j;
    } else if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && text[i + 1] === '*') {
      i = text.indexOf('*/', i + 2) + 1;
    } else {
      out += c;
    }
  }
  return JSON.parse(out.replace(/,(\s*[}\]])/g, '$1'));
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(`${OUT}/grammars`, { recursive: true });
fs.mkdirSync(`${OUT}/configs`, { recursive: true });
fs.mkdirSync(`${OUT}/themes`, { recursive: true });

const languages = [];
for (const [name, grammar, config] of LANGS) {
  const text = await get(`extensions/${grammar}`);
  fs.writeFileSync(`${OUT}/grammars/${name}.json`, text);
  const entry = { name, scopeName: JSON.parse(text).scopeName, grammar: `textmate/grammars/${name}.json` };
  if (config) {
    const cfgName = config.split('/')[0];
    // Written as plain JSON: VS Code's files are JSONC and the editor's reader may reject comments.
    const cfg = jsonc(await get(`extensions/${config}`));
    fs.writeFileSync(`${OUT}/configs/${cfgName}.json`, JSON.stringify(cfg, null, 2) + '\n');
    entry.languageConfiguration = `textmate/configs/${cfgName}.json`;
  }
  languages.push(entry);
}
fs.writeFileSync(`${OUT}/languages.json`, JSON.stringify({ languages }, null, 2) + '\n');

const [vs, plus, modern] = await Promise.all(
  ['dark_vs', 'dark_plus', 'dark_modern'].map(async t => jsonc(await get(`extensions/theme-defaults/themes/${t}.json`))));
const theme = {
  name: 'Dark Modern',
  type: 'dark',
  colors: { ...vs.colors, ...plus.colors, ...modern.colors },
  tokenColors: [...(vs.tokenColors || []), ...(plus.tokenColors || []), ...(modern.tokenColors || [])],
};
fs.writeFileSync(`${OUT}/themes/dark_modern.json`, JSON.stringify(theme, null, 2) + '\n');

fs.writeFileSync(`${OUT}/LICENSE-vscode.txt`, await get('LICENSE.txt'));
fs.writeFileSync(`${OUT}/README.md`,
  'Grammars, language configurations and the Dark Modern theme used by the experimental\n' +
  'editor. They come from https://github.com/microsoft/vscode/tree/main/extensions\n' +
  '(MIT License, see LICENSE-vscode.txt). Generated by scripts/build-textmate-assets.mjs;\n' +
  'do not edit by hand.\n');

console.log(languages.map(l => `${l.name.padEnd(16)} ${l.scopeName}`).join('\n'));
console.log(`theme: ${theme.tokenColors.length} token rules, ${Object.keys(theme.colors).length} UI colors`);
