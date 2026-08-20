$ErrorActionPreference = "Continue"
$log = "C:\Users\Shirisha\Fitness_tracker\test_run_output.txt"
"" | Set-Content $log -Encoding UTF8

function Append-Log($title) { Add-Content $log "`n========== $title ==========" }

Append-Log "STEP 1 - CORS test"
Set-Location "C:\Users\Shirisha\Fitness_tracker\backend"
$py = "C:\Users\Shirisha\Fitness_tracker\backend\.venv\Scripts\python.exe"
if (-not (Test-Path $py)) { $py = "python" }
Add-Content $log "Using python: $py"
& $py -c "from app.config import Settings; import os; os.environ['SECRET_KEY']='test-secret-key-16'; os.environ['DATABASE_URL']='postgresql+asyncpg://u:p@db:5432/fitforge'; os.environ['REDIS_URL']='redis://redis:6379/0'; os.environ['CORS_ORIGINS']='http://localhost:3000,http://localhost'; from app.config import get_settings; get_settings.cache_clear(); s=Settings(); print('CORS', s.cors_origins); print('DB', s.database_url)" 2>&1 | Out-String | Add-Content $log
Add-Content $log "STEP1_EXIT: $LASTEXITCODE"

Append-Log "STEP 2 - docker compose up"
Set-Location "C:\Users\Shirisha\Fitness_tracker"
docker compose up -d --build 2>&1 | Out-String | Add-Content $log
Add-Content $log "DOCKER_UP_EXIT: $LASTEXITCODE"

Append-Log "Waiting for api healthy (up to 4 min)"
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
  $psOut = docker compose ps 2>&1 | Out-String
  if ($psOut -match "api" -and $psOut -match "healthy") { Add-Content $log "Healthy detected"; break }
  Start-Sleep -Seconds 5
}

Append-Log "docker compose ps"
docker compose ps 2>&1 | Out-String | Add-Content $log
Append-Log "DATABASE_URL"
docker compose exec -T api printenv DATABASE_URL 2>&1 | Out-String | Add-Content $log
Append-Log "REDIS_URL"
docker compose exec -T api printenv REDIS_URL 2>&1 | Out-String | Add-Content $log
Append-Log "curl health"
curl.exe -s http://localhost/api/v1/health 2>&1 | Out-String | Add-Content $log
Append-Log "curl ready"
curl.exe -s http://localhost/api/v1/ready 2>&1 | Out-String | Add-Content $log
Append-Log "api logs tail 50"
docker compose logs api --tail 50 2>&1 | Out-String | Add-Content $log

Append-Log "STEP 3 - pytest"
Set-Location "C:\Users\Shirisha\Fitness_tracker\backend"
& "C:\Users\Shirisha\Fitness_tracker\backend\.venv\Scripts\python.exe" -m pytest -v --tb=short 2>&1 | Out-String | Add-Content $log
Add-Content $log "PYTEST_EXIT: $LASTEXITCODE"

Get-Content $log -Raw
