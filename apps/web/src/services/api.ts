const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(API_BASE_URL + path, { ...init, headers });
  if (!response.ok) {
    let detail = response.statusText || "Request failed";
    try {
      const body: unknown = await response.json();
      if (body && typeof body === "object") {
        const record = body as Record<string, unknown>;
        detail = String(record.message ?? record.detail ?? detail);
      }
    } catch {
      // Keep the HTTP status text when the response is not JSON.
    }
    throw new Error(detail + " (HTTP " + response.status + ")");
  }

  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}