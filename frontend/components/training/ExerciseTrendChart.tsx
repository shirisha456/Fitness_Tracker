"use client";

import { CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts";

import {
  type ChartConfig,
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { formatShortDate, type SessionMetrics } from "@/lib/training";

const LOAD_CONFIG = {
  value: { label: "Top load (kg)", color: "hsl(var(--primary))" },
} satisfies ChartConfig;

const REPS_CONFIG = {
  value: { label: "Total reps", color: "hsl(var(--primary))" },
} satisfies ChartConfig;

/** Plots top load where the exercise is weighted, and total reps where it isn't —
 * the same metric the backend classified on, so the chart never tells a different
 * story from the verdict. */
export function ExerciseTrendChart({
  sessions,
  basis,
}: {
  sessions: SessionMetrics[];
  basis: "load" | "reps" | "none";
}) {
  const useLoad = basis === "load";
  const data = sessions
    .map((session) => ({
      performed_at: session.performed_at,
      value: useLoad ? session.top_weight_kg : session.total_reps,
    }))
    .filter((point): point is { performed_at: string; value: number } => point.value != null);

  if (data.length < 2) return null;

  return (
    <ChartContainer
      config={useLoad ? LOAD_CONFIG : REPS_CONFIG}
      className="aspect-auto h-56 w-full"
    >
      <LineChart data={data} margin={{ left: 12, right: 12, top: 8, bottom: 8 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="performed_at"
          tickFormatter={formatShortDate}
          tickLine={false}
          axisLine={false}
          minTickGap={24}
        />
        <YAxis tickLine={false} axisLine={false} width={40} domain={["dataMin - 5", "dataMax + 5"]} />
        <ChartTooltip
          content={
            <ChartTooltipContent
              labelFormatter={(value) =>
                new Date(`${value}T00:00:00`).toLocaleDateString(undefined, {
                  weekday: "short",
                  month: "short",
                  day: "numeric",
                })
              }
            />
          }
        />
        <Line
          dataKey="value"
          type="monotone"
          stroke="var(--color-value)"
          strokeWidth={2}
          dot={{ r: 4 }}
          activeDot={{ r: 5 }}
        />
      </LineChart>
    </ChartContainer>
  );
}
