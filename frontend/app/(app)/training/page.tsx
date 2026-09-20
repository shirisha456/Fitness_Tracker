import { Activity, CalendarCheck, Dumbbell, Weight } from "lucide-react";
import Link from "next/link";

import { ClassificationBadge } from "@/components/training/ClassificationBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { serverReadWithAccessToken } from "@/lib/auth/authedFetch";
import { formatSignedPercent, type TrainingOverview } from "@/lib/training";

function MetricCard({
  icon: Icon,
  title,
  headline,
  description,
}: {
  icon: typeof Activity;
  title: string;
  headline: string;
  description: string;
}) {
  return (
    <Card className="h-full">
      <CardHeader>
        <div className="mb-2 flex h-9 w-9 items-center justify-center rounded-md bg-muted">
          <Icon className="h-5 w-5 text-muted-foreground" />
        </div>
        <CardTitle className="text-base">{title}</CardTitle>
        <p className="text-2xl font-semibold tracking-tight">{headline}</p>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
    </Card>
  );
}

export default async function TrainingPage() {
  const response = await serverReadWithAccessToken("/v1/training/overview");
  const overview: TrainingOverview | null = response.ok
    ? (await response.json()).data
    : null;

  if (!overview) {
    return (
      <div className="mx-auto max-w-3xl">
        <h1 className="mb-4 text-2xl font-bold tracking-tight">Training insights</h1>
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">
            Training insights are unavailable right now.
          </CardContent>
        </Card>
      </div>
    );
  }

  const { consistency, volume, exercises } = overview;
  const volumeChange = formatSignedPercent(volume.change_percent);

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Training insights</h1>
        <p className="text-sm text-muted-foreground">
          Computed from your logged workouts — every verdict shows the evidence behind it.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <MetricCard
          icon={CalendarCheck}
          title="Weekly consistency"
          headline={`${consistency.workouts_last_7_days} this week`}
          description={
            `${consistency.workouts_previous_7_days} last week · ` +
            `${consistency.average_workouts_per_week}/week over ${consistency.weeks_analyzed} weeks`
          }
        />
        <MetricCard
          icon={Weight}
          title="Training volume"
          headline={volumeChange ?? "—"}
          description={
            volumeChange
              ? `${Math.round(volume.current_7_day_volume_kg).toLocaleString()} kg vs ` +
                `${Math.round(volume.previous_7_day_volume_kg).toLocaleString()} kg the previous 7 days`
              : "Not enough weighted training in the previous 7 days to compare."
          }
        />
      </div>

      <div>
        <h2 className="mb-3 text-lg font-semibold">Exercise progress</h2>
        {exercises.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
              <p className="text-muted-foreground">
                Log a few workouts and your training trends will appear here.
              </p>
              <Button asChild variant="outline">
                <Link href="/workouts/new">Log a workout</Link>
              </Button>
            </CardContent>
          </Card>
        ) : (
          <div className="flex flex-col gap-3">
            {exercises.map((insight) => (
              <Link
                key={insight.exercise.id}
                href={`/training/exercises/${insight.exercise.id}`}
              >
                <Card className="transition-colors hover:bg-accent">
                  <CardHeader className="flex flex-row items-center justify-between space-y-0">
                    <div className="flex items-center gap-3">
                      <Dumbbell className="h-5 w-5 shrink-0 text-muted-foreground" />
                      <div>
                        <CardTitle className="text-base">{insight.exercise.name}</CardTitle>
                        <p className="text-sm text-muted-foreground">
                          {insight.sessions_analyzed > 0
                            ? `${insight.sessions_analyzed} session${
                                insight.sessions_analyzed === 1 ? "" : "s"
                              } analysed`
                            : "No comparable sessions yet"}
                        </p>
                      </div>
                    </div>
                    <ClassificationBadge classification={insight.classification} />
                  </CardHeader>
                </Card>
              </Link>
            ))}
          </div>
        )}
      </div>

      <p className="text-xs text-muted-foreground">
        These are training-behaviour metrics computed from what you logged. They are not
        medical or health assessments. For pain, injury or any medical concern, speak to a
        qualified professional.
      </p>
    </div>
  );
}
