import { cookies } from "next/headers";
import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/auth/backend";
import { ACCESS_COOKIE, clearAuthCookies, setAuthCookies, type TokenPair } from "@/lib/auth/cookies";
import { performRefresh } from "@/lib/auth/refresh";

export type AuthedFetchResult = {
  response: Response;
  refreshedTokens?: TokenPair;
  unauthenticated?: boolean;
};

/**
 * Server-only: calls the backend with the caller's access token, retrying once via a silent
 * refresh on a 401. The calling Route Handler is responsible for setting/clearing cookies
 * on its own response based on `refreshedTokens` / `unauthenticated`.
 */
export async function authedBackendFetch(
  path: string,
  init?: RequestInit,
): Promise<AuthedFetchResult> {
  const cookieStore = await cookies();
  const accessToken = cookieStore.get(ACCESS_COOKIE)?.value;

  if (accessToken) {
    const response = await backendFetch(path, {
      ...init,
      headers: { ...init?.headers, Authorization: `Bearer ${accessToken}` },
    });
    if (response.status !== 401) {
      return { response };
    }
  }

  const refreshResult = await performRefresh();
  if (!refreshResult.ok) {
    return {
      response: new Response(
        JSON.stringify({
          error: {
            code: "UNAUTHORIZED",
            message: "Not authenticated",
            details: [],
            correlation_id: "",
          },
        }),
        { status: 401, headers: { "Content-Type": "application/json" } },
      ),
      unauthenticated: true,
    };
  }

  const response = await backendFetch(path, {
    ...init,
    headers: { ...init?.headers, Authorization: `Bearer ${refreshResult.tokens.access_token}` },
  });
  return { response, refreshedTokens: refreshResult.tokens };
}

/**
 * Server Component reads only: uses the current access token with no refresh attempt.
 * Server Components cannot set cookies, so a rotated refresh token from a retry couldn't be
 * persisted — and since the backend revokes the old refresh token the moment it issues a new
 * one, a "successful but discarded" refresh here would silently break the next real refresh.
 * On an expired token this simply surfaces a 401 for the caller to handle; the access token's
 * 15-minute lifetime makes this rare, and the next Route-Handler-backed mutation refreshes it.
 */
export async function serverReadWithAccessToken(path: string): Promise<Response> {
  const cookieStore = await cookies();
  const accessToken = cookieStore.get(ACCESS_COOKIE)?.value;
  if (!accessToken) {
    return new Response(
      JSON.stringify({
        error: {
          code: "UNAUTHORIZED",
          message: "Not authenticated",
          details: [],
          correlation_id: "",
        },
      }),
      { status: 401, headers: { "Content-Type": "application/json" } },
    );
  }
  return backendFetch(path, { headers: { Authorization: `Bearer ${accessToken}` } });
}

/** Shapes an AuthedFetchResult into a NextResponse, applying any cookie rotation/clearing. */
export async function toNextResponse(result: AuthedFetchResult): Promise<NextResponse> {
  const { response, refreshedTokens, unauthenticated } = result;

  const nextResponse =
    response.status === 204
      ? new NextResponse(null, { status: 204 })
      : NextResponse.json(await response.json().catch(() => ({})), { status: response.status });

  if (refreshedTokens) setAuthCookies(nextResponse, refreshedTokens);
  if (unauthenticated) clearAuthCookies(nextResponse);
  return nextResponse;
}
