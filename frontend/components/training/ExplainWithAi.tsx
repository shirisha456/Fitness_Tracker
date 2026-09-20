"use client";

import { Sparkles } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import type { ExerciseInsight } from "@/lib/training";

/** Asks the coach to reword the insight. The numbers on the page are already final —
 * this only ever changes the prose, and a failed call leaves it untouched. */
export function ExplainWithAi({
  exerciseId,
  initialExplanation,
}: {
  exerciseId: string;
  initialExplanation: string;
}) {
  const [explanation, setExplanation] = useState(initialExplanation);
  const [source, setSource] = useState<"deterministic" | "ai">("deterministic");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function explain() {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/training/exercises/${exerciseId}/insights?explain=true`,
        { method: "GET" },
      );
      if (!response.ok) throw new Error("Could not reach the coach right now.");
      const insight = ((await response.json()) as { data: ExerciseInsight }).data;
      setExplanation(insight.explanation);
      setSource(insight.explanation_source === "ai" ? "ai" : "deterministic");
      if (insight.explanation_source !== "ai") {
        setError("The AI coach is unavailable, so this is the standard explanation.");
      }
    } catch {
      setError("The AI coach is unavailable, so this is the standard explanation.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-sm text-muted-foreground">{explanation}</p>
      {error && <p className="text-xs text-muted-foreground">{error}</p>}
      {source === "deterministic" && (
        <Button
          variant="outline"
          size="sm"
          className="w-fit gap-2"
          onClick={explain}
          disabled={loading}
        >
          <Sparkles className="h-4 w-4" />
          {loading ? "Asking the coach…" : "Explain with AI"}
        </Button>
      )}
    </div>
  );
}
