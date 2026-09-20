import { ArrowLeft, Trophy } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ClassificationBadge } from "@/components/training/ClassificationBadge";
import { EvidenceList } from "@/components/training/EvidenceList";
import { ExerciseTrendChart } from "@/components/training/ExerciseTrendChart";
import { ExplainWithAi } from "@/components/training/ExplainWithAi";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { serverReadWithAccessToken } from "@/lib/auth/authedFetch";
import {
  formatShortDate,
  formatSignedPercent,
  type ExerciseHistory,
  type ExerciseInsight,
} from "@/lib/training";

function formatKg(value: number): string {
  return `${Number(value.toFixed(2))} kg`;
}

export default async function ExerciseInsightPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  // Both endpoints are deterministic and independent, so they can be fetched together.
  // `explain` is deliberately not requested here: the page renders from computed data
  // alone, and the AI rewording is opt-in via the button below.
  const [insightResponse, historyResponse] = await Promise.all([
    serverReadWithAccessToken(`/v1/training/exercises/${id}/insights`),
    serverReadWithAccessToken(`/v1/training/exercises/${id}/history`),
  ]);

  if (insightResponse.status === 404) {
    notFound();
  }

  const insight: ExerciseInsight = (await insightResponse.json()).data;
  const history: ExerciseHistory | null = historyResponse.ok
    ? (await historyResponse.json()).data
    : null;

  const sessions = history?.sessions ?? [];
  const bests = history?.personal_bests;
  const primaryChange = formatSignedPercent(insight.primary_change_percent);
  const volumeChange = formatSignedPercent(insight.volume_change_percent);

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Button asChild variant="ghost" size="sm" className="-ml-3 gap-2">
          <Link href="/training">
            <ArrowLeft className="h-4 w-4" />
            Training insights
          </Link>
        </Button>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight">{insight.exercise.name}</h1>
          <ClassificationBadge classification={insight.classification} />
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">What the data shows</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <ExplainWithAi exerciseId={insight.exercise.id} initialExplanation={insight.explanation} />

          <EvidenceList evidence={insight.evidence} />

          {(primaryChange || volumeChange) && (
            <dl className="grid grid-cols-2 gap-4 border-t pt-4 text-sm sm:grid-cols-4">
              {insight.current_weight_kg != null && (
                <div>
                  <dt className="text-muted-foreground">Latest load</dt>
                  <dd className="font-medium">{formatKg(insight.current_weight_kg)}</dd>
                </div>
              )}
              {insight.previous_weight_kg != null && (
                <div>
                  <dt className="text-muted-foreground">Previous load</dt>
                  <dd className="font-medium">{formatKg(insight.previous_weight_kg)}</dd>
                </div>
              )}
              {primaryChange && (
                <div>
                  <dt className="text-muted-foreground">
                    {insight.metric_basis === "load" ? "Load change" : "Rep change"}
                  </dt>
                  <dd className="font-medium">{primaryChange}</dd>
                </div>
              )}
              {volumeChange && (
                <div>
                  <dt className="text-muted-foreground">Volume change</dt>
                  <dd className="font-medium">{volumeChange}</dd>
                </div>
              )}
            </dl>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Suggested next session</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm">{insight.suggestion.message}</p>
          <p className="text-sm text-muted-foreground">{insight.suggestion.rationale}</p>
          {insight.suggestion.suggested_load_kg != null && (
            <p className="text-sm text-muted-foreground">
              Nothing is changed automatically — apply it yourself when you log your next
              workout.
            </p>
          )}
        </CardContent>
      </Card>

      {sessions.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">History</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-6">
            <ExerciseTrendChart sessions={sessions} basis={insight.metric_basis} />

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="pb-2 pr-4 font-medium">Date</th>
                    <th className="pb-2 pr-4 font-medium">Sets</th>
                    <th className="pb-2 pr-4 font-medium">Reps</th>
                    <th className="pb-2 pr-4 font-medium">Weight</th>
                    <th className="pb-2 font-medium">Volume</th>
                  </tr>
                </thead>
                <tbody>
                  {[...sessions].reverse().map((session) => (
                    <tr key={session.workout_id} className="border-b last:border-0">
                      <td className="py-2 pr-4">
                        <Link
                          href={`/workouts/${session.workout_id}`}
                          className="hover:underline"
                        >
                          {formatShortDate(session.performed_at)}
                        </Link>
                      </td>
                      <td className="py-2 pr-4">{session.total_sets}</td>
                      <td className="py-2 pr-4">
                        {session.reps_per_set ?? `${session.total_reps} total`}
                      </td>
                      <td className="py-2 pr-4">
                        {session.top_weight_kg != null ? formatKg(session.top_weight_kg) : "—"}
                      </td>
                      <td className="py-2">
                        {session.volume_kg != null
                          ? `${Math.round(session.volume_kg).toLocaleString()} kg`
                          : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      )}

      {bests?.heaviest_weight_kg != null && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Trophy className="h-4 w-4" />
              Personal bests
            </CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-4 sm:grid-cols-3">
              <div>
                <dt className="text-sm text-muted-foreground">Heaviest load</dt>
                <dd className="font-medium">{formatKg(bests.heaviest_weight_kg)}</dd>
                {bests.heaviest_weight_on && (
                  <dd className="text-xs text-muted-foreground">
                    {formatShortDate(bests.heaviest_weight_on)}
                  </dd>
                )}
              </div>
              {bests.best_session_volume_kg != null && (
                <div>
                  <dt className="text-sm text-muted-foreground">Best session volume</dt>
                  <dd className="font-medium">
                    {Math.round(bests.best_session_volume_kg).toLocaleString()} kg
                  </dd>
                  {bests.best_session_volume_on && (
                    <dd className="text-xs text-muted-foreground">
                      {formatShortDate(bests.best_session_volume_on)}
                    </dd>
                  )}
                </div>
              )}
              {bests.most_reps_at_heaviest_weight != null && (
                <div>
                  <dt className="text-sm text-muted-foreground">Most reps at that load</dt>
                  <dd className="font-medium">{bests.most_reps_at_heaviest_weight} reps</dd>
                  {bests.most_reps_at_heaviest_weight_on && (
                    <dd className="text-xs text-muted-foreground">
                      {formatShortDate(bests.most_reps_at_heaviest_weight_on)}
                    </dd>
                  )}
                </div>
              )}
            </dl>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
