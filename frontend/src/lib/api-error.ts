type SpringErrorBody = {
  message?: string | string[];
};

type FastApiErrorBody = {
  error?: {
    message?: string;
  };
};

class ApiFallback {
  constructor(readonly status: number) {}
}

async function parseJsonSafe(res: Response): Promise<unknown> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

export async function parseApiError(res: Response): Promise<Error | ApiFallback> {
  const body = await parseJsonSafe(res);
  if (body && typeof body === "object") {
    const fastApiMessage = (body as FastApiErrorBody).error?.message;
    if (fastApiMessage) return new Error(fastApiMessage);

    const springMessage = (body as SpringErrorBody).message;
    if (typeof springMessage === "string") return new Error(springMessage);
    if (Array.isArray(springMessage)) return new Error(springMessage.join(" "));
  }
  return new ApiFallback(res.status);
}

export async function readJsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as T;
}
