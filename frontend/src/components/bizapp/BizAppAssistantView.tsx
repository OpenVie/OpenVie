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

import { useState, useRef, useEffect, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { ArrowUp, Bot, Loader2, Sparkles, User } from "lucide-react";

import type { BizAppInstalledAppView } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { AssistantResponse } from "@/components/chat/AssistantResponse";
import { createChatSessionApi, submitChatMessageApi } from "@/lib/chat-api";

interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
}

export function BizAppAssistantView({
  app,
  blockId,
}: {
  app: BizAppInstalledAppView;
  blockId: string;
}) {
  const t = useTranslations("BizApps");
  const { request } = useApiClient();

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [streamingContent, setStreamingContent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  const block = app.manifest.blocks?.find((b) => b.id === blockId);
  const instructions = typeof block?.params?.instructions === "string" ? block.params.instructions : null;
  const kbId = typeof app.config?.knowledgeBaseId === "string" ? (app.config.knowledgeBaseId as string) : undefined;

  const initiatedRef = useRef(false);
  useEffect(() => {
    if (initiatedRef.current) return;
    initiatedRef.current = true;
    let active = true;
    const init = async () => {
      try {
        const resp = await createChatSessionApi(request, {
          chatbot_id: "",
          knowledge_base_id: kbId ?? "",
          locale: "vi-VN",
        });
        if (active) setSessionId(resp.id);
      } catch (err) {
        if (active) setError(err instanceof Error ? err.message : "Failed to initialize assistant session");
      }
    };
    void init();
    return () => {
      active = false;
    };
  }, [request, kbId]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamingContent]);

  const handleSend = async (e: FormEvent) => {
    e.preventDefault();
    const query = input.trim();
    if (!query || !sessionId || isSending) return;

    setInput("");
    setError(null);
    setIsSending(true);

    const userMsg: Message = { id: crypto.randomUUID(), role: "user", content: query };
    setMessages((prev) => [...prev, userMsg]);
    setStreamingContent("");

    try {
      const resp = await submitChatMessageApi(
        request,
        sessionId,
        query,
        {
          onContent: (chunk: string) => {
            setStreamingContent((prev) => (prev ?? "") + chunk);
          },
          onReset: () => {
            setStreamingContent("");
          },
        }
      );

      const assistantMsg: Message = {
        id: crypto.randomUUID(),
        role: "assistant",
        content: resp.content || streamingContent || "",
      };
      setMessages((prev) => [...prev, assistantMsg]);
      setStreamingContent(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Error sending message to assistant");
    } finally {
      setIsSending(false);
      setStreamingContent(null);
    }
  };

  return (
    <Card className="flex flex-col h-[650px] shadow-sm">
      <CardHeader className="border-b pb-3">
        <div className="flex items-center gap-2">
          <Bot className="h-5 w-5 text-primary" />
          <CardTitle className="text-base font-semibold">
            {app.nameEn || app.kind} Assistant
          </CardTitle>
        </div>
        {instructions && (
          <CardDescription className="text-xs line-clamp-2">
            {instructions}
          </CardDescription>
        )}
      </CardHeader>

      <CardContent className="flex-1 flex flex-col p-4 overflow-hidden">
        <div className="flex-1 overflow-y-auto space-y-4 pr-1">
          {messages.length === 0 && !streamingContent && (
            <div className="h-full flex flex-col items-center justify-center text-center p-6 text-muted-foreground">
              <Sparkles className="h-10 w-10 text-muted-foreground/50 mb-3" />
              <p className="text-sm font-medium">
                {t("assistant.welcome", { fallback: "Ask questions about records and processes in this app." })}
              </p>
              <p className="text-xs text-muted-foreground mt-1">
                {t("assistant.evidenceHint", { fallback: "Answers cite verified documents from this application's knowledge base." })}
              </p>
            </div>
          )}

          {messages.map((msg) => (
            <div
              key={msg.id}
              className={`flex gap-3 ${msg.role === "user" ? "justify-end" : "justify-start"}`}
            >
              {msg.role === "assistant" && (
                <div className="h-7 w-7 rounded-full bg-primary/10 text-primary flex items-center justify-center shrink-0">
                  <Bot className="h-4 w-4" />
                </div>
              )}
              <div
                className={`rounded-xl px-4 py-2.5 text-sm max-w-[80%] ${
                  msg.role === "user"
                    ? "bg-primary text-primary-foreground"
                    : "bg-muted text-foreground"
                }`}
              >
                {msg.role === "assistant" ? (
                  <AssistantResponse content={msg.content} />
                ) : (
                  <p className="whitespace-pre-wrap">{msg.content}</p>
                )}
              </div>
              {msg.role === "user" && (
                <div className="h-7 w-7 rounded-full bg-muted flex items-center justify-center shrink-0">
                  <User className="h-4 w-4 text-muted-foreground" />
                </div>
              )}
            </div>
          ))}

          {streamingContent !== null && (
            <div className="flex gap-3 justify-start">
              <div className="h-7 w-7 rounded-full bg-primary/10 text-primary flex items-center justify-center shrink-0">
                <Bot className="h-4 w-4" />
              </div>
              <div className="rounded-xl px-4 py-2.5 text-sm max-w-[80%] bg-muted text-foreground">
                <AssistantResponse content={streamingContent} />
                <span className="inline-block animate-pulse">▍</span>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {error && (
          <div className="text-xs text-destructive bg-destructive/10 p-2 rounded mt-2">
            {error}
          </div>
        )}

        <form onSubmit={handleSend} className="pt-3 border-t flex gap-2">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={t("assistant.placeholder", { fallback: "Ask a question..." })}
            className="flex-1 bg-background border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/20"
            disabled={isSending || !sessionId}
          />
          <Button type="submit" size="sm" disabled={isSending || !input.trim() || !sessionId}>
            {isSending ? <Loader2 className="h-4 w-4 animate-spin" /> : <ArrowUp className="h-4 w-4" />}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
