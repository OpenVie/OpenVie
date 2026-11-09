// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Synchronizes the PDF.js worker into `public/` from the installed
// `pdfjs-dist` dependency. The worker is never committed to the source tree:
// `npm ci` restores the exact upstream version and this script copies the
// artifact at build time, so the shipped worker always matches the declared
// dependency range (see NOTICE, "PDF.js").
import { copyFileSync, existsSync, mkdirSync } from "node:fs"
import { resolve } from "node:path"

const root = resolve(import.meta.dirname, "..")
const source = resolve(root, "node_modules/pdfjs-dist/build/pdf.worker.min.mjs")
const targetDir = resolve(root, "public")
const target = resolve(targetDir, "pdf.worker.min.mjs")

if (!existsSync(source)) {
  console.error(
    "pdf.worker sync failed: node_modules/pdfjs-dist/build/pdf.worker.min.mjs not found. " +
      "Run `npm ci` first.",
  )
  process.exit(1)
}

mkdirSync(targetDir, { recursive: true })
copyFileSync(source, target)
console.log("pdf.worker synced", resolve(source))
