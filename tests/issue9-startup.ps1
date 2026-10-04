# Control-flow regression test: execute production method bodies with view doubles.
# Does not emulate Android layout, binder flows, or MIUI transient animations.
param([switch]$Baseline)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'work\issue9\startup-test'
New-Item -ItemType Directory -Force $out | Out-Null
$path = 'app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java'
$source = if ($Baseline) { (git -C $root show "HEAD:$path") -join "`n" } else { Get-Content (Join-Path $root $path) -Raw }
function ExtractMethod($name) {
    $match = [regex]::Match($source, "private static void $name\([^)]*\)\s*\{")
    if (-not $match.Success) { throw "Missing method $name" }
    $start = $match.Index; $pos = $start + $match.Length; $depth = 1
    while ($depth -gt 0 -and $pos -lt $source.Length) {
        if ($source[$pos] -eq '{') { $depth++ }
        if ($source[$pos] -eq '}') { $depth-- }
        $pos++
    }
    if ($depth -ne 0) { throw 'Unbalanced method' }
    $source.Substring($start, $pos - $start)
}
$methods = (ExtractMethod 'foldHostContainer') + "`n" + (ExtractMethod 'foldAndSettle')
$java = @'
public class StartupTest {
    static class View { Object owner; }
    static class ViewGroup extends View { boolean slotsAlreadyFolded; boolean nativeVisible = true; int layouts; }
    static class Owner { Object icons; }
    static class Refl { static Object get(Object field, Object owner) { return ((Owner)owner).icons; } }
    static Object sStatusIconField;
    static boolean enabled = true;
    static int settles;
    static Object batteryContainerOf(View host) { return host.owner; }
    static void syncSlots(Object obj) {
        if (!(obj instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup)obj;
        if (enabled && !g.slotsAlreadyFolded) { g.slotsAlreadyFolded = true; g.layouts++; }
    }
    static void settle(ViewGroup g) { settles++; if (enabled) g.nativeVisible = false; }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    METHODS
    public static void main(String[] args) {
        View host = new View(); Owner owner = new Owner(); ViewGroup icons = new ViewGroup();
        host.owner = owner; owner.icons = icons; icons.slotsAlreadyFolded = true;
        foldHostContainer(host);
        check(!icons.nativeVisible, "startup must settle even when slots already folded");
        check(icons.layouts == 0, "must not rely on a new layout");
        int count = settles; foldHostContainer(host);
        check(settles == count + 1 && icons.layouts == 0, "repeated call settles without layout loop");
        enabled = false; icons.nativeVisible = true; foldHostContainer(host);
        check(icons.nativeVisible, "disabled state must stay native");
        enabled = true; icons.slotsAlreadyFolded = false; foldHostContainer(host);
        check(icons.layouts == 1 && !icons.nativeVisible, "new slots also settle");
        host.owner = null; foldHostContainer(host);
        host.owner = owner; owner.icons = null; foldHostContainer(host);
        owner.icons = new Object(); foldHostContainer(host);
        System.out.println("PASS: 7 startup control-flow cases (Android/MIUI behavior not simulated)");
    }
}
'@
$java.Replace('METHODS', $methods) | Set-Content (Join-Path $out 'StartupTest.java') -Encoding utf8NoBOM
& javac -encoding UTF-8 -d $out (Join-Path $out 'StartupTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $out StartupTest
if ($LASTEXITCODE -ne 0) { throw 'Regression test failed' }
