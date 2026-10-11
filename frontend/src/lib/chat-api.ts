import type {
  AssistantMessageResponse,
  ChatHistoryMessageResponse,
  ChatSessionResponse,
  PlaygroundSession,
} from "@/types";
import { getApiBase } from "@/lib/auth-api";
import { readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";

const CHAT_MESSAGE_TIMEOUT_MS = 90_000;

export class ChatMessageTimeoutError extends Error {
  constructor() {
    super("CHAT_MESSAGE_TIMEOUT");
    this.name = "ChatMessageTimeoutError";
  }
}

export async function createChatSessionApi(
  request: ApiRequest,
  payload: {
    chatbot_id: string;
    knowledge_base_id: string;
    locale: string;
  },
): Promise<ChatSessionResponse> {
  const res = await request(`${getApiBase()}/chat/sessions`, {
    method: "POST",
    body: JSON.stringify(payload),
  });
  return readJsonOrThrow<ChatSessionResponse>(res);
}

export async function listPlaygroundSessionsApi(
  request: ApiRequest,
  query: {
    limit?: number; cursor?: string | null; q?: string; status?: string;
    activityFrom?: string; activityTo?: string; sort?: string; direction?: "asc" | "desc";
    signal?: AbortSignal;
  } = {},
): Promise<{ items: PlaygroundSession[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ limit: String(query.limit ?? 30) });
  if (query.cursor) params.set("cursor", query.cursor);
  if (query.q) params.set("q", query.q.slice(0, 200));
  if (query.status) params.set("status", query.status);
  if (query.activityFrom) params.set("activity_from", query.activityFrom);
  if (query.activityTo) params.set("activity_to", query.activityTo);
  if (query.sort) params.set("sort", query.sort);
  if (query.direction) params.set("direction", query.direction);
  const res = await request(`${getApiBase()}/chat/playground/sessions?${params}`, { signal: query.signal });
  return {
    items: await readJsonOrThrow<PlaygroundSession[]>(res),
    nextCursor: res.headers.get("X-Next-Cursor"),
  };
}

export async function hidePlaygroundSessionApi(
  request: ApiRequest,
  sessionId: string,
): Promise<void> {
  const res = await request(`${getApiBase()}/chat/playground/sessions/${sessionId}`, {
    method: "DELETE",
  });
  if (!res.ok) await readJsonOrThrow(res);
}

export type ChatMessageStreamOptions = {
  onContent: (content: string) => void;
  onReset: () => void;
  signal?: AbortSignal;
};

export class ChatMessageStreamError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "ChatMessageStreamError";
  }
}

export async function submitChatMessageApi(
  request: ApiRequest,
  sessionId: string,
  content: string,
  { onContent, onReset, signal }: ChatMessageStreamOptions,
): Promise<AssistantMessageResponse> {
  const controller = new AbortController();
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  let timedOut = false;
  const abort = () => {
    controller.abort();
    void reader?.cancel().catch(() => {});
  };
  const timeout = setTimeout(() => {
    if (controller.signal.aborted) return;
    timedOut = true;
    abort();
  }, CHAT_MESSAGE_TIMEOUT_MS);
  const throwIfAborted = () => {
    if (!controller.signal.aborted) return;
    if (timedOut) throw new ChatMessageTimeoutError();
    throw new DOMException("The chat response was canceled.", "AbortError");
  };

  if (signal?.aborted) abort();
  else signal?.addEventListener("abort", abort, { once: true });

  try {
    throwIfAborted();
    const res = await request(`${getApiBase()}/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      headers: { Accept: "text/event-stream" },
      body: JSON.stringify({ content }),
      signal: controller.signal,
    });
    throwIfAborted();
    if (!res.ok) await readJsonOrThrow(res);
    if (res.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() !== "text/event-stream" || !res.body) {
      throw new ChatMessageStreamError("INVALID_STREAM", "The chat response could not be read. Please try again.");
    }

    reader = res.body.getReader();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let buffer = "";
    let event = "";
    let data: string[] = [];
    let completed: AssistantMessageResponse | undefined;
    const malformed = () => new ChatMessageStreamError("INVALID_STREAM", "The chat response was malformed. Please try again.");
    const consumeLine = (line: string) => {
      throwIfAborted();
      if (line === "") {
        if (data.length === 0) {
          event = "";
          return;
        }
        let payload: unknown;
        try {
          payload = JSON.parse(data.join("\n"));
        } catch {
          throw malformed();
        }
        if (!payload || typeof payload !== "object" || Array.isArray(payload)) throw malformed();
        const body = payload as Record<string, unknown>;
        switch (event) {
          case "content":
            if (typeof body.content !== "string" || !body.content.trim()) throw malformed();
            onContent(body.content);
            break;
          case "reset":
            onReset();
            break;
          case "complete":
            if (body.role !== "assistant" || typeof body.content !== "string" || !body.content.trim() || !Array.isArray(body.citations)) throw malformed();
            completed = payload as AssistantMessageResponse;
            break;
          case "error":
            if (typeof body.code !== "string" || typeof body.message !== "string" || !body.message.trim()) throw malformed();
            throw new ChatMessageStreamError(body.code, body.message);
          default:
            throw malformed();
        }
        event = "";
        data = [];
        return;
      }
      if (line.startsWith(":")) return;
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") event = value;
      if (field === "data") data.push(value);
    };

    while (!completed) {
      const { value, done } = await reader.read();
      throwIfAborted();
      try {
        buffer += decoder.decode(value, { stream: !done });
      } catch {
        throw malformed();
      }
      let start = 0;
      for (let index = 0; index < buffer.length; index += 1) {
        const char = buffer[index];
        if (char !== "\r" && char !== "\n") continue;
        // A CR at the end of a chunk may be the first half of CRLF.
        if (char === "\r" && index === buffer.length - 1 && !done) break;
        consumeLine(buffer.slice(start, index));
        if (char === "\r" && buffer[index + 1] === "\n") index += 1;
        start = index + 1;
        if (completed) break;
      }
      buffer = buffer.slice(start);
      if (done && !completed) {
        throw new ChatMessageStreamError("INCOMPLETE_STREAM", "The chat response ended unexpectedly. Please try again.");
      }
    }
    throwIfAborted();
    return completed;
  } catch (error) {
    throwIfAborted();
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
    if (reader) {
      try {
        await reader.cancel();
      } catch {
        // The network may already have closed the stream.
      } finally {
        reader.releaseLock();
      }
    }
  }
}

export async function getChatMessagesApi(
  request: ApiRequest,
  sessionId: string,
): Promise<ChatHistoryMessageResponse[]> {
  const messages: ChatHistoryMessageResponse[] = [];
  let after = 0;
  while (true) {
    const params = new URLSearchParams({ limit: "200", after: String(after) });
    const res = await request(`${getApiBase()}/chat/sessions/${sessionId}/messages?${params}`);
    const page = await readJsonOrThrow<ChatHistoryMessageResponse[]>(res);
    messages.push(...page);
    if (page.length < 200) return messages;
    after = page.at(-1)?.sequence_number ?? after;
  }
}
