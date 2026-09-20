package com.fitnesstracker.traininginsights.domain;

import java.util.Set;

/**
 * Every threshold and vocabulary constant the analytics engine uses.
 *
 * <p>A direct transcription of {@code app/modules/training_insights/rules.py}. Nothing in
 * {@link TrainingAnalytics} hard-codes a number; a reviewer asking "why did the system say
 * this?" should be able to answer from this file plus the evidence strings.
 *
 * <h2>Classification rules</h2>
 *
 * Sessions are ordered oldest → newest and the most recent {@link #TREND_WINDOW_SESSIONS}
 * form the analysis window. Within it, evaluated in this order:
 *
 * <ol>
 *   <li>{@code not_applicable} — not a strength exercise. Sets and reps cannot express
 *       distance or duration, so no honest trend exists for cardio or mobility work.
 *   <li>{@code insufficient_data} — fewer than {@link #MIN_SESSIONS_FOR_TREND} sessions.
 *   <li>{@code possible_plateau} — the last {@link #PLATEAU_MIN_SESSIONS} sessions span at
 *       least {@link #PLATEAU_MIN_SPAN_DAYS} days and both the primary metric and session
 *       volume stay inside a ±{@link #MIN_MEANINGFUL_CHANGE_PCT} band. Checked
 *       <em>before</em> progression so an older jump followed by four flat sessions is
 *       still reported as a plateau, and the day-span requirement is what stops four
 *       sessions in one week being called one.
 *   <li>{@code progressing} — primary metric up by more than the threshold, and session
 *       volume not down by more than it. (Heavier but meaningfully less total work is a
 *       trade-off, not progress; that falls through to {@code stable}.)
 *   <li>{@code declining} — primary metric down by more than the threshold.
 *   <li>{@code stable} — everything else.
 * </ol>
 */
public final class AnalysisRules {

    private AnalysisRules() {}

    // --- trend classification ------------------------------------------------

    /** How many recent sessions are considered. Older ones show in history, not the verdict. */
    public static final int TREND_WINDOW_SESSIONS = 5;

    /** Below this the engine refuses to classify rather than guess from two points. */
    public static final int MIN_SESSIONS_FOR_TREND = 3;

    /** A plateau claim needs more sessions and more calendar time than a trend claim. */
    public static final int PLATEAU_MIN_SESSIONS = 4;
    public static final int PLATEAU_MIN_SPAN_DAYS = 14;

    /**
     * Percentage change below which a difference is noise. 2% is roughly one 1 kg jump on
     * a 50 kg lift — smaller than the smallest plate change most users can make.
     */
    public static final double MIN_MEANINGFUL_CHANGE_PCT = 2.0;

    /** Two logged weights are "the same load" when they differ by less than this. */
    public static final double WEIGHT_COMPARISON_EPSILON_KG = 1e-6;

    // --- workload and consistency --------------------------------------------

    public static final int CONSISTENCY_WINDOW_DAYS = 7;
    public static final int CONSISTENCY_TREND_WEEKS = 8;

    // --- query bounds --------------------------------------------------------

    public static final int HISTORY_LOOKBACK_DAYS = 365;
    public static final int HISTORY_MAX_SESSIONS = 50;
    public static final int OVERVIEW_LOOKBACK_DAYS = 180;
    public static final int OVERVIEW_MAX_EXERCISES = 8;

    // --- progression suggestions ---------------------------------------------

    /** Deliberately conservative: a small percentage, hard-capped in kilograms. */
    public static final double PROGRESSION_STEP_PCT = 2.5;
    public static final double MIN_LOAD_INCREASE_KG = 0.5;
    public static final double MAX_LOAD_INCREASE_KG = 2.5;

    /** Suggested loads are rounded to this granularity so the number is loadable. */
    public static final double LOAD_ROUNDING_KG = 0.5;

    // --- medical boundary ----------------------------------------------------

    /**
     * A deliberately blunt keyword screen. It is a conservative trigger for <em>not</em>
     * giving training advice, never an assessment of what is wrong — this application is
     * not a medical tool.
     */
    public static final Set<String> MEDICAL_REFERRAL_KEYWORDS = Set.of(
            "pain", "painful", "hurt", "hurts", "hurting",
            "injury", "injured", "strain", "strained", "sprain", "sprained",
            "tear", "torn", "tendon", "tendonitis", "tendinitis",
            "physio", "physiotherapist", "doctor", "surgery", "fracture");

    public static final String MEDICAL_REFERRAL_MESSAGE =
            "Your notes for this exercise mention pain or injury, so no training "
                    + "suggestion is offered here. Please speak to a doctor, physiotherapist or "
                    + "another qualified professional before changing your training.";

    /**
     * True when any note appears to mention pain or injury.
     *
     * <p>Case-insensitive, word-boundary matched so "training" does not match "strain".
     * Callers pass only the resulting boolean onward — the note text never leaves the
     * database layer, so it cannot reach a log, an error message or an AI prompt.
     */
    public static boolean mentionsMedicalConcern(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String word : text.split("\\s+")) {
                String cleaned = word.replaceAll("^[.,!?;:()\\[\\]\"']+|[.,!?;:()\\[\\]\"']+$", "")
                        .toLowerCase(java.util.Locale.ROOT);
                if (MEDICAL_REFERRAL_KEYWORDS.contains(cleaned)) {
                    return true;
                }
            }
        }
        return false;
    }
}
