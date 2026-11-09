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

"use client";

import { useState } from "react";
import { Check, Copy, Wand2 } from "lucide-react";
import type { BizAppManifest } from "@/types";
import { Button } from "@/components/ui/button";

interface JsonTabProps {
  manifest: BizAppManifest;
  onChange: (manifest: BizAppManifest) => void;
}

export function JsonTab({ manifest, onChange }: JsonTabProps) {
  const [text, setText] = useState(() => JSON.stringify(manifest, null, 2));
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const [prevManifest, setPrevManifest] = useState(manifest);
  if (manifest !== prevManifest) {
    setPrevManifest(manifest);
    setText(JSON.stringify(manifest, null, 2));
  }

  const handleChange = (newText: string) => {
    setText(newText);
    try {
      const parsed = JSON.parse(newText);
      setError(null);
      onChange(parsed);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invalid JSON syntax");
    }
  };

  const formatJson = () => {
    try {
      const parsed = JSON.parse(text);
      setText(JSON.stringify(parsed, null, 2));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Cannot format invalid JSON");
    }
  };

  const copyToClipboard = async () => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-background p-4 space-y-2">
      <div className="flex items-center justify-between pb-2 border-b">
        <span className="text-xs text-muted-foreground font-mono">
          Canonical bizapp.v1 manifest JSON
        </span>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={formatJson} className="h-7 text-xs gap-1.5">
            <Wand2 className="h-3.5 w-3.5" />
            <span>Format</span>
          </Button>
          <Button variant="outline" size="sm" onClick={copyToClipboard} className="h-7 text-xs gap-1.5">
            {copied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
            <span>{copied ? "Copied" : "Copy"}</span>
          </Button>
        </div>
      </div>

      <textarea
        value={text}
        onChange={(e) => handleChange(e.target.value)}
        className="flex-1 w-full bg-muted/20 font-mono text-xs p-3 rounded-lg border focus:outline-none focus:ring-1 focus:ring-primary resize-none leading-relaxed"
        spellCheck={false}
      />

      {error && (
        <div className="text-xs text-destructive bg-destructive/10 px-3 py-1.5 rounded">
          {error}
        </div>
      )}
    </div>
  );
}
