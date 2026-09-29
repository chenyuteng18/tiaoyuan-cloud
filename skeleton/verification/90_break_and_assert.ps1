# ============================================================================
# 90_break_and_assert.ps1 -- Reverse verification (prove the assertions have teeth)
#
# METHOD (same as the skeleton review): a test that only passes on a correct
# implementation and a test that ALWAYS passes look identical from the outside.
# The only way to tell them apart is to inject a real defect and see it fail.
#
# Each scenario: break the policy -> run 03_assert -> assert it MUST FAIL
#                -> restore -> assert it MUST PASS again.
#
# [ASCII-only output on purpose] PowerShell 5.1 reads BOM-less .ps1 as ANSI(GBK)
# on this machine, which would garble any Chinese literal written into the log.
# Keep every emitted label ASCII so the evidence file stays readable.
# ============================================================================
$ws   = "C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59"
$psql = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
$env:PGPASSWORD = "postgres"
# Force UTF-8 out of the server so the evidence log stays readable: this host's
# console codepage is GBK, and PS 5.1 decodes child stdout/stderr with it by default.
$env:PGCLIENTENCODING = "UTF8"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding            = [System.Text.Encoding]::UTF8
$DB = "diaoyuanyun_rls_test"

# Script directory: prefer $PSScriptRoot (reliable in -File mode), fall back to
# the hardcoded deliverable path. Do NOT use `$x = if(){}else{}` -- that form has
# been observed to leave the variable empty on some PowerShell 5.1 hosts.
$HERE = $PSScriptRoot
if (-not $HERE) { $HERE = "$ws\deliverables\product-strategy\skeleton\verification" }

$out = @()

function Run-Assert {
  param($label)
  # KEY 1: capture the native exit code BEFORE any pipeline. Piping immediately
  #        (e.g. `& psql ... 2>&1 | Out-String`) resets $LASTEXITCODE and the
  #        gate signal would degrade to 0/empty -- observed in practice.
  # KEY 2: psql assertion failures are raised on stderr. PowerShell wraps them as
  #        ErrorRecord objects, so coerce to string to recover the real text.
  $raw   = & $psql -h 127.0.0.1 -p 5432 -U dy_app -d $DB -v ON_ERROR_STOP=1 -f "$HERE\03_assert.sql" 2>&1
  $code  = $LASTEXITCODE
  $lines = @($raw | ForEach-Object { "$_" })
  return @{ code = $code; lines = $lines }
}

# Pick the line that actually states the failure reason.
#
# LANGUAGE-INDEPENDENT by design: psql localises NOTICE/ERROR level words, so
# matching on those is fragile (and a Chinese literal in this file would be
# mis-decoded by PS 5.1 as GBK anyway). Instead rely on the assertion's own ID:
# every assertion prints "A<n>", and only the FAILING one lacks " OK".
function Select-FailureLine {
  param($lines)
  $hit = $lines | Where-Object { $_ -match 'A[0-8]' -and $_ -notmatch 'OK' } | Select-Object -First 1
  if (-not $hit) { $hit = $lines | Where-Object { $_ -match 'A[0-8]' } | Select-Object -Last 1 }
  if (-not $hit) { $hit = $lines | Where-Object { $_.Trim() } | Select-Object -Last 1 }
  # Normalise: drop the psql prefix and the long absolute path, keep "03_assert.sql:<line>: <message>"
  $hit = $hit -replace '^\s*psql:', ''
  $hit = $hit -replace '^.*?verification[\\/]', ''
  return ($hit -replace '\s+', ' ').Trim()
}

# Baseline: current state should PASS
$out += "########## BASELINE (should PASS) ##########"
$b = Run-Assert "baseline"
$out += "EXIT=$($b.code)  (0 = PASS)"
$out += (($b.lines | Where-Object { $_ -match 'A[0-8] OK|PASSED' } | ForEach-Object { ($_ -replace '^\s*psql:', '') -replace '^.*?verification[\\/]', '' }) -join "`n")

