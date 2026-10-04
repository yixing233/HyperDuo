# Executes the production pre-hide helper against a small model of this ROM's
# setChildVisible(false) branches. Not a replacement for device verification.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Get-Content (Join-Path $root 'app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java') -Raw
$method = [regex]::Match($source, '(?s)private static void prepareNativeTypeHide\(View view\) \{.*?\n    \}').Value
if (-not $method) { throw 'Production helper missing' }
$out = Join-Path $root 'work/issue9/animation-test'
New-Item -ItemType Directory -Force $out | Out-Null
$java = @'
public class AnimationTest {
    static class View {
        static final int VISIBLE=0, INVISIBLE=4, GONE=8;
        int visibility, clones, cleanups; boolean marked;
        int getVisibility() { return visibility; }
        void setVisibility(int v) { visibility=v; }
    }
    static boolean markCollapsed(View v) { boolean first=!v.marked; v.marked=true; return first; }
    METHOD
    // Observed order in MobileSignalAnimatorContainer.java: remove old fake,
    // early return for GONE/INVISIBLE, then clone a visible measured target.
    static void originalHide(View v) {
        v.cleanups++;
        if (v.visibility==View.GONE) return;
        if (v.visibility==View.INVISIBLE) { v.visibility=View.GONE; return; }
        v.clones++; v.visibility=View.GONE;
    }
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        View old=new View(); originalHide(old); check(old.clones==1);
        View v=new View(); prepareNativeTypeHide(v); originalHide(v);
        check(v.clones==0 && v.marked && v.visibility==View.GONE && v.cleanups==1);
        prepareNativeTypeHide(v); originalHide(v); check(v.clones==0 && v.cleanups==2);
        v.visibility=View.VISIBLE; prepareNativeTypeHide(v); originalHide(v); check(v.clones==0);
        View hidden=new View(); hidden.visibility=View.GONE; prepareNativeTypeHide(hidden);
        check(!hidden.marked);
        View invisible=new View(); invisible.visibility=View.INVISIBLE; prepareNativeTypeHide(invisible);
        originalHide(invisible); check(!invisible.marked && invisible.clones==0);
        System.out.println("PASS: 6 animation-branch cases; baseline model creates a clone, helper prevents it");
    }
}
'@
$java.Replace('METHOD', $method) | Set-Content (Join-Path $out 'AnimationTest.java') -Encoding utf8NoBOM
& javac -encoding UTF-8 -d $out (Join-Path $out 'AnimationTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -Xmx64m -cp $out AnimationTest
if ($LASTEXITCODE -ne 0) { throw 'Animation regression test failed' }
