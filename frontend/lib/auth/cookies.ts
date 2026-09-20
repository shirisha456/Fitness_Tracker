import type { NextResponse } from "next/server";

export const ACCESS_COOKIE = "ff_access";
export const REFRESH_COOKIE = "ff_refresh";

const isProd = process.env.NODE_ENV === "production";

// Ceiling for how long the browser retains the cookie; actual validity is always
// enforced by the backend regardless of this value, so drift from backend config is harmless.
const REFRESH_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

export type TokenPair = {
  access_token: string;
  refresh_token: string;
  expires_in: number;
};

export function setAuthCookies(response: NextResponse, tokens: TokenPair): void {
  response.cookies.set(ACCESS_COOKIE, tokens.access_token, {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    maxAge: tokens.expires_in,
  });
  response.cookies.set(REFRESH_COOKIE, tokens.refresh_token, {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    // Scoped to /api (not just /api/auth) so any BFF route handler — workouts,
    // nutrition, etc. — can trigger a silent refresh-and-retry on a 401, not just
    // the auth endpoints themselves.
    path: "/api",
    maxAge: REFRESH_COOKIE_MAX_AGE_SECONDS,
  });
}

export function clearAuthCookies(response: NextResponse): void {
  response.cookies.set(ACCESS_COOKIE, "", {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
  response.cookies.set(REFRESH_COOKIE, "", {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/api",
    maxAge: 0,
  });
}
