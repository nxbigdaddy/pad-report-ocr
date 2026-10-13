$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = [System.IO.Path]::GetFullPath($projectRoot)
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$buildTools = Join-Path $sdkRoot 'build-tools\34.0.0'
$androidJar = Join-Path $sdkRoot 'platforms\android-34\android.jar'
$jdkRoot = 'C:\Program Files\Android\openjdk\jdk-21.0.8'

$aapt2 = Join-Path $buildTools 'aapt2.exe'
$aapt = Join-Path $buildTools 'aapt.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$javac = Join-Path $jdkRoot 'bin\javac.exe'
$jar = Join-Path $jdkRoot 'bin\jar.exe'
$keytool = Join-Path $jdkRoot 'bin\keytool.exe'

@($aapt2, $aapt, $d8, $zipalign, $apksigner, $javac, $jar, $keytool, $androidJar) | ForEach-Object {
    if (-not (Test-Path -LiteralPath $_)) {
        throw "Required build tool is missing: $_"
    }
}

$outputDir = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'manual-build'))
if (-not $outputDir.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean output outside the project: $outputDir"
}
if (Test-Path -LiteralPath $outputDir) {
    Remove-Item -LiteralPath $outputDir -Recurse -Force
}

$generatedDir = Join-Path $outputDir 'generated'
$classesDir = Join-Path $outputDir 'classes'
$dexDir = Join-Path $outputDir 'dex'
New-Item -ItemType Directory -Path $generatedDir, $classesDir, $dexDir -Force | Out-Null

$resourcesZip = Join-Path $outputDir 'resources.zip'
$unsignedApk = Join-Path $outputDir 'app-unsigned.apk'
$alignedApk = Join-Path $outputDir 'app-aligned.apk'
$finalApk = Join-Path $outputDir 'PADTableDemo-debug.apk'
$manifest = Join-Path $projectRoot 'app\src\main\AndroidManifest.xml'
$resources = Join-Path $projectRoot 'app\src\main\res'

& $aapt2 compile --dir $resources -o $resourcesZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

& $aapt2 link `
    -o $unsignedApk `
    -I $androidJar `
    --manifest $manifest `
    --java $generatedDir `
    --min-sdk-version 23 `
    --target-sdk-version 34 `
    --version-code 5 `
    --version-name '0.4.0' `
    $resourcesZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$sourceFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName }
    Get-ChildItem -LiteralPath $generatedDir -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName }
)
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classesDir @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$classesJar = Join-Path $outputDir 'classes.jar'
& $jar cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $jdkRoot
try {
    & $d8 --lib $androidJar --min-api 23 --output $dexDir $classesJar
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

    Push-Location -LiteralPath $dexDir
    try {
        & $aapt add $unsignedApk 'classes.dex'
        if ($LASTEXITCODE -ne 0) { throw 'Adding classes.dex failed' }
    }
    finally {
        Pop-Location
    }

    & $zipalign -p -f 4 $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

    $androidUserDir = Join-Path $env:USERPROFILE '.android'
    $debugKeyStore = Join-Path $androidUserDir 'debug.keystore'
    if (-not (Test-Path -LiteralPath $debugKeyStore)) {
        New-Item -ItemType Directory -Path $androidUserDir -Force | Out-Null
        & $keytool -genkeypair -keystore $debugKeyStore -storepass android -alias androiddebugkey `
            -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Creating debug keystore failed' }
    }

    & $apksigner sign --ks $debugKeyStore --ks-pass 'pass:android' --key-pass 'pass:android' `
        --out $finalApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & $apksigner verify --verbose $finalApk
    if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
}

Write-Output "APK=$finalApk"
