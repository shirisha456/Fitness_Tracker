"""Deterministic training analytics over a user's logged workout history.

This module owns no tables. It reads `workouts` / `workout_exercises` and derives
trend classifications, personal bests, consistency and workload metrics using
explicit, documented rules — never an LLM. See `rules.py` for every threshold.
"""
