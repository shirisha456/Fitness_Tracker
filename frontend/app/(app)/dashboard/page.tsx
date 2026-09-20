import { Activity, Dumbbell, Flame, TrendingUp } from "lucide-react";
import Link from "next/link";

import { EmptyStateCard } from "@/components/dashboard/EmptyStateCard";
import { QuickActions } from "@/components/dashboard/QuickActions";
import { WelcomeHeader } from "@/components/dashboard/WelcomeHeader";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { serverReadWithAccessToken } from "@/lib/auth/authedFetch";
import { CLASSIFICATION_LABELS, type TrainingOverview } from "@/lib/training";
import { todayIso } from "@/lib/date";

type WorkoutSummary = { id: string; name: string; exercise_count: number };
type DailyNutritionSummary = { total_calories: number };
type Measurement = { recorded_at: string; weight_kg: number | null };

function CardIcon({ icon: Icon }: { icon: typeof Dumbbell }) {
  return (
    <div className="mb-2 flex h-9 w-9 items-center justify-center rounded-md bg-muted">
      <Icon className="h-5 w-5 text-muted-foreground" />
    </div>
  );
}

export default async function DashboardPage() {
  const today = todayIso();

  const [
    meResponse,
    profileResponse,
    workoutsResponse,
    summaryResponse,
    measurementsResponse,
    trainingResponse,
  ] = await Promise.all([
    serverReadWithAccessToken("/v1/auth/me"),
    serverReadWithAccessToken("/v1/profile"),
    serverReadWithAccessToken(`/v1/workouts?date_from=${today}&date_to=${today}`),
    serverReadWithAccessToken(`/v1/nutrition/summary?date=${today}`),
    serverReadWithAccessToken(`/v1/measurements?date_to=${today}`),
    serverReadWithAccessToken("/v1/training/overview"),
  ]);

  const email = meResponse.ok ? (await meResponse.json()).data.email : "";
  const displayName = profileResponse.ok
    ? ((await profileResponse.json()).data.display_name as string | null)
    : null;
  const greetingName = displayName || email;
  const todaysWorkouts: WorkoutSummary[] = workoutsResponse.ok
    ? (await workoutsResponse.json()).data
    : [];
  const nutritionSummary: DailyNutritionSummary = summaryResponse.ok
    ? (await summaryResponse.json()).data
    : { total_calories: 0 };
  const measurements: Measurement[] = measurementsResponse.ok
    ? (await measurementsResponse.json()).data
    : [];
  const latestMeasurement = measurements.find((m) => m.weight_kg != null);
  const training: TrainingOverview | null = trainingResponse.ok
    ? (await trainingResponse.json()).data
    : null;
  // Lead with the exercise the engine has most to say about, falling back to the
  // most recently trained one.
  const headlineInsight =
    training?.exercises.find((insight) =>
      ["progressing", "possible_plateau", "declining"].includes(insight.classification),
    ) ?? training?.exercises[0];

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <WelcomeHeader name={greetingName} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {todaysWorkouts.length > 0 ? (
          <Link href={`/workouts/${todaysWorkouts[0].id}`}>
            <Card className="h-full transition-colors hover:bg-accent">
              <CardHeader>
                <CardIcon icon={Dumbbell} />
                <CardTitle className="text-base">Today&apos;s workout</CardTitle>
                <CardDescription>
                  {todaysWorkouts[0].name} · {todaysWorkouts[0].exercise_count} exercise
                  {todaysWorkouts[0].exercise_count === 1 ? "" : "s"}
                </CardDescription>
              </CardHeader>
            </Card>
          </Link>
        ) : (
          <EmptyStateCard
            icon={Dumbbell}
            title="Today's workout"
            description="No workout scheduled for today."
            ctaLabel="Plan a workout"
            ctaHref="/workouts/new"
          />
        )}

        {nutritionSummary.total_calories > 0 ? (
          <Link href="/nutrition">
            <Card className="h-full transition-colors hover:bg-accent">
              <CardHeader>
                <CardIcon icon={Flame} />
                <CardTitle className="text-base">Calories</CardTitle>
                <CardDescription>
                  {nutritionSummary.total_calories} kcal logged today
                </CardDescription>
              </CardHeader>
            </Card>
          </Link>
        ) : (
          <EmptyStateCard
            icon={Flame}
            title="Calories"
            description="No meals logged today."
            ctaLabel="Log a meal"
            ctaHref="/nutrition/meals/new"
          />
        )}

        {headlineInsight ? (
          <Link href={`/training/exercises/${headlineInsight.exercise.id}`}>
            <Card className="h-full transition-colors hover:bg-accent">
              <CardHeader>
                <CardIcon icon={Activity} />
                <CardTitle className="text-base">Training insights</CardTitle>
                <CardDescription>
                  {headlineInsight.exercise.name} ·{" "}
                  {CLASSIFICATION_LABELS[headlineInsight.classification]}
                </CardDescription>
              </CardHeader>
            </Card>
          </Link>
        ) : (
          <EmptyStateCard
            icon={Activity}
            title="Training insights"
            description="Log a few workouts to see progression and plateau analysis."
            ctaLabel="View training"
            ctaHref="/training"
          />
        )}

        {latestMeasurement ? (
          <Link href="/progress">
            <Card className="h-full transition-colors hover:bg-accent">
              <CardHeader>
                <CardIcon icon={TrendingUp} />
                <CardTitle className="text-base">Progress</CardTitle>
                <CardDescription>
                  {latestMeasurement.weight_kg}kg on{" "}
                  {new Date(`${latestMeasurement.recorded_at}T00:00:00`).toLocaleDateString(
                    undefined,
                    { month: "short", day: "numeric" },
                  )}
                </CardDescription>
              </CardHeader>
            </Card>
          </Link>
        ) : (
          <EmptyStateCard
            icon={TrendingUp}
            title="Progress"
            description="Start tracking to see your progress here."
            ctaLabel="Add a measurement"
            ctaHref="/progress/measurements/new"
          />
        )}
      </div>

      <QuickActions />
    </div>
  );
}
