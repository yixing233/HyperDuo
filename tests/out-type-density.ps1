$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$source=Get-Content "$root/app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java" -Raw
$method=[regex]::Match($source,'(?s)private static float outTypeSizePx\(View container\) \{.*?\n    \}').Value
if(!$method){throw 'Missing production conversion'}
if($source -notmatch 'final float size = outTypeSizePx\(container\)' -or $source -notmatch 'label.getTextSize\(\) != outTypeSizePx\(container\)'){throw 'Draw and refresh must use same PX conversion'}
$out="$root/work/issue9/density-test"; New-Item -ItemType Directory -Force $out | Out-Null
@"
public class DensityTest {
 static class Metrics {float density;}
 static class Resources {Metrics m=new Metrics();Metrics getDisplayMetrics(){return m;}}
 static class View {Resources r=new Resources();Resources getResources(){return r;}}
 static class Appearance {int outTypeSize;}
 static class TrioConfig {static Appearance a=new Appearance();static Appearance appearance(){return a;}}
 $method
 public static void main(String[] args){
  int count=0;View v=new View();
  for(float density:new float[]{1f,1.5f,2f,3f,4f})for(int dp:new int[]{6,11,12,22}){
   v.r.m.density=density;TrioConfig.a.outTypeSize=dp;
   if(outTypeSizePx(v)!=dp*density)throw new AssertionError("wrong conversion");count++;
  }
  v.r.m.density=3;TrioConfig.a.outTypeSize=12;if(outTypeSizePx(v)!=36)throw new AssertionError("device regression");
  TrioConfig.a.outTypeSize=22;if(outTypeSizePx(v)!=66)throw new AssertionError("slider maximum");
  System.out.println("PASS: "+count+" density/size cases plus device and slider-maximum regressions; both production call sites checked");
 }
}
"@ | Set-Content "$out/DensityTest.java" -Encoding utf8NoBOM
javac -d $out "$out/DensityTest.java"; if($LASTEXITCODE){throw 'Compile failed'}
java -Xmx64m -cp $out DensityTest; if($LASTEXITCODE){throw 'Test failed'}
