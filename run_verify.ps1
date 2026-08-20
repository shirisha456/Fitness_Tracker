# Fitness Tracker verification script - outputs to verify_results.txt
$ErrorActionPreference = "Continue"
$out = "C:\Users\Shirisha\Fitness_tracker\verify_results.txt"
"" | Set-Content $out

function Log($msg) {
    $msg | Tee-Object -FilePath $out -Append
}

Set-Location "C:\Users\Shirisha\Fitness_tracker"
Log "=== Step 1: CWD ==="
Log (Get-Location).Path

Log "`n=== Step 2: Root .env DATABASE_URL ==="
Select-String -Path ".env" -Pattern "DATABASE_URL" | ForEach-Object { Log $_.Line }

Log "`n=== Step 3: docker compose up -d --build ==="
docker compose up -d --build 2>&1 | ForEach-Object { Log $_ }

Log "`n=== Step 4: Wait for api healthy ==="
$maxWait = 180
$elapsed = 0
while ($elapsed -lt $maxWait) {
    $status = docker inspect --format='{{.State.Health.Status}}' fitness_tracker-api-1 2>&1
    Log "api health: $status (elapsed ${elapsed}s)"
    if ($status -eq "healthy") { break }
    Start-Sleep -Seconds 5
    $elapsed += 5
}

Log "`n=== Step 5: DATABASE_URL in container ==="
docker compose exec -T api printenv DATABASE_URL 2>&1 | ForEach-Object { Log $_ }

Log "`n=== Step 6: Health endpoints ==="
try {
    $health = Invoke-RestMethod -Uri "http://localhost/api/v1/health" -TimeoutSec 10
    Log "health: $($health | ConvertTo-Json -Compress)"
} catch {
    Log "health ERROR: $_"
}
try {
    $ready = Invoke-RestMethod -Uri "http://localhost/api/v1/ready" -TimeoutSec 10
    Log "ready: $($ready | ConvertTo-Json -Compress)"
} catch {
    Log "ready ERROR: $_"
}

Log "`n=== docker ps ==="
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>&1 | ForEach-Object { Log $_ }

Log "`n=== Step 7: pytest ==="
Set-Location "C:\Users\Shirisha\Fitness_tracker\backend"
& .\.venv\Scripts\python.exe -m pytest -v --tb=short 2>&1 | ForEach-Object { Log $_ }

Log "`n=== DONE ==="
