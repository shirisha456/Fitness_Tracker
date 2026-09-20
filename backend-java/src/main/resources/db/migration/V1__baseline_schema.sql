--
-- V1: baseline schema.
--
-- This is the schema the previous implementation owns at Alembic revision
-- 008_exercise_scope_indexes, captured with pg_dump from a database built by running
-- `alembic upgrade head` against an empty PostgreSQL 16 instance. It is not hand-written,
-- and the eight Alembic migrations are deliberately NOT replayed as Flyway migrations.
--
-- Two audiences:
--
--   * A fresh database (local dev, CI, every Testcontainers instance) is built by this
--     file, so tests run against a byte-identical replica of the production schema.
--
--   * The deployed database already has this schema. There, Flyway runs with
--     baseline-on-migrate=true and baseline-version=1, records V1 as already applied,
--     and executes nothing. No existing table is ever dropped or recreated.
--
-- The `alembic_version` table is intentionally absent here: a Flyway-built database has
-- no Alembic history. On the deployed database that table is left untouched as the
-- record of where the reference implementation stopped, and as the rollback anchor.
--
-- Schema changes made after the Java cutover belong in V2 onward.
--

CREATE TYPE public.exercise_category AS ENUM (
    'strength',
    'cardio',
    'mobility',
    'other'
);

CREATE TYPE public.goal_status AS ENUM (
    'active',
    'achieved',
    'abandoned'
);

CREATE TYPE public.profile_activity_level AS ENUM (
    'sedentary',
    'light',
    'moderate',
    'active',
    'very_active'
);

CREATE TYPE public.profile_sex AS ENUM (
    'male',
    'female',
    'unspecified'
);

CREATE TYPE public.user_role AS ENUM (
    'user',
    'admin'
);

CREATE TABLE public.body_measurements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    recorded_at date NOT NULL,
    weight_kg double precision,
    body_fat_pct double precision,
    waist_cm double precision,
    chest_cm double precision,
    hips_cm double precision,
    arm_cm double precision,
    notes character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.email_verification_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.exercises (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(255) NOT NULL,
    category public.exercise_category NOT NULL,
    muscle_group character varying(100),
    equipment character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by_user_id uuid
);

CREATE TABLE public.goals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    title character varying(255) NOT NULL,
    target_weight_kg double precision,
    target_date date,
    status public.goal_status DEFAULT 'active'::public.goal_status NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.meals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    logged_at date NOT NULL,
    calories integer NOT NULL,
    protein_g double precision,
    carbs_g double precision,
    fat_g double precision,
    notes character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.password_reset_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.profiles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    display_name character varying(100),
    date_of_birth date,
    sex public.profile_sex,
    height_cm double precision,
    fitness_goal character varying(255),
    activity_level public.profile_activity_level,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.refresh_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    user_agent character varying(512),
    ip_address character varying(45)
);

CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255),
    role public.user_role DEFAULT 'user'::public.user_role NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);

CREATE TABLE public.water_entries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    logged_at date NOT NULL,
    amount_ml integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.workout_exercises (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workout_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    order_index integer DEFAULT 0 NOT NULL,
    sets integer NOT NULL,
    reps integer NOT NULL,
    weight_kg double precision,
    notes character varying(500)
);

