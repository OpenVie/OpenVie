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

import { localizeErrorMessage } from "./localize-error";

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
    if (fastApiMessage) return new Error(localizeErrorMessage(fastApiMessage));

    const springMessage = (body as SpringErrorBody).message;
    if (typeof springMessage === "string") return new Error(localizeErrorMessage(springMessage));
    if (Array.isArray(springMessage)) return new Error(localizeErrorMessage(springMessage.join(" ")));
  }
  return new ApiFallback(res.status);
}

export async function readJsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return (await res.json()) as T;
}
