import { readFile } from "node:fs/promises";

const catalogs = await Promise.all(
  ["en", "vi"].map(async (locale) => [
    locale,
    JSON.parse(await readFile(new URL(`../messages/${locale}.json`, import.meta.url), "utf8")),
  ]),
);

function flatten(value, prefix = "", output = new Map()) {
  for (const [key, child] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (child && typeof child === "object" && !Array.isArray(child)) flatten(child, path, output);
    else output.set(path, child);
  }
  return output;
}

function placeholders(message) {
  if (typeof message !== "string") return [];
  const names = new Set();
  for (const match of message.matchAll(/\{([A-Za-z][\w]*)\b/g)) names.add(`arg:${match[1]}`);
  for (const match of message.matchAll(/<\/?([A-Za-z][\w]*)>/g)) names.add(`tag:${match[1]}`);
  return [...names].sort();
}

const [baseLocale, baseCatalog] = catalogs[0];
const base = flatten(baseCatalog);
let failed = false;

for (const [locale, catalog] of catalogs.slice(1)) {
  const current = flatten(catalog);
  const missing = [...base.keys()].filter((key) => !current.has(key));
  const extra = [...current.keys()].filter((key) => !base.has(key));
  if (missing.length || extra.length) {
    failed = true;
    if (missing.length) console.error(`${locale}: missing keys\n  ${missing.join("\n  ")}`);
    if (extra.length) console.error(`${locale}: extra keys\n  ${extra.join("\n  ")}`);
  }
  for (const [key, message] of base) {
    if (!current.has(key)) continue;
    const expected = placeholders(message);
    const actual = placeholders(current.get(key));
    if (expected.join("|") !== actual.join("|")) {
      failed = true;
      console.error(`${locale}: placeholder mismatch at ${key} (${baseLocale}: ${expected.join(", ") || "none"}; ${locale}: ${actual.join(", ") || "none"})`);
    }
  }
}

if (failed) process.exitCode = 1;
else console.log(`Message catalogs match: ${base.size} keys across ${catalogs.length} locales.`);
