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
import toast from "react-hot-toast";
import {
  ArrowUp,
  Bot,
  FileText,
  Loader2,
  Sparkles,
  User,
} from "lucide-react";

import type {
  BizAppEntity,
  BizAppInstalledAppView,
  BizAppRecordView,
} from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import { AssistantResponse } from "@/components/chat/AssistantResponse";
import { createChatSessionApi, submitChatMessageApi } from "@/lib/chat-api";
import { BizAppEntityFormDialog } from "@/components/bizapp/BizAppEntityFormDialog";
import {
  type BizAppActionSuggestion,
  extractActionSuggestion,
} from "./action-suggestion";

interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
  action?: BizAppActionSuggestion | null;
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

  // Human-in-the-Loop Action Dialog State
  const [isActionDialogOpen, setIsActionDialogOpen] = useState(false);
  const [activeActionEntity, setActiveActionEntity] = useState<BizAppEntity | null>(null);
  const [activeActionRecord, setActiveActionRecord] = useState<BizAppRecordView | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  const block = app.manifest.blocks?.find((b) => b.id === blockId);
  const instructions =
    typeof block?.params?.instructions === "string" ? block.params.instructions : null;
  const kbId =
    typeof app.config?.knowledgeBaseId === "string"
      ? (app.config.knowledgeBaseId as string)
      : undefined;

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
      const resp = await submitChatMessageApi(request, sessionId, query, {
        onContent: (chunk: string) => {
          setStreamingContent((prev) => (prev ?? "") + chunk);
        },
        onReset: () => {
          setStreamingContent("");
        },
      });

      // Detect Human-in-the-Loop actionable suggestion
      let actionSuggestion: BizAppActionSuggestion | null = null;
      if (resp.action && typeof resp.action === "object" && "type" in resp.action) {
        actionSuggestion = resp.action as unknown as BizAppActionSuggestion;
      } else {
        actionSuggestion = extractActionSuggestion(query, app);
      }

      const assistantMsg: Message = {
        id: crypto.randomUUID(),
        role: "assistant",
        content: resp.content || streamingContent || "",
        action: actionSuggestion,
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

  const handleTriggerAction = (action: BizAppActionSuggestion) => {
    const targetEntity = (app.manifest.entities || []).find((e) => e.name === action.entityName);
    if (!targetEntity) {
      toast.error(`Entity ${action.entityName} not found`);
      return;
    }

    const draftRecord: BizAppRecordView = {
      id: "",
      appKind: app.kind,
      entity: action.entityName,
      payload: action.payload || {},
      runtime: {},
      status: "ACTIVE",
      createdBy: "",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };

    setActiveActionEntity(targetEntity);
    setActiveActionRecord(draftRecord);
    setIsActionDialogOpen(true);
  };

  const handleActionSaved = () => {
    setIsActionDialogOpen(false);
    toast.success(t("action.recordCreated", { fallback: "Đã tạo bản ghi thành công!" }));
    const confirmMsg: Message = {
      id: crypto.randomUUID(),
      role: "assistant",
      content: `✓ ${t("action.recordCreated", { fallback: "Đã tạo bản ghi thành công." })}`,
    };
    setMessages((prev) => [...prev, confirmMsg]);
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
                {t("assistant.welcome", {
                  fallback: "Ask questions about records and processes in this app.",
                })}
              </p>
              <p className="text-xs text-muted-foreground mt-1">
                {t("assistant.evidenceHint", {
                  fallback: "Answers cite verified documents from this application's knowledge base.",
                })}
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
                className={`rounded-xl px-4 py-2.5 text-sm max-w-[85%] ${
                  msg.role === "user"
                    ? "bg-primary text-primary-foreground"
                    : "bg-muted text-foreground"
                }`}
              >
                {msg.role === "assistant" ? (
                  <>
                    <AssistantResponse content={msg.content} />
                    {/* Human-in-the-Loop Action Suggestion Card */}
                    {msg.action && (
                      <div className="mt-3 p-3 rounded-lg border border-primary/20 bg-card text-card-foreground shadow-xs space-y-2">
                        <div className="flex items-center gap-2">
                          <Sparkles className="h-4 w-4 text-primary shrink-0" />
                          <span className="font-semibold text-xs text-foreground">
                            {msg.action.label}
                          </span>
                        </div>
                        {msg.action.payload && Object.keys(msg.action.payload).length > 0 && (
                          <div className="flex flex-wrap gap-1.5 pt-0.5">
                            {Object.entries(msg.action.payload).map(([k, v]) => (
                              <span
                                key={k}
                                className="text-[11px] bg-muted/80 border px-1.5 py-0.5 rounded font-mono text-muted-foreground"
                              >
                                <span className="font-medium text-foreground">{k}:</span> {String(v)}
                              </span>
                            ))}
                          </div>
                        )}
                        <div className="pt-1.5 flex justify-end">
                          <Button
                            size="sm"
                            variant="default"
                            onClick={() => handleTriggerAction(msg.action!)}
                            className="h-7 text-xs gap-1.5 font-medium"
                          >
                            <FileText className="h-3.5 w-3.5" />
                            <span>
                              {t("action.reviewAndConfirm", { fallback: "Xem lại & Xác nhận" })}
                            </span>
                          </Button>
                        </div>
                      </div>
                    )}
                  </>
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
              <div className="rounded-xl px-4 py-2.5 text-sm max-w-[85%] bg-muted text-foreground">
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

      {/* Human-in-the-Loop Action Confirmation Dialog */}
      {activeActionEntity && (
        <BizAppEntityFormDialog
          app={app}
          entity={activeActionEntity}
          record={activeActionRecord}
          open={isActionDialogOpen}
          onClose={() => setIsActionDialogOpen(false)}
          onSaved={handleActionSaved}
        />
      )}
    </Card>
  );
}
