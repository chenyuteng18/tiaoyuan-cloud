# ============================================================================
# 91_rls_gate_reverse.ps1 -- A2 RLS 门禁的【反向验证】(prove the gate has teeth)
#
# 为什么必须做这件事
# ------------------
# 一个"只对正确实现通过"的测试，和一个"永远都通过"的测试，从外面看一模一样。
# 唯一的区分办法：注入一个真实缺陷，看它是否变红。
# 本脚本注入的不是测试代码，而是【被测交付物本体】——
#   deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql
# 也就是说：改坏要交付的那份迁移脚本，CI 门禁必须红。这才是"有牙齿"的定义。
#
# 三个注入（与 DoD 逐条对应）
#   1) FORCE  ROW LEVEL SECURITY  ->  NO FORCE
#   2) NULLIF 归一                ->  COALESCE 兜底默认租户（fail-open 的经典入口）
#   3) USING/WITH CHECK 表达式    ->  USING (true) / WITH CHECK (true)（等于不隔离）
#
# 每次都跑同一套门禁（mvn -pl dy-app -am test），记录：
#   注入内容 / 期望失败点 / 实际失败断言原文
#
# [ASCII-only 输出] 本机 PS 5.1 把无 BOM 的 .ps1 当 ANSI(GBK) 读，
# 写中文到日志会变乱码；故本脚本自身的标签全用 ASCII，
# 而被捕获的断言原文（UTF-8 中文）原样透传，保证证据可读。
# ============================================================================
# 注意：不要用 ErrorActionPreference="Stop"。
# mvn.cmd 会向 stderr 打 JVM 警告（如 "Sharing is only supported for boot loader classes"），
# 在 Stop 模式下会被 PS 当成终止错误，脚本会在第一次 Run-Gate 时就中断，
# 结果是"看起来跑了、其实一个注入都没验证"——这正是最危险的假绿。
$ErrorActionPreference = "Continue"

$ws   = "C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59"
$sk   = "$ws\deliverables\product-strategy\skeleton"
$mvn  = "$ws\.tools\apache-maven-3.9.9\bin\mvn.cmd"
$mig  = "$sk\dy-app\src\main\resources\db\migration\V1__baseline_tenant_rls.sql"
$bak  = "$sk\dy-app\src\main\resources\db\migration\V1__baseline_tenant_rls.sql.revbak"
$here = "$sk\verification"
$logs = "$sk\dy-app\target\rls-reverse-logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null

$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding          = [System.Text.Encoding]::UTF8
$env:PGPASSWORD          = "postgres"
$env:PGCLIENTENCODING    = "UTF8"

$out = @()

function Run-Gate {
  param([string]$tag)
  # 不清 clean: 迁移脚本在 src/main/resources, process-resources 会重新拷贝到 target/classes
  $log = "$logs\$tag.log"
  $raw = & $mvn -f "$sk\pom.xml" -B -ntp -pl dy-app -am test 2>&1
  $code = $LASTEXITCODE
  $raw | Out-File -FilePath $log -Encoding utf8
  return @{ code = $code; log = $log; lines = @($raw | ForEach-Object { "$_" }) }
}

# 从 mvn 日志里挑出"真正决定红/绿的那条断言原文"。
# 优先取 surefire 的 "Failures:" / "Errors:" 明细段（形如 "  XxxTest.method:line 消息"），
# 它们是断言自己的话，而非 Maven 的包装语。
function Select-FailureLine {
  param($lines)
  $hits = @($lines | Where-Object {
      $_ -match '^\s*\[ERROR\]\s{2,}\w+Test\.\w+:\d+' -or
      $_ -match '^\s*\[ERROR\]\s{2,}\w+Test\.\w+'
  })
  if ($hits.Count -gt 0) {
    return (@($hits | ForEach-Object { ($_ -replace '^\s*\[ERROR\]\s+', '').Trim() }) -join " || ")
  }
  $hit = $lines | Where-Object { $_ -match '\[ERROR\].*Tests run.*Failures: [1-9]' } | Select-Object -First 1
  if ($hit) { return ($hit -replace '^\s*\[ERROR\]\s+', '').Trim() }
  $hit = $lines | Where-Object { $_ -match 'BUILD FAILURE' } | Select-Object -First 1
  if ($hit) { return $hit.Trim() }
  return "(no failure line captured)"
}

