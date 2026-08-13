$ErrorActionPreference = 'Stop'

$project = Join-Path $PSScriptRoot 'IllusionLiveNotifier.Android'
$toolRoot = 'C:\Android\illusion-tools'
# Android aapt2 on Windows still fails on some non-ASCII paths. Build in an ASCII staging path.
$build = [IO.Path]::GetFullPath((Join-Path $toolRoot 'app-build'))
$dist = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'android-dist'))
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android\illusion-tools\android-sdk' }
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    (Get-ChildItem 'C:\Android\illusion-tools\jdk' -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1).FullName
}

function Reset-SafeDirectory([string]$path, [string]$parent) {
    $fullParent = [IO.Path]::GetFullPath($parent).TrimEnd('\') + '\'
    if (-not $path.StartsWith($fullParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe output path: $path"
    }
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    New-Item -ItemType Directory -Path $path | Out-Null
}

function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit $LASTEXITCODE)" }
}

if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { throw 'JDK 17 not found.' }
$env:JAVA_HOME = $jdk
$tools = Join-Path $sdk 'build-tools\36.0.0'
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
if (-not (Test-Path $androidJar)) { throw 'Android SDK platform 36 not found.' }

Reset-SafeDirectory $build $toolRoot
Reset-SafeDirectory $dist $PSScriptRoot
$input = New-Item -ItemType Directory -Path (Join-Path $build 'input')
Copy-Item (Join-Path $project 'AndroidManifest.xml') $input.FullName
Copy-Item (Join-Path $project 'res') $input.FullName -Recurse
Copy-Item (Join-Path $project 'src') $input.FullName -Recurse
Copy-Item (Join-Path $project 'tests') $input.FullName -Recurse
$gen = New-Item -ItemType Directory -Path (Join-Path $build 'gen')
$classes = New-Item -ItemType Directory -Path (Join-Path $build 'classes')
$testClasses = New-Item -ItemType Directory -Path (Join-Path $build 'test-classes')
$dex = New-Item -ItemType Directory -Path (Join-Path $build 'dex')

$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$parser = Join-Path $input.FullName 'src\com\illusionlive\notifier\FeedParser.java'
$colors = Join-Path $input.FullName 'src\com\illusionlive\notifier\MemberColors.java'
$selfTest = Join-Path $input.FullName 'tests\com\illusionlive\notifier\SelfTest.java'

& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -d $testClasses.FullName $parser $colors $selfTest
Assert-Exit 'Parser test compile'
& $java -ea -cp $testClasses.FullName com.illusionlive.notifier.SelfTest
Assert-Exit 'Parser self-test'

$compiledResources = Join-Path $build 'resources.zip'
$baseApk = Join-Path $build 'base.apk'
& (Join-Path $tools 'aapt2.exe') compile --dir (Join-Path $input.FullName 'res') -o $compiledResources
Assert-Exit 'Resource compile'
& (Join-Path $tools 'aapt2.exe') link -o $baseApk -I $androidJar `
    --manifest (Join-Path $input.FullName 'AndroidManifest.xml') `
    --min-sdk-version 26 --target-sdk-version 36 --version-code 4 --version-name 1.0.3 `
    --java $gen.FullName $compiledResources
Assert-Exit 'APK resource link'

# R.java comes from the link step so the app can reference its own drawables by name.
$sources = @(Get-ChildItem (Join-Path $input.FullName 'src'), $gen.FullName -Recurse -Filter '*.java' |
    ForEach-Object FullName)
$javacArgs = @('--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
    '-classpath', $androidJar, '-d', $classes.FullName) + $sources
& $javac @javacArgs
Assert-Exit 'Android source compile'

$classesJar = Join-Path $build 'classes.jar'
& $jar --create --file $classesJar -C $classes.FullName .
Assert-Exit 'Class archive'
& (Join-Path $tools 'd8.bat') --release --min-api 26 --lib $androidJar --output $dex.FullName $classesJar
Assert-Exit 'DEX compile'
& $jar --update --file $baseApk -C $dex.FullName classes.dex
Assert-Exit 'DEX package'

$alignedApk = Join-Path $build 'aligned.apk'
& (Join-Path $tools 'zipalign.exe') -f -p 4 $baseApk $alignedApk
Assert-Exit 'APK alignment'

$keyDirectory = 'C:\Android\illusion-tools\keys'
New-Item -ItemType Directory -Force $keyDirectory | Out-Null
$keystore = Join-Path $keyDirectory 'illusionlive-notifier.p12'
$password = 'illusionlive-local-key'
if (-not (Test-Path $keystore)) {
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -noprompt -storetype PKCS12 `
        -keystore $keystore -storepass $password -keypass $password -alias illusionlive `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=IllusionLive Notifier, OU=Local Build, O=IllusionLive, L=Seoul, C=KR'
    Assert-Exit 'Signing key creation'
}

$signedApk = Join-Path $build 'IllusionLiveNotifier.apk'
& (Join-Path $tools 'apksigner.bat') sign --ks $keystore --ks-pass "pass:$password" `
    --key-pass "pass:$password" --out $signedApk $alignedApk
Assert-Exit 'APK signing'
& (Join-Path $tools 'apksigner.bat') verify --verbose --print-certs $signedApk
Assert-Exit 'APK signature verification'
# Let aapt run to completion first; piping straight into Select-Object -First kills it mid-write.
$badging = & (Join-Path $tools 'aapt.exe') dump badging $signedApk
Assert-Exit 'APK manifest inspection'
$badging | Select-Object -First 6

$apk = Join-Path $dist 'IllusionLiveNotifier.apk'
Copy-Item $signedApk $apk
$readme = Join-Path $dist 'README-Android.md'
Copy-Item (Join-Path $PSScriptRoot 'README-Android.md') $readme
$zip = Join-Path $dist 'IllusionLiveNotifier-android.zip'
Compress-Archive -LiteralPath $apk, $readme -DestinationPath $zip -CompressionLevel Optimal

Get-FileHash $apk, $zip -Algorithm SHA256 | Select-Object Path, Hash
