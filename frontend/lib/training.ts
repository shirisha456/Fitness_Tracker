/** Shared types for the training-insights API. Mirrors the backend Pydantic schemas
 * in `backend/app/modules/training_insights/schemas.py`. */

export type Classification =
  | "progressing"
  | "stable"
  | "possible_plateau"
  | "declining"
  | "insufficient_data"
  | "not_applicable";

export type SuggestionKind =
  | "maintain"
  | "small_progression"
  | "review_exercise"
  | "insufficient_history"
  | "consult_professional";

export type ExerciseRef = {
  id: string;
  name: string;
  category: "strength" | "cardio" | "mobility" | "other";
};

export type DateRange = { from: string; to: string };

export type NextSessionSuggestion = {
  kind: SuggestionKind;
  message: string;
  rationale: string;
  current_load_kg: number | null;
  suggested_load_kg: number | null;
};

export type ExerciseInsight = {
  exercise: ExerciseRef;
  classification: Classification;
  metric_basis: "load" | "reps" | "none";
  sessions_analyzed: number;
  date_range: DateRange | null;
  current_weight_kg: number | null;
  previous_weight_kg: number | null;
  primary_change_percent: number | null;
  volume_change_percent: number | null;
  plateau_detected: boolean;
  evidence: string[];
  suggestion: NextSessionSuggestion;
  explanation: string;
  explanation_source: string;
};

export type SessionMetrics = {
  workout_id: string;
  performed_at: string;
  total_sets: number;
  total_reps: number;
  reps_per_set: number | null;
  top_weight_kg: number | null;
  volume_kg: number | null;
};

export type PersonalBests = {
  heaviest_weight_kg: number | null;
  heaviest_weight_on: string | null;
  best_session_volume_kg: number | null;
  best_session_volume_on: string | null;
  most_reps_at_heaviest_weight: number | null;
  most_reps_at_heaviest_weight_on: string | null;
};

export type ExerciseHistory = {
  exercise: ExerciseRef;
  sessions: SessionMetrics[];
  personal_bests: PersonalBests;
  date_range: DateRange | null;
};

export type TrainingOverview = {
  generated_on: string;
  date_range: DateRange;
  consistency: {
    workouts_last_7_days: number;
    workouts_previous_7_days: number;
    change: number;
    active_weeks: number;
    weeks_analyzed: number;
    average_workouts_per_week: number;
  };
  volume: {
    current_7_day_volume_kg: number;
    previous_7_day_volume_kg: number;
    change_percent: number | null;
  };
  exercises: ExerciseInsight[];
};

/** How each verdict is worded and styled. The backend owns the decision; this map
 * only owns its presentation. */
export const CLASSIFICATION_LABELS: Record<Classification, string> = {
  progressing: "Progressing",
  stable: "Stable",
  possible_plateau: "Possible plateau",
  declining: "Trending down",
  insufficient_data: "Not enough data",
  not_applicable: "Not tracked",
};

export function formatShortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
  });
}

export function formatSignedPercent(value: number | null): string | null {
  if (value == null) return null;
  return `${value > 0 ? "+" : ""}${value}%`;
}
