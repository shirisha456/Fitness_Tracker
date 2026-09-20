const BACKEND_INTERNAL_URL =
  process.env.BACKEND_INTERNAL_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost/api";

export type ApiErrorEnvelope = {
  error: {
    code: string;
    message: string;
    details: unknown[];
    correlation_id: string;
  };
};

export class BackendError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body?: ApiErrorEnvelope,
  ) {
    super(message);
    this.name = "BackendError";
  }
}

/** Server-only: calls the backend directly (Docker network / configured origin), never through the browser. */
export async function backendFetch(path: string, init?: RequestInit): Promise<Response> {
  const base = BACKEND_INTERNAL_URL.replace(/\/$/, "");
  const url = `${base}${path.startsWith("/") ? path : `/${path}`}`;

  return fetch(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
    cache: "no-store",
  });
}

export async function backendJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await backendFetch(path, init);
  const body = await response.json().catch(() => undefined);

  if (!response.ok) {
    const errBody = body as ApiErrorEnvelope | undefined;
    throw new BackendError(
      errBody?.error?.message ?? `Request failed with status ${response.status}`,
      response.status,
      errBody,
    );
  }

  return body as T;
}
