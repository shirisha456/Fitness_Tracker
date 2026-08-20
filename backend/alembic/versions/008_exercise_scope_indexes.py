"""Add exercise ownership and composite (user_id, date) indexes.

Two unrelated-looking but both overdue changes:

1. `exercises.created_by_user_id` — NULL marks a curated library entry, non-NULL marks
   a custom exercise a user added via POST /exercises. Exercise names are free text and
   get concatenated into the AI system prompt, so without this column one user's name
   could reach every other user's prompt.

2. Composite indexes on (user_id, date). Every list query filters on user_id and a date
   range and sorts by that date, but only separate single-column indexes existed. The
   composite serves the filter and the sort together; a btree scans backwards, so no
   DESC index is needed. The standalone user_id indexes are subsumed and dropped.

Revision ID: 008_exercise_scope_indexes
Revises: 007_expand_exercise_seed
Create Date: 2026-08-19
"""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

# Keep revision ids under 32 chars: alembic_version.version_num is varchar(32).
revision: str = "008_exercise_scope_indexes"
down_revision: str | None = "007_expand_exercise_seed"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 1. Exercise ownership. Existing rows are all seeded library entries, so NULL is
    # the correct backfill and no data migration is needed.
    op.add_column(
        "exercises",
        sa.Column("created_by_user_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_foreign_key(
        "fk_exercises_created_by_user_id",
        "exercises",
        "users",
        ["created_by_user_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_index(
        "ix_exercises_created_by_user_id",
        "exercises",
        ["created_by_user_id"],
        unique=False,
    )

    # 2. Composite indexes, replacing the standalone user_id ones they subsume.
    op.create_index(
        "ix_workouts_user_performed", "workouts", ["user_id", "performed_at"], unique=False
    )
    op.drop_index("ix_workouts_user_id", table_name="workouts")

    op.create_index("ix_meals_user_logged", "meals", ["user_id", "logged_at"], unique=False)
    op.drop_index("ix_meals_user_id", table_name="meals")

    op.create_index(
        "ix_water_entries_user_logged",
        "water_entries",
        ["user_id", "logged_at"],
        unique=False,
    )
    op.drop_index("ix_water_entries_user_id", table_name="water_entries")


def downgrade() -> None:
    op.create_index("ix_water_entries_user_id", "water_entries", ["user_id"], unique=False)
    op.drop_index("ix_water_entries_user_logged", table_name="water_entries")

    op.create_index("ix_meals_user_id", "meals", ["user_id"], unique=False)
    op.drop_index("ix_meals_user_logged", table_name="meals")

    op.create_index("ix_workouts_user_id", "workouts", ["user_id"], unique=False)
    op.drop_index("ix_workouts_user_performed", table_name="workouts")

    op.drop_index("ix_exercises_created_by_user_id", table_name="exercises")
    op.drop_constraint("fk_exercises_created_by_user_id", "exercises", type_="foreignkey")
    op.drop_column("exercises", "created_by_user_id")
