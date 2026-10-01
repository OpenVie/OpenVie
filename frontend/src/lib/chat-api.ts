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

export async function submitChatMessageApi(
  request: ApiRequest,
  sessionId: string,
  content: string,
  signal?: AbortSignal,
): Promise<AssistantMessageResponse> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), CHAT_MESSAGE_TIMEOUT_MS);
  const abortFromCaller = () => controller.abort();

  if (signal?.aborted) {
    controller.abort();
  } else {
    signal?.addEventListener("abort", abortFromCaller, { once: true });
  }

  try {
    const res = await request(`${getApiBase()}/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      body: JSON.stringify({ content }),
      signal: controller.signal,
    });
    return readJsonOrThrow<AssistantMessageResponse>(res);
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ChatMessageTimeoutError();
    }
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abortFromCaller);
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