CREATE TABLE public.workouts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    performed_at date NOT NULL,
    notes character varying(2000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.body_measurements
    ADD CONSTRAINT body_measurements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.body_measurements
    ADD CONSTRAINT body_measurements_user_id_recorded_at_key UNIQUE (user_id, recorded_at);

ALTER TABLE ONLY public.email_verification_tokens
    ADD CONSTRAINT email_verification_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.email_verification_tokens
    ADD CONSTRAINT email_verification_tokens_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY public.exercises
    ADD CONSTRAINT exercises_name_key UNIQUE (name);

ALTER TABLE ONLY public.exercises
    ADD CONSTRAINT exercises_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.goals
    ADD CONSTRAINT goals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.meals
    ADD CONSTRAINT meals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_user_id_key UNIQUE (user_id);

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.water_entries
    ADD CONSTRAINT water_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workout_exercises
    ADD CONSTRAINT workout_exercises_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workouts
    ADD CONSTRAINT workouts_pkey PRIMARY KEY (id);

CREATE INDEX ix_body_measurements_recorded_at ON public.body_measurements USING btree (recorded_at);

CREATE INDEX ix_body_measurements_user_id ON public.body_measurements USING btree (user_id);

CREATE INDEX ix_email_verification_tokens_user_id ON public.email_verification_tokens USING btree (user_id);

CREATE INDEX ix_email_verify_expires ON public.email_verification_tokens USING btree (expires_at) WHERE (used_at IS NULL);

CREATE INDEX ix_exercises_created_by_user_id ON public.exercises USING btree (created_by_user_id);

CREATE INDEX ix_exercises_name ON public.exercises USING btree (name);

CREATE INDEX ix_goals_user_id ON public.goals USING btree (user_id);

CREATE INDEX ix_meals_logged_at ON public.meals USING btree (logged_at);

CREATE INDEX ix_meals_user_logged ON public.meals USING btree (user_id, logged_at);

CREATE INDEX ix_password_reset_expires ON public.password_reset_tokens USING btree (expires_at) WHERE (used_at IS NULL);

CREATE INDEX ix_password_reset_tokens_user_id ON public.password_reset_tokens USING btree (user_id);

CREATE INDEX ix_refresh_tokens_expires ON public.refresh_tokens USING btree (expires_at) WHERE (revoked_at IS NULL);

CREATE INDEX ix_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);

CREATE INDEX ix_users_email ON public.users USING btree (email);

CREATE INDEX ix_water_entries_logged_at ON public.water_entries USING btree (logged_at);

CREATE INDEX ix_water_entries_user_logged ON public.water_entries USING btree (user_id, logged_at);

CREATE INDEX ix_workout_exercises_workout_id ON public.workout_exercises USING btree (workout_id);

CREATE INDEX ix_workouts_performed_at ON public.workouts USING btree (performed_at);

CREATE INDEX ix_workouts_user_performed ON public.workouts USING btree (user_id, performed_at);

