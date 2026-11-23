# 上传前密钥/隐私扫描器
# 扫描「将要提交到 git 的文件」（含未跟踪但未被 .gitignore 排除的文件），
# 而不是整个工作区 —— 工作区里的 tools/、build-artifacts/ 已被 .gitignore 排除，
# 扫它们只会淹没真正的发现。
#
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File tools\scan_secrets.ps1
# 退出码：0 = 干净，1 = 有发现（可直接接进 CI）

param(
    [string]$RepoRoot = (Get-Location).Path
)

$ErrorActionPreference = 'Stop'
Set-Location $RepoRoot

# ---- 收集待扫描文件 ----
$tracked   = git ls-files
$untracked = git ls-files --others --exclude-standard
$files = @($tracked + $untracked) | Sort-Object -Unique

$textExt = @(
    '.kt', '.kts', '.java', '.xml', '.html', '.htm', '.js', '.json', '.md',
    '.gradle', '.properties', '.pro', '.txt', '.yml', '.yaml', '.toml'
)
$plainNames = @('.gitignore', '.gitattributes', 'gradlew', 'gradlew.bat')

$files = $files | Where-Object {
    $ext  = [System.IO.Path]::GetExtension($_).ToLower()
    $name = [System.IO.Path]::GetFileName($_)
    ($textExt -contains $ext) -or ($plainNames -contains $name)
}

# ---- 规则 ----
# 注意：正则里不要直接写引号，用 \x22（双引号）/ \x27（单引号）代替，
# 否则 PowerShell 单引号字符串会被截断。
$rules = @(
    @{
        Name    = '真实 openid（o 开头 28 位）'
        Pattern = '(?<![A-Za-z0-9])o[A-Za-z0-9_-]{27}(?![A-Za-z0-9_-])'
        Unless  = '(?i)oABCDEF|oNEW|oOLD|oXXXX|oYOUR|placeholder|oTEST|example|(?<![A-Za-z0-9])openid[A-Za-z0-9_-]{24,}'
    }
    @{
        Name    = '会话令牌 sn='
        Pattern = 'sn=[0-9A-Fa-f]{24,}'
    }
    @{
        Name    = '微信选座 Cookie'
        Pattern = 'wechatSESS_ID\s*=\s*[0-9A-Fa-f]{16,}'
    }
    @{
        Name    = 'Authorization / Bearer 令牌'
        Pattern = 'Bearer\s+[A-Za-z0-9._\-]{20,}|Authorization\x22?\s*[:=]\s*[A-Za-z0-9]{32,}'
    }
    @{
        Name    = '私钥内容'
        Pattern = 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
    }
    @{
        Name    = '硬编码密码'
        Pattern = '(?i)(storePassword|keyPassword|password|passwd|pwd)\s*[=:]\s*\x22?[^\s\x22\x27,<]{6,}'
        Unless  = 'password|passwd|pwd|<|脱敏|占位|示例|your|xxx|placeholder|\*\*\*'
    }
    @{
        Name    = '手机号'
        Pattern = '(?<![0-9])1[3-9]\d{9}(?![0-9])'
    }
    @{
        Name    = '身份证号'
        Pattern = '(?<![0-9])\d{17}[0-9Xx](?![0-9])'
    }
    @{
        Name    = 'fixtures 里的真实姓名'
        Pattern = '(读者|姓名|借阅人)[：:]\s*[\u4e00-\u9fa5]{2,4}(?=[\s<\u3000])'
        Unless  = '张三|李四|王五|某某|读者姓名'
    }
    @{
        Name    = '本机绝对路径'
        Pattern = '[A-Za-z]:\\\\Users\\\\[^\s\x22\x27]+'
    }
)

$findings = New-Object System.Collections.Generic.List[object]

foreach ($rel in $files) {
    if (-not (Test-Path -LiteralPath $rel)) { continue }
    $lines = Get-Content -LiteralPath $rel -ErrorAction SilentlyContinue
    if (-not $lines) { continue }

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        foreach ($rule in $rules) {
            if ($line -notmatch $rule.Pattern) { continue }
            if ($rule.ContainsKey('Unless') -and $line -match $rule.Unless) { continue }
            $findings.Add([pscustomobject]@{
                File = $rel
                Line = $i + 1
                Rule = $rule.Name
                Text = $line.Trim()
            })
        }
    }
}

# ---- 输出 ----
if ($findings.Count -eq 0) {
    Write-Host "CLEAN: 扫描 $($files.Count) 个待提交文件，未发现密钥/隐私泄露。" -ForegroundColor Green
    exit 0
}

Write-Host "发现 $($findings.Count) 处可疑内容（扫描 $($files.Count) 个文件）：" -ForegroundColor Yellow
foreach ($f in $findings) {
    $preview = $f.Text
    if ($preview.Length -gt 130) { $preview = $preview.Substring(0, 130) + '...' }
    Write-Host ''
    Write-Host ('[{0}] {1}:{2}' -f $f.Rule, $f.File, $f.Line) -ForegroundColor Red
    Write-Host ('    {0}' -f $preview) -ForegroundColor DarkGray
}
exit 1
