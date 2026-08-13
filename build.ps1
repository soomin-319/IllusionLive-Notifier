param([switch]$NoZip)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $root 'IllusionLiveNotifier\IllusionLiveNotifier.csproj'
$dist = Join-Path $root 'dist'
$rootFull = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
$distFull = [IO.Path]::GetFullPath($dist)
if (-not $distFull.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output path escaped workspace' }

if (Test-Path $distFull) { Remove-Item -LiteralPath $distFull -Recurse -Force }

dotnet publish $project -c Release -r win-x64 --self-contained true -o $dist `
  -p:PublishSingleFile=true `
  -p:IncludeNativeLibrariesForSelfExtract=true `
  -p:EnableCompressionInSingleFile=true `
  -p:DebugType=None `
  -p:DebugSymbols=false

Copy-Item (Join-Path $root 'README.md') (Join-Path $dist 'README.md')
New-Item -ItemType Directory -Path (Join-Path $dist 'docs') | Out-Null
Copy-Item (Join-Path $root 'docs\screenshot.png') (Join-Path $dist 'docs\screenshot.png')

if (-not $NoZip) {
  $zip = Join-Path $root 'IllusionLiveNotifier-win-x64.zip'
  if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
  Compress-Archive -Path (Join-Path $dist '*') -DestinationPath $zip -CompressionLevel Optimal
}

Write-Host "Done: $dist\IllusionLiveNotifier.exe"
