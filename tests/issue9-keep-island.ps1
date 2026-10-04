$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$source=Get-Content "$root/app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java" -Raw
$method=[regex]::Match($source,'(?s)private static Object keepBatteryDuringIsland\(XposedInterface.Chain chain\) throws Throwable \{.*?\n    \}').Value
$replay=[regex]::Match($source,'(?s)private static void replayBatteryIslandRequests\(\) \{.*?\n    \}').Value
if(!$method -or !$replay){throw 'Missing production method'}
$out="$root/work/issue9/keep-island-test"; New-Item -ItemType Directory -Force $out | Out-Null
@"
import java.util.*;
public class KeepIslandTest {
 static Map<Object,boolean[]> BATTERY_ISLAND_REQUESTS=new WeakHashMap<>();
 static class Refl {
  static java.lang.reflect.Field field(Class<?> c,String s){try{return c.getDeclaredField(s);}catch(Exception e){throw new AssertionError(e);}}
  static void set(java.lang.reflect.Field f,Object o,Object v){try{f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
  static Object callArgs(Object o,String name,Class<?>[] types,Object[] args){
   Chain c=(Chain)o;c.request=(Boolean)args[0];c.trigger=(Boolean)args[1];
   try{return keepBatteryDuringIsland(c);}catch(Throwable t){throw new AssertionError(t);}
  }
 }
 static class TrioConfig { boolean enabled; static TrioConfig c=new TrioConfig(); static TrioConfig get(){return c;} }
 static class XposedInterface { interface Chain {Object getArg(int i);Object getThisObject();Object proceed(Object[] a)throws Throwable;} }
 static class Chain implements XposedInterface.Chain {
  boolean request,trigger,mStoreIsAddBatteryIsland; Object[] forwarded; int calls;
  public Object getArg(int i){return i==0?request:trigger;}
  public Object getThisObject(){return this;}
  public Object proceed(Object[] a){forwarded=a;mStoreIsAddBatteryIsland=(Boolean)a[0];calls++;return "result";}
 }
 $method
 $replay
 public static void main(String[] args)throws Throwable {
  int n=0;
  for(boolean enabled:new boolean[]{false,true})for(boolean request:new boolean[]{false,true})for(boolean trigger:new boolean[]{false,true}){
   TrioConfig.c.enabled=enabled;Chain c=new Chain();c.request=request;c.trigger=trigger;
   Object result=keepBatteryDuringIsland(c);
   if(!"result".equals(result)||c.calls!=1||!c.forwarded[0].equals(request&&!enabled)||!c.forwarded[1].equals(trigger))throw new AssertionError("forwarding");
   boolean[] saved=BATTERY_ISLAND_REQUESTS.get(c);
   if(saved[0]!=request||saved[1]!=trigger||c.mStoreIsAddBatteryIsland!=request)throw new AssertionError("request lost");n++;
  }
  BATTERY_ISLAND_REQUESTS.clear();Chain c=new Chain();c.request=true;c.trigger=true;
  TrioConfig.c.enabled=true;keepBatteryDuringIsland(c);
  TrioConfig.c.enabled=false;replayBatteryIslandRequests();
  if(!c.forwarded[0].equals(true)||!c.mStoreIsAddBatteryIsland)throw new AssertionError("disable replay");
  TrioConfig.c.enabled=true;replayBatteryIslandRequests();
  if(!c.forwarded[0].equals(false)||!c.mStoreIsAddBatteryIsland)throw new AssertionError("enable replay");
  c.request=false;keepBatteryDuringIsland(c);TrioConfig.c.enabled=false;replayBatteryIslandRequests();
  if(!c.forwarded[0].equals(false)||c.mStoreIsAddBatteryIsland)throw new AssertionError("island ended");
  n+=3;
  System.out.println("PASS: "+n+" enabled/request/trigger cases; original called once and actual request retained");
 }
}
"@ | Set-Content "$out/KeepIslandTest.java" -Encoding utf8NoBOM
javac -d $out "$out/KeepIslandTest.java"; if($LASTEXITCODE){throw 'Compile failed'}
java -Xmx64m -cp $out KeepIslandTest; if($LASTEXITCODE){throw 'Test failed'}
