<#
.SYNOPSIS
    Verifies cRZBaseString against SC4's cRZString vtable, then runs its
    behaviour self-check.

.DESCRIPTION
    MSVC lays out same-name virtual overloads in reverse declaration order, so
    a well-meaning tidy-up of the overload pairs in cIGZString.h silently
    swaps vtable slots. A swapped slot still compiles and still calls a real
    function, so nothing else catches it: the game just calls the wrong
    overload with the wrong arguments.

    This script compiles gzcom-dll/src/cRZBaseString.cpp for x86, extracts the
    emitted vtable from the assembly listing, and compares it slot by slot
    with the layout read out of cRZString_vftable_00a80810 in
    SimCity 4.exe 1.1.641. It then builds and runs
    gzcom-dll/tests/cRZBaseStringSelfCheck.cpp.

.EXAMPLE
    pwsh -File scripts/check-cRZBaseString.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# Expected vtable, slot 0 upward. Decorated names, because they encode the
# exact overload in each slot. Derived from the Windows game vtable; see the
# slot table in gzcom-dll/include/cIGZString.h.
$expected = @(
    '?QueryInterface@cRZBaseString@@UAE_NIPAPAX@Z'
    '?AddRef@cRZBaseString@@UAEIXZ'
    '?Release@cRZBaseString@@UAEIXZ'
    '?FromChar@cRZBaseString@@UAEXPBDI@Z'
    '?FromChar@cRZBaseString@@UAEXPBD@Z'
    '?ToChar@cRZBaseString@@UBEPBDXZ'
    '?Data@cRZBaseString@@UBEPBDXZ'
    '?Strlen@cRZBaseString@@UBEIXZ'
    '?IsEqual@cRZBaseString@@UBE_NPBDI_N@Z'
    '?IsEqual@cRZBaseString@@UBE_NABVcIGZString@@_N@Z'
    '?IsEqual@cRZBaseString@@UBE_NPBVcIGZString@@_N@Z'
    '?CompareTo@cRZBaseString@@UBEHPBDI_N@Z'
    '?CompareTo@cRZBaseString@@UBEHABVcIGZString@@_N@Z'
    '??4cRZBaseString@@UAEAAVcIGZString@@ABV1@@Z'
    '?Copy@cRZBaseString@@UAEXABVcIGZString@@@Z'
    '?Resize@cRZBaseString@@UAEXI@Z'
    '?Append@cRZBaseString@@UAEPAVcIGZString@@ABV2@@Z'
    '?Append@cRZBaseString@@UAEPAVcIGZString@@PBDI@Z'
    '?Insert@cRZBaseString@@UAEPAVcIGZString@@IABV2@@Z'
    '?Insert@cRZBaseString@@UAEPAVcIGZString@@IPBDI@Z'
    '?Replace@cRZBaseString@@UAEPAVcIGZString@@IABV2@@Z'
    '?Replace@cRZBaseString@@UAEPAVcIGZString@@IPBDI@Z'
    '?Erase@cRZBaseString@@UAEPAVcIGZString@@II@Z'
    '?Find@cRZBaseString@@UBEHABVcIGZString@@I_N@Z'
    '?Find@cRZBaseString@@UBEHPBDI_N@Z'
    '?RFind@cRZBaseString@@UBEHABVcIGZString@@I_N@Z'
    '?RFind@cRZBaseString@@UBEHPBDI_N@Z'
    '?Sprintf@cRZBaseString@@UAAPAVcIGZString@@PBDZZ'
    '??_EcRZBaseString@@UAEPAXI@Z'
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $repoRoot 'gzcom-dll\src\cRZBaseString.cpp'
$selfCheck = Join-Path $repoRoot 'gzcom-dll\tests\cRZBaseStringSelfCheck.cpp'
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('cRZBaseStringCheck-' + [System.Guid]::NewGuid().ToString('N'))

foreach ($file in @($source, $selfCheck)) {
    if (-not (Test-Path $file)) { throw "Missing $file" }
}

# Locate a Visual Studio x86 developer environment.
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { throw "vswhere.exe not found. Install Visual Studio with the C++ workload." }
$vsRoot = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsRoot) { throw 'No Visual Studio installation with the C++ tools was found.' }
$vcvarsall = Join-Path $vsRoot 'VC\Auxiliary\Build\vcvarsall.bat'
if (-not (Test-Path $vcvarsall)) { throw "vcvarsall.bat not found under $vsRoot" }

New-Item -ItemType Directory -Path $work | Out-Null
try {
    # /FAcs writes an assembly listing that contains the emitted vtable.
    $build = cmd /c "call `"$vcvarsall`" x86 >nul 2>&1 && cd /d `"$work`" && cl /nologo /c /std:c++20 /W3 /FAcs /Favtable.asm /Fovtable.obj `"$source`" 2>&1"
    if ($LASTEXITCODE -ne 0) { $build; throw 'Compiling cRZBaseString.cpp failed.' }

    $listing = Get-Content (Join-Path $work 'vtable.asm')
    $start = ($listing | Select-String -Pattern '^\?\?_7cRZBaseString@@6B@ DD' | Select-Object -First 1).LineNumber
    if (-not $start) { throw 'No cRZBaseString vtable in the assembly listing.' }

    # LineNumber is 1-based, so $listing[$start] is the line after the vtable
    # label. That label's own entry is the RTTI locator, not a method, so the
    # first collected entry is already slot 0.
    $actual = @()
    foreach ($line in $listing[$start..($listing.Count - 1)]) {
        if ($line -notmatch 'DD\s+FLAT:(\S+)') { break }
        $actual += $Matches[1]
    }

    $failures = 0
    for ($slot = 0; $slot -lt $expected.Count; $slot++) {
        $got = if ($slot -lt $actual.Count) { $actual[$slot] } else { '<missing>' }
        if ($got -ne $expected[$slot]) {
            Write-Host ("slot {0}: expected {1}" -f $slot, $expected[$slot]) -ForegroundColor Red
            Write-Host ("slot {0}:      got {1}" -f $slot, $got) -ForegroundColor Red
            $failures++
        }
    }
    if ($actual.Count -gt $expected.Count) {
        Write-Host ("vtable has {0} extra slot(s); the game's table ends at slot {1}." -f ($actual.Count - $expected.Count), ($expected.Count - 1)) -ForegroundColor Red
        $failures++
    }

    if ($failures -gt 0) {
        Write-Host ''
        Write-Host 'Vtable order does not match the game. Check the declaration order in' -ForegroundColor Red
        Write-Host 'gzcom-dll/include/cIGZString.h: overload pairs are declared in the' -ForegroundColor Red
        Write-Host 'reverse of their slot order, because MSVC reverses them.' -ForegroundColor Red
        exit 1
    }
    Write-Host ("vtable order matches the game in all {0} slots" -f $expected.Count) -ForegroundColor Green

    $build = cmd /c "call `"$vcvarsall`" x86 >nul 2>&1 && cd /d `"$work`" && cl /nologo /std:c++20 /W3 /EHsc /Feselfcheck.exe `"$selfCheck`" `"$source`" 2>&1"
    if ($LASTEXITCODE -ne 0) { $build; throw 'Compiling the self-check failed.' }

    & (Join-Path $work 'selfcheck.exe')
    if ($LASTEXITCODE -ne 0) { throw "Self-check failed with exit code $LASTEXITCODE." }
}
finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