# 从失败明细里挑出【最能说明该注入机制】的那一条。
# 三栏表需要"一句话证据"，全列出来反而看不清因果，故按注入类型各选一条。
function Select-KeyFailure {
  param($lines, [string]$preferredPattern)
  $hits = @($lines | Where-Object { $_ -match '^\s*\[ERROR\]\s{2,}\w+Test\.\w+:\d+' })
  if ($hits.Count -eq 0) { return (Select-FailureLine $lines) }
  $pick = $hits | Where-Object { $_ -match $preferredPattern } | Select-Object -First 1
  if (-not $pick) { $pick = $hits | Select-Object -First 1 }
  return (($pick -replace '^\s*\[ERROR\]\s+', '').Trim())
}

function Test-AnyFailureMatches {
  param($lines, [string]$pattern)
  $hits = @($lines | Where-Object { $_ -match '\[ERROR\]' -and $_ -match $pattern })
  return ($hits.Count -gt 0)
}

function Select-BuildVerdict {
  param($lines)
  $b = $lines | Where-Object { $_ -match 'BUILD SUCCESS|BUILD FAILURE' } | Select-Object -Last 1
  if ($b) { return $b.Trim() }
  return "(no verdict)"
}

function Restore-Migration {
  if (Test-Path $bak) {
    Copy-Item $bak $mig -Force
  }
}

# 注入是否真的落到了文件上？没落上就必须显式失败。
# 否则正则没匹配时，"注入后的构建"其实跑的还是正确代码，会显示 BUILD SUCCESS,
# 而我们可能把它误读成"注入无效"或更糟——误读成"门禁没抓住"。
function Assert-Injected {
  param([string]$label, [string]$pattern, [bool]$shouldExist)
  $found = (Select-String -Path $mig -Pattern $pattern -Quiet) -eq $true
  if ($found -ne $shouldExist) {
    throw "INJECTION FAILED [$label]: pattern '$pattern' shouldExist=$shouldExist but found=$found. 注入未生效，本次结果不可信。"
  }
}

# 只在【代码行】里查模式（排除 -- 注释行）。
# 必须排除注释：V1 迁移脚本本身就在注释里列了反例
#   L60  ❌ COALESCE(current_setting('app.tenant_id', true), '<某个默认租户>')
#   L62  ❌ USING (true)
# 若不排除注释，还原检查会永远报 True，得出"注入残留未清"的错误结论。
function Test-CodeContains {
  param([string]$pattern)
  $codeLines = @(Get-Content $mig -Encoding UTF8 | Where-Object { $_.Trim() -notmatch '^--' })
  $hits = @($codeLines | Select-String -Pattern $pattern)
  return ($hits.Count -gt 0)
}

# ---------------------------------------------------------------------------
# 0) 备份 + 基线
# ---------------------------------------------------------------------------
Copy-Item $mig $bak -Force
$baselineBytes = (Get-Item $mig).Length
$md5 = (Get-FileHash $mig -Algorithm MD5).Hash

