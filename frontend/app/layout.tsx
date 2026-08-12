import type { Metadata } from "next";
import { Inter } from "next/font/google";
import { cookies } from "next/headers";

import "./globals.css";
import { AuthProvider } from "@/lib/auth/AuthContext";
import { backendFetch } from "@/lib/auth/backend";
import { ACCESS_COOKIE } from "@/lib/auth/cookies";
import type { AuthUser } from "@/lib/auth/types";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Fitness Tracker",
  description: "AI-Powered Fitness Platform",
};

async function getInitialUser(): Promise<AuthUser | null> {
  const cookieStore = await cookies();
  const accessToken = cookieStore.get(ACCESS_COOKIE)?.value;
  if (!accessToken) return null;

  const response = await backendFetch("/v1/auth/me", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) return null;

  const payload = await response.json();
  return payload.data as AuthUser;
}

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const initialUser = await getInitialUser();

  return (
    <html lang="en">
      <body className={inter.className}>
        <AuthProvider initialUser={initialUser}>{children}</AuthProvider>
      </body>
    </html>
  );
}
