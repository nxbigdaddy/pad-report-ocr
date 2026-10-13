$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $serverRoot

if (Test-Path -LiteralPath '.env') {
    Get-Content -LiteralPath '.env' | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $name, $value = $line.Split('=', 2)
            [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
        }
    }
}

python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