# 整个注入-验证-还原过程包在 try/finally 里：
# 任何一步抛异常（包括 Assert-Injected 的注入未生效），都必须把交付物的迁移脚本还原回基线，
# 绝不允许把一份被改坏的 DDL 留在仓库里。
try {

$out += "########## 0) ARTIFACT UNDER TEST ##########"
$out += "file   : $mig"
$out += "bytes  : $baselineBytes"
$out += "md5    : $md5"
$out += ""

$out += "########## 1) BASELINE (expect BUILD SUCCESS / exit 0) ##########"
$b = Run-Gate "baseline"
$out += "EXIT=$($b.code)"
$out += "VERDICT: " + (Select-BuildVerdict $b.lines)
$out += (($b.lines | Where-Object { $_ -match 'Tests run: \d+, Failures: 0, Errors: 0, Skipped: 0\s*$' } | ForEach-Object { $_.Trim() }) -join "`n")
$out += ""

# ---------------------------------------------------------------------------
# BREAK-1: NO FORCE  -> owner(dy_app) 绕过全部策略
#
# 期望失败点（注入前就写死，事后不得据实况倒推）：
#   A6（FORCE 语义）+ A1（租户A 读到全表）+ 未设上下文读到 2 行。
#   机制：dy_app 同时是表 owner；一旦去掉 FORCE，owner 直接绕过所有策略，
#   于是"任何上下文下都看得见全部行"。
# ---------------------------------------------------------------------------
$out += "########## BREAK-1: FORCE ROW LEVEL SECURITY -> NO FORCE (expect BUILD FAILURE) ##########"
(Get-Content $mig -Raw -Encoding UTF8) `
    -replace 'ALTER TABLE customer FORCE ROW LEVEL SECURITY;', 'ALTER TABLE customer NO FORCE ROW LEVEL SECURITY;' `
    | Set-Content $mig -Encoding UTF8 -NoNewline
Assert-Injected "break1" 'ALTER TABLE customer NO FORCE ROW LEVEL SECURITY;' $true
$r1 = Run-Gate "break1_noforce"
$out += "INJECTED      : ALTER TABLE customer FORCE ROW LEVEL SECURITY  ->  NO FORCE ROW LEVEL SECURITY"
$out += "EXPECT FAIL AT: A6 (FORCE 语义) / A1 (租户A 读到全表) / 未设上下文读到 2 行"
$out += "EXIT          : $($r1.code)  (non-zero = correctly CAUGHT)"
$out += "VERDICT       : " + (Select-BuildVerdict $r1.lines)
$out += "ACTUAL FAILURE: " + (Select-KeyFailure $r1.lines 'FORCE ROW LEVEL SECURITY')
$out += "  | A1 also caught (psql gate): " + (Test-AnyFailureMatches $r1.lines 'A1 . 断言失败')
$out += "  | fail-closed also caught   : " + (Test-AnyFailureMatches $r1.lines '未设 app.tenant_id 时读到')
$out += "ALL FAILURE LINES: " + (Select-FailureLine $r1.lines)
$out += ""
Restore-Migration

# ---------------------------------------------------------------------------
# BREAK-2: NULLIF -> COALESCE 默认租户（fail-open 最经典的入口）
#
# 期望失败点：
#   A3（未设上下文读到 1 行，而非 0）+ A4（空串上下文读到 1 行）。
#   机制：COALESCE 把"无上下文"兜底成租户A，于是未设上下文时读到 A 的 1 行
#   —— 这正是 V1 脚本注释 L59-L61 明令禁止的写法。
# ---------------------------------------------------------------------------
$out += "########## BREAK-2: NULLIF -> COALESCE(default tenant) i.e. fail-open (expect BUILD FAILURE) ##########"
(Get-Content $mig -Raw -Encoding UTF8) `
    -replace "NULLIF\(current_setting\('app\.tenant_id', true\), ''\)", "COALESCE(NULLIF(current_setting('app.tenant_id', true), ''), 'aaaaaaaa-1111-1111-1111-111111111111')" `
    | Set-Content $mig -Encoding UTF8 -NoNewline
Assert-Injected "break2" 'COALESCE\(NULLIF\(current_setting' $true
$r2 = Run-Gate "break2_coalesce"
$out += "INJECTED      : NULLIF(current_setting('app.tenant_id', true), '')::uuid"
$out += "                -> COALESCE(NULLIF(...), 'aaaaaaaa-1111-1111-1111-111111111111')::uuid"
$out += "EXPECT FAIL AT: A3 (未设上下文读到 1 行) / A4 (空串上下文读到 1 行)"
$out += "EXIT          : $($r2.code)  (non-zero = correctly CAUGHT)"
$out += "VERDICT       : " + (Select-BuildVerdict $r2.lines)
$out += "ACTUAL FAILURE: " + (Select-KeyFailure $r2.lines '空串上下文读到')
$out += "  | A3 also caught (psql gate): " + (Test-AnyFailureMatches $r2.lines 'A3 . 断言失败')
$out += "  | A4 also caught (psql gate): " + (Test-AnyFailureMatches $r2.lines 'A4 . 断言失败')
$out += "ALL FAILURE LINES: " + (Select-FailureLine $r2.lines)
$out += ""
Restore-Migration

# ---------------------------------------------------------------------------
# BREAK-3: USING/WITH CHECK -> (true)，等于完全不隔离
#
# 期望失败点：
#   A1/A2（双向串租户）+ A5（跨租户写入被放行）+ A7（策略元数据不含 NULLIF）。
#   机制：USING(true) 让所有行可见；WITH CHECK(true) 让跨租户写入畅通。
# ---------------------------------------------------------------------------
$out += "########## BREAK-3: USING/WITH CHECK -> (true) i.e. allow-all (expect BUILD FAILURE) ##########"
$sql = Get-Content $mig -Raw -Encoding UTF8
$sql = $sql -replace "USING      \(tenant_id = NULLIF\(current_setting\('app\.tenant_id', true\), ''\)::uuid\)", "USING      (true)"
$sql = $sql -replace "WITH CHECK \(tenant_id = NULLIF\(current_setting\('app\.tenant_id', true\), ''\)::uuid\)", "WITH CHECK (true)"
$sql | Set-Content $mig -Encoding UTF8 -NoNewline
Assert-Injected "break3" 'USING      \(true\)' $true
$r3 = Run-Gate "break3_using_true"
$out += "INJECTED      : USING (tenant_id = NULLIF(...)::uuid)      -> USING (true)"
$out += "                WITH CHECK (tenant_id = NULLIF(...)::uuid) -> WITH CHECK (true)"
$out += "EXPECT FAIL AT: A1/A2 (双向串租户) / A5 (跨租户写入被放行) / A7 (策略不含 NULLIF)"
$out += "EXIT          : $($r3.code)  (non-zero = correctly CAUGHT)"
$out += "VERDICT       : " + (Select-BuildVerdict $r3.lines)
$out += "ACTUAL FAILURE: " + (Select-KeyFailure $r3.lines 'WITH CHECK 未生效')
$out += "  | A1 also caught (psql gate): " + (Test-AnyFailureMatches $r3.lines 'A1 . 断言失败')
$out += "  | A7 also caught (metadata):  " + (Test-AnyFailureMatches $r3.lines 'USING 必须含 NULLIF')
$out += "ALL FAILURE LINES: " + (Select-FailureLine $r3.lines)
$out += ""
Restore-Migration

# ---------------------------------------------------------------------------
# 5) RESTORE  -> 必须恢复全绿，且文件字节/MD5 与基线一致
# ---------------------------------------------------------------------------
$out += "########## 5) RESTORE (expect BUILD SUCCESS again + byte-identical artifact) ##########"
$restoredBytes = (Get-Item $mig).Length
$restoredMd5 = (Get-FileHash $mig -Algorithm MD5).Hash
$r5 = Run-Gate "restore"
$out += "EXIT=$($r5.code)  (0 = restored correctly)"
$out += "VERDICT: " + (Select-BuildVerdict $r5.lines)
$out += (($r5.lines | Where-Object { $_ -match 'Tests run: \d+, Failures: 0, Errors: 0, Skipped: 0\s*$' } | ForEach-Object { $_.Trim() }) -join "`n")
$out += "bytes  : $restoredBytes (baseline $baselineBytes)"
$out += "md5    : $restoredMd5 (baseline $md5)"
$out += "byte-identical: " + ($restoredBytes -eq $baselineBytes -and $restoredMd5 -eq $md5)
$out += ""

# ---------------------------------------------------------------------------
# 6) 注入残留检查（只在代码行里查，排除注释里的反例文字；正常应全为 False）
# ---------------------------------------------------------------------------
$out += "########## CLEANUP ##########"
$out += "leftover 'NO FORCE'     : " + (Test-CodeContains 'ALTER TABLE customer NO FORCE')
$out += "leftover 'COALESCE(...)' : " + (Test-CodeContains 'COALESCE\(NULLIF\(current_setting')
$out += "leftover 'USING (true)' : " + (Test-CodeContains 'USING\s+\(true\)')

} finally {
  # 无论中途发生了什么，交付物的迁移脚本必须回到基线
  Restore-Migration
  Remove-Item $bak -Force -ErrorAction SilentlyContinue
  $out += ""
  $out += "########## FINALLY ##########"
  $out += "artifact restored: " + ((Get-FileHash $mig -Algorithm MD5).Hash -eq $md5)
  $out += "temp backup removed: " + (-not (Test-Path $bak))
  # 证据必须先落盘再可能向上抛错：中途失败时也需要留下"跑到哪一步"的记录
  $out | Set-Content -Encoding UTF8 "$here\run5-rls-gate-reverse.txt"
}

"done -> $here\run5-rls-gate-reverse.txt"