ALTER TABLE ONLY public.body_measurements
    ADD CONSTRAINT body_measurements_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.email_verification_tokens
    ADD CONSTRAINT email_verification_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.exercises
    ADD CONSTRAINT fk_exercises_created_by_user_id FOREIGN KEY (created_by_user_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.goals
    ADD CONSTRAINT goals_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.meals
    ADD CONSTRAINT meals_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.water_entries
    ADD CONSTRAINT water_entries_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workout_exercises
    ADD CONSTRAINT workout_exercises_exercise_id_fkey FOREIGN KEY (exercise_id) REFERENCES public.exercises(id);

ALTER TABLE ONLY public.workout_exercises
    ADD CONSTRAINT workout_exercises_workout_id_fkey FOREIGN KEY (workout_id) REFERENCES public.workouts(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workouts
    ADD CONSTRAINT workouts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Seeded exercise library (39 rows), inserted by Alembic migrations 003 and 007.
-- The library is shared and not user-owned; created_by_user_id IS NULL marks a seeded
-- entry. Ids are generated per database, so nothing may depend on their values.
--

INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('8e3717a9-6695-4ff8-9180-fd3ed48b21ce', 'Squat', 'strength', 'legs', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('fd749068-a8e3-4cca-a51b-f84246ad9bf1', 'Bench Press', 'strength', 'chest', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('977a1c1f-8555-400f-bbf3-69edc2813075', 'Deadlift', 'strength', 'back', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('95f0049e-5c14-44e1-b582-0891c3e5e334', 'Overhead Press', 'strength', 'shoulders', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('9340f36c-de99-4231-93be-121d55b1a83d', 'Barbell Row', 'strength', 'back', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('7ed43449-244f-4094-924d-f6621c8619e8', 'Pull-up', 'strength', 'back', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('d6c8120d-f0fa-4856-b8c5-fadc35179f2e', 'Push-up', 'strength', 'chest', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('eb842b73-23fe-4717-8a93-5dd43beef101', 'Running', 'cardio', 'full_body', NULL, '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('d860d893-3f86-44f4-abc2-7d4739062c70', 'Cycling', 'cardio', 'legs', 'bike', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('82df6ad5-5b6d-4b91-a584-d0fdfc0e6ebe', 'Plank', 'mobility', 'core', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('d7a404b4-0712-4e98-99f3-c4aabb2b55cc', 'Incline Bench Press', 'strength', 'chest', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('7422757f-0d6e-474a-87b1-181261258af0', 'Dumbbell Bench Press', 'strength', 'chest', 'dumbbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('c919501a-0138-4f99-b421-b00a95635de2', 'Dumbbell Shoulder Press', 'strength', 'shoulders', 'dumbbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('ef23bc27-e82c-4d85-8598-b1be8db3ed20', 'Lateral Raise', 'strength', 'shoulders', 'dumbbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('3afba045-5af3-45b9-886c-2f8671468b01', 'Bicep Curl', 'strength', 'arms', 'dumbbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('6e2b21f6-21e2-4542-9861-465cbb16ab39', 'Tricep Pushdown', 'strength', 'arms', 'cable', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('10a31b99-ec15-4996-aeba-895701f07676', 'Lat Pulldown', 'strength', 'back', 'cable', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('1ab77732-ca38-4937-84f5-5d10ac2c0fdd', 'Seated Cable Row', 'strength', 'back', 'cable', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('ced32ba2-2cac-4996-8f80-a3c2704bc1cf', 'Leg Press', 'strength', 'legs', 'machine', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('64185714-fbc6-40f3-bb22-8f4fc407497b', 'Leg Curl', 'strength', 'legs', 'machine', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('236976f6-a799-4c4a-adef-6de8ede44645', 'Leg Extension', 'strength', 'legs', 'machine', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('ff605085-dcd9-4b3f-aab8-94543e9ffa7f', 'Lunge', 'strength', 'legs', 'dumbbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('e4bd5105-5854-489f-a011-75ee1f856c19', 'Hip Thrust', 'strength', 'legs', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('17d85b12-7d39-49d8-9e4e-b81d3da5a333', 'Romanian Deadlift', 'strength', 'back', 'barbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('33e570fb-65f0-4c98-8854-501c55824f1e', 'Dip', 'strength', 'chest', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('bbb433a3-4c23-411a-8a4c-f3ba8f816397', 'Chin-up', 'strength', 'back', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('57928c92-8f24-4c8b-a191-50d1870ad45e', 'Sit-up', 'strength', 'core', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('6ec91650-7441-4f5c-a4eb-1be2bd7e9c6f', 'Crunch', 'strength', 'core', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('230f3f02-d05f-4caf-a1b8-f34ead914ba1', 'Russian Twist', 'strength', 'core', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('a422c772-654c-4cbb-a110-546bbf5b7b97', 'Farmer''s Carry', 'strength', 'full_body', 'dumbbell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('c0980637-cb95-467b-8706-9653de7e53c1', 'Kettlebell Swing', 'strength', 'full_body', 'kettlebell', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('6acd3be1-97e8-465c-b6e2-09307b19b898', 'Rowing Machine', 'cardio', 'full_body', 'machine', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('5194e20a-6e1e-4cf6-ac23-07733465652d', 'Elliptical', 'cardio', 'full_body', 'machine', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('b990cfaf-3c6f-4eeb-8156-fc4ea6f35d63', 'Jump Rope', 'cardio', 'full_body', 'bodyweight', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('fb72835f-5f11-43e6-8179-e51a8522ef1e', 'Stair Climber', 'cardio', 'legs', 'machine', '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('ecced5cf-29c2-4f41-833d-d29421e45021', 'Swimming', 'cardio', 'full_body', NULL, '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('c0ad2b7c-9dd1-4da9-b564-9293fb1dc45d', 'Yoga', 'mobility', 'full_body', NULL, '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('73e930fa-12e8-4458-81f8-d141a940ccd2', 'Stretching', 'mobility', 'full_body', NULL, '2026-09-18 09:47:55.743575-07', NULL);
INSERT INTO public.exercises (id, name, category, muscle_group, equipment, created_at, created_by_user_id) VALUES ('e56aadf7-86e8-4d98-96e0-b695412625df', 'Foam Rolling', 'mobility', 'full_body', 'foam_roller', '2026-09-18 09:47:55.743575-07', NULL);
