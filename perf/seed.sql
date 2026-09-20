-- Realistic dataset for the Phase 13 load tests.
--
-- Seeded with generate_series rather than through the API: 100 users each with a year of
-- history is ~10k workouts and ~30k meals, which would take hours over HTTP and would be
-- measuring the seeding, not the thing under test.
--
-- Every load-test user shares one password ('LoadTest123!') and therefore one Argon2 hash.
-- That is safe because the hash is computed with the SAME parameters the application uses
-- (m=65536, t=3, p=4), so login still pays the real verification cost — which matters,
-- because Argon2 verification is the single most expensive operation in the auth path and
-- a shortcut here would make the login numbers meaningless.
--
-- Idempotent: re-running replaces the load-test rows and touches nothing else.

BEGIN;

DELETE FROM users WHERE email LIKE 'loadtest-%@example.com';

INSERT INTO users (id, email, password_hash, role, email_verified, is_active, created_at, updated_at)
SELECT gen_random_uuid(),
       'loadtest-' || lpad(n::text, 4, '0') || '@example.com',
       '$argon2id$v=19$m=65536,t=3,p=4$N/lz/d+R1ulqJQB26G8U5A$cPfNCczIf5zNb9hu+mUGP2QktO8I4tYb+FsraX6LXKc',
       'user', true, true,
       now() - interval '400 days', now()
FROM generate_series(1, 100) AS n;

-- Workouts: 100 per user spread over the last 400 days.
INSERT INTO workouts (id, user_id, name, performed_at, created_at, updated_at)
SELECT gen_random_uuid(), u.id,
       (ARRAY['Push Day','Pull Day','Leg Day','Upper Body','Full Body'])[1 + (d % 5)],
       (CURRENT_DATE - (d * 4))::date,
       now(), now()
FROM users u CROSS JOIN generate_series(0, 99) AS d
WHERE u.email LIKE 'loadtest-%@example.com';

-- 4 exercises per workout, with load progressing slowly over time so the training-insights
-- analytics has a real trend to classify rather than flat noise.
INSERT INTO workout_exercises (id, workout_id, exercise_id, order_index, sets, reps, weight_kg)
SELECT gen_random_uuid(), w.id, e.id, e.rn,
       3 + (e.rn % 2),
       8 + (e.rn % 5),
       round((40 + (e.rn * 12) + (400 - (CURRENT_DATE - w.performed_at)) * 0.05)::numeric, 1)
FROM workouts w
JOIN LATERAL (
    SELECT id, row_number() OVER (ORDER BY name) AS rn
    FROM exercises WHERE created_by_user_id IS NULL ORDER BY name LIMIT 4
) e ON true
JOIN users u ON u.id = w.user_id
WHERE u.email LIKE 'loadtest-%@example.com';

-- Meals: 300 per user.
INSERT INTO meals (id, user_id, name, logged_at, calories, protein_g, carbs_g, fat_g, created_at)
SELECT gen_random_uuid(), u.id,
       (ARRAY['Breakfast','Lunch','Dinner','Snack'])[1 + (d % 4)],
       (CURRENT_DATE - (d / 3))::date,
       350 + (d % 400),
       round((25 + (d % 30))::numeric, 1),
       round((40 + (d % 60))::numeric, 1),
       round((10 + (d % 20))::numeric, 1),
       now()
FROM users u CROSS JOIN generate_series(0, 299) AS d
WHERE u.email LIKE 'loadtest-%@example.com';

-- Body measurements: 60 per user, trending down.
INSERT INTO body_measurements (id, user_id, recorded_at, weight_kg, body_fat_pct, waist_cm, created_at)
SELECT gen_random_uuid(), u.id,
       (CURRENT_DATE - (d * 7))::date,
       round((95 - (60 - d) * 0.15)::numeric, 1),
       round((24 - (60 - d) * 0.03)::numeric, 1),
       round((92 - (60 - d) * 0.1)::numeric, 1),
       now()
FROM users u CROSS JOIN generate_series(0, 59) AS d
WHERE u.email LIKE 'loadtest-%@example.com';

-- Profiles: without these, GET /profile is a legitimate 404 and the load test records it
-- as an error, which buries real failures in noise.
INSERT INTO profiles (id, user_id, display_name, date_of_birth, sex, height_cm, fitness_goal, activity_level, created_at, updated_at)
SELECT gen_random_uuid(), u.id,
       'Load Test ' || substring(u.email from 10 for 4),
       DATE '1990-01-01' + ((random() * 3650)::int),
       (ARRAY['male','female'])[1 + (floor(random() * 2))::int]::profile_sex,
       round((160 + random() * 30)::numeric, 1),
       'Build strength',
       'moderate'::profile_activity_level,
       now(), now()
FROM users u
WHERE u.email LIKE 'loadtest-%@example.com';

COMMIT;

ANALYZE users; ANALYZE workouts; ANALYZE workout_exercises; ANALYZE meals; ANALYZE body_measurements;

SELECT 'users' AS table, count(*) FROM users WHERE email LIKE 'loadtest-%'
UNION ALL SELECT 'workouts', count(*) FROM workouts w JOIN users u ON u.id=w.user_id WHERE u.email LIKE 'loadtest-%'
UNION ALL SELECT 'workout_exercises', count(*) FROM workout_exercises we JOIN workouts w ON w.id=we.workout_id JOIN users u ON u.id=w.user_id WHERE u.email LIKE 'loadtest-%'
UNION ALL SELECT 'meals', count(*) FROM meals m JOIN users u ON u.id=m.user_id WHERE u.email LIKE 'loadtest-%'
UNION ALL SELECT 'body_measurements', count(*) FROM body_measurements b JOIN users u ON u.id=b.user_id WHERE u.email LIKE 'loadtest-%';
