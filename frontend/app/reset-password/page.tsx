"use client";

import { FormEvent, Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";

import { PasswordInput } from "@/components/ui/password-input";
import { ApiClientError, apiClient } from "@/lib/api-client";

function ResetPasswordContent() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [status, setStatus] = useState<"idle" | "loading" | "success" | "error">("idle");
  const [message, setMessage] = useState("");

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (!token) {
      setStatus("error");
      setMessage("Missing reset token.");
      return;
    }
    setStatus("loading");
    try {
      const result = await apiClient.resetPassword({
        token,
        password,
        password_confirm: passwordConfirm,
      });
      setStatus("success");
      setMessage(result.message);
    } catch (err) {
      setStatus("error");
      setMessage(err instanceof ApiClientError ? err.message : "Reset failed.");
    }
  }

  if (status === "success") {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center px-6">
        <div className="max-w-md text-center">
          <h1 className="text-3xl font-bold">Password reset</h1>
          <p className="mt-4 text-muted-foreground">{message}</p>
          <Link href="/login" className="mt-6 inline-block text-sm underline">
            Go to login
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-6">
      <form onSubmit={onSubmit} className="w-full max-w-md space-y-4">
        <h1 className="text-center text-3xl font-bold">Reset password</h1>
        <p className="text-center text-sm text-muted-foreground">
          Choose a new password for your Fitness Tracker account.
        </p>
        <PasswordInput
          required
          placeholder="New password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <PasswordInput
          required
          placeholder="Confirm password"
          value={passwordConfirm}
          onChange={(e) => setPasswordConfirm(e.target.value)}
        />
        {status === "error" && (
          <p className="text-sm text-red-600">{message}</p>
        )}
        <button
          type="submit"
          disabled={status === "loading"}
          className="w-full rounded bg-foreground px-3 py-2 text-background disabled:opacity-60"
        >
          {status === "loading" ? "Saving…" : "Reset password"}
        </button>
      </form>
    </main>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense
      fallback={
        <main className="flex min-h-screen items-center justify-center">
          <p>Loading…</p>
        </main>
      }
    >
      <ResetPasswordContent />
    </Suspense>
  );
}
