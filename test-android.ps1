# 순수 자바 클래스만 모아 SelfTest 를 돌린다. APK 를 만들지 않으므로 키스토어가 필요 없다.
# 컴파일 목록은 build-android.ps1 의 자체 테스트 줄과 같아야 한다. 한쪽만 고치지 말 것.
$ErrorActionPreference = 'Stop'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    (Get-ChildItem 'C:\Android\illusion-tools\jdk' -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1).FullName
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { throw 'JDK 17 not found.' }

$src = Join-Path $PSScriptRoot 'IllusionLiveNotifier.Android\src\com\illusionlive\notifier'
$tests = Join-Path $PSScriptRoot 'IllusionLiveNotifier.Android\tests\com\illusionlive\notifier'
$out = Join-Path $env:TEMP 'illusionlive-test-classes'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

$sources = @(
    (Join-Path $src 'FeedParser.java'),
    (Join-Path $src 'MemberColors.java'),
    (Join-Path $src 'CommentParser.java'),
    (Join-Path $src 'TrackedPosts.java'),
    (Join-Path $src 'CommentRules.java'),
    (Join-Path $tests 'SelfTest.java')
)
& (Join-Path $jdk 'bin\javac.exe') --release 8 -encoding UTF-8 -Xlint:all -Werror -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "compile failed (exit $LASTEXITCODE)" }
& (Join-Path $jdk 'bin\java.exe') -ea -cp $out com.illusionlive.notifier.SelfTest
if ($LASTEXITCODE -ne 0) { throw "self-test failed (exit $LASTEXITCODE)" }
