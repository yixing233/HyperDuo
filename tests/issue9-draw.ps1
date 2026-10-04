# Executes the production draw gate with a recording Chain, not an Android renderer.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Get-Content (Join-Path $root 'app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java') -Raw
$method = [regex]::Match($source, '(?s)private static Object drawNativeType\(XposedInterface.Chain chain\) throws Throwable \{.*?\n    \}').Value
if (-not $method) { throw 'Production draw gate missing' }
if ($source -notmatch 'hooked \+= group\(module, cl, 11\)' -or $source -notmatch 'case 11: return hookMobileTypeDraw') { throw 'Draw hook is not registered' }
$out = Join-Path $root 'work/issue9/draw-test'
New-Item -ItemType Directory -Force $out | Out-Null
$java = @'
public class DrawTest {
    static class TrioAppearance { boolean glyph, typeOutOfRing; }
    static class TrioConfig { static TrioAppearance a=new TrioAppearance(); static TrioAppearance appearance(){return a;} }
    static class XposedInterface { interface Chain { Object proceed() throws Throwable; } }
    static boolean sNativeTypeDrawLogged;
    static final int LOG_INFO=4;
    static boolean debugLog(){return true;}
    static int logs;
    static void log(int priority,String msg){logs++;}
    METHOD
    static class Chain implements XposedInterface.Chain {
        int calls; boolean fail;
        public Object proceed() throws Throwable {calls++; if(fail)throw new IllegalStateException("original"); return "native";}
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args) throws Throwable {
        for(boolean enabled:new boolean[]{false,true}) for(boolean out:new boolean[]{false,true}) {
            TrioConfig.a.glyph=enabled; TrioConfig.a.typeOutOfRing=out;
            Chain c=new Chain(); Object result=drawNativeType(c);
            check(c.calls==(enabled&&out?0:1)); check(enabled&&out?result==null:"native".equals(result));
        }
        Chain c=new Chain(); for(int i=0;i<100;i++)drawNativeType(c); check(c.calls==0 && logs==1);
        TrioConfig.a.glyph=false; drawNativeType(c); check(c.calls==1);
        TrioConfig.a.glyph=true; TrioConfig.a.typeOutOfRing=false; drawNativeType(c); check(c.calls==2);
        c.fail=true; boolean threw=false; try{drawNativeType(c);}catch(IllegalStateException e){threw=true;}
        check(threw); System.out.println("PASS: 8 draw-gate cases and hook registration check");
    }
}
'@
$java.Replace('METHOD', $method) | Set-Content (Join-Path $out 'DrawTest.java') -Encoding utf8NoBOM
& javac -encoding UTF-8 -d $out (Join-Path $out 'DrawTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -Xmx64m -cp $out DrawTest
if ($LASTEXITCODE -ne 0) { throw 'Draw gate regression test failed' }
