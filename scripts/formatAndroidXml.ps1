param(
    [Parameter(Mandatory = $true)]
    [string] $FilePath
)

$ErrorActionPreference = "Stop"
$resolvedPath = (Resolve-Path -LiteralPath $FilePath).Path
$portablePath = $resolvedPath.Replace("\", "/")
if ($portablePath -notmatch "/res/layout/[^/]+\.xml$") {
    exit 0
}

$toolDirectory = Join-Path $env:LOCALAPPDATA "AntennaPodSkipper\tools"
$formatterPath = Join-Path $toolDirectory "android-xml-formatter-1.1.0.jar"
if (-not (Test-Path -LiteralPath $formatterPath)) {
    New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
    Invoke-WebRequest `
        -Uri "https://github.com/ByteHamster/android-xml-formatter/releases/download/1.1.0/android-xml-formatter.jar" `
        -OutFile $formatterPath
}

& java -jar $formatterPath $resolvedPath
if ($LASTEXITCODE -ne 0) {
    throw "Android XML formatter failed for $resolvedPath"
}
