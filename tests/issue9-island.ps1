$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$source=Get-Content "$root/app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java" -Raw
$method=[regex]::Match($source,'(?s)private static void placeOutTypeLabel\(ViewGroup container, View view, View anchor, int gap\) \{.*?\n    \}').Value
if(!$method){throw 'Missing production placement method'}
$out="$root/work/issue9/island-test"
New-Item -ItemType Directory -Force $out | Out-Null
$java=@"
public class IslandTest {
 static class View {
  static final int LAYOUT_DIRECTION_RTL=1; int l,t,r,b,mw=48,mh=48; float tx;
  int getLeft(){return l;} int getRight(){return r;} int getTop(){return t;}
  int getWidth(){return r-l;} int getHeight(){return b-t;}
  int getMeasuredWidth(){return mw;} int getMeasuredHeight(){return mh;}
  void layout(int a,int c,int d,int e){l=a;t=c;r=d;b=e;}
  float getTranslationX(){return tx;} void setTranslationX(float x){tx=x;}
 }
 static class ViewGroup extends View {
  int direction; int getLayoutDirection(){return direction;}
  int getPaddingLeft(){return 0;} int getPaddingRight(){return 0;}
  int getPaddingTop(){return 0;} int getPaddingBottom(){return 0;}
 }
 static class OutSignalView extends View {}
 static View icons=new View(); static int shift;
 static View iconContainerIn(ViewGroup c){return icons;}
 static int islandShiftPx(ViewGroup c){return shift;}
 $method
 public static void main(String[] args){
  int count=0;
  for(int rtl=0;rtl<2;rtl++)for(int island:new int[]{0,90}){
   ViewGroup c=new ViewGroup();c.layout(0,0,600,86);c.direction=rtl;
   View meter=new View();meter.layout(400,0,500,86);
   icons.layout(100,0,400,86);shift=island;
   OutSignalView signal=new OutSignalView();signal.mw=60;
   View label=new View();
   placeOutTypeLabel(c,signal,meter,6);
   placeOutTypeLabel(c,label,signal,6);
   if(rtl==0?label.r+6!=signal.l:signal.r+6!=label.l)throw new AssertionError("overlap rtl="+rtl+" island="+island);
   if(label.tx!=-island||signal.tx!=-island)throw new AssertionError("translation");
   View solo=new View();placeOutTypeLabel(c,solo,meter,6);
   if(rtl==0?solo.r!=icons.r-6:solo.l!=icons.l+6)throw new AssertionError("solo changed");
   count++;
  }
  System.out.println("PASS: "+count+" LTR/RTL island on/off cases; adjacent spacing, translation and solo label");
 }
}
"@
$java | Set-Content "$out/IslandTest.java" -Encoding utf8NoBOM
& javac -encoding UTF-8 -d $out "$out/IslandTest.java"
if($LASTEXITCODE){throw 'Compile failed'}
& java -Xmx64m -cp $out IslandTest
if($LASTEXITCODE){throw 'Placement regression failed'}