# ---- BREAK-1: drop NULLIF, use COALESCE with a default tenant (classic fail-open) ----
$out += "`n########## BREAK-1: remove NULLIF -> COALESCE default tenant (expect FAIL) ##########"
$sql = @"
ALTER TABLE customer NO FORCE ROW LEVEL SECURITY;
DROP POLICY tenant_isolation ON customer;
CREATE POLICY tenant_isolation ON customer FOR ALL
  USING      (tenant_id = COALESCE(NULLIF(current_setting('app.tenant_id', true), ''), 'aaaaaaaa-1111-1111-1111-111111111111')::uuid)
  WITH CHECK (tenant_id = COALESCE(NULLIF(current_setting('app.tenant_id', true), ''), 'aaaaaaaa-1111-1111-1111-111111111111')::uuid);
ALTER TABLE customer FORCE ROW LEVEL SECURITY;
"@
$sql | & $psql -h 127.0.0.1 -p 5432 -U postgres -d $DB -v ON_ERROR_STOP=1 -q 2>&1 | Out-Null
$r1 = Run-Assert "break1"
$out += "EXIT=$($r1.code)  (non-zero = correctly CAUGHT)"
$out += "ACTUAL FAILURE ASSERTION: " + (Select-FailureLine $r1.lines)

# ---- BREAK-2: disable FORCE (owner bypasses the policy) ----
# NOTE: measured behaviour is failure at A1 (owner reads all 2 rows), NOT at A6.
# The original comment guessed "expect FAIL at A6"; kept here so nobody is misled.
$out += "`n########## BREAK-2: disable FORCE RLS (measured: FAIL at A1) ##########"
"ALTER TABLE customer NO FORCE ROW LEVEL SECURITY;" | & $psql -h 127.0.0.1 -p 5432 -U postgres -d $DB -v ON_ERROR_STOP=1 -q 2>&1 | Out-Null
$r2 = Run-Assert "break2"
$out += "EXIT=$($r2.code)  (non-zero = correctly CAUGHT)"
$out += "ACTUAL FAILURE ASSERTION: " + (Select-FailureLine $r2.lines)

# ---- BREAK-3: USING (true) -- allow everything (crudest fail-open) ----
$out += "`n########## BREAK-3: USING (true) allow-all (expect FAIL at A1/A3) ##########"
$sql3 = @"
DROP POLICY tenant_isolation ON customer;
CREATE POLICY tenant_isolation ON customer FOR ALL USING (true) WITH CHECK (true);
"@
$sql3 | & $psql -h 127.0.0.1 -p 5432 -U postgres -d $DB -v ON_ERROR_STOP=1 -q 2>&1 | Out-Null
$r3 = Run-Assert "break3"
$out += "EXIT=$($r3.code)  (non-zero = correctly CAUGHT)"
$out += "ACTUAL FAILURE ASSERTION: " + (Select-FailureLine $r3.lines)

# ---- RESTORE the correct policy ----
$out += "`n########## RESTORE (expect PASS again) ##########"
$fix = @"
DROP POLICY tenant_isolation ON customer;
CREATE POLICY tenant_isolation ON customer FOR ALL
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE customer FORCE ROW LEVEL SECURITY;
"@
$fix | & $psql -h 127.0.0.1 -p 5432 -U postgres -d $DB -v ON_ERROR_STOP=1 -q 2>&1 | Out-Null
$r4 = Run-Assert "restore"
$out += "EXIT=$($r4.code)  (0 = restored correctly)"
$out += (($r4.lines | Where-Object { $_ -match 'PASSED' } | ForEach-Object { ($_ -replace '^\s*psql:', '') -replace '^.*?verification[\\/]', '' }) -join "`n")

$out | Set-Content -Encoding UTF8 "$HERE\run4-reverse.txt"
"done"