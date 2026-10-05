$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$s=Get-Content "$root/app/src/main/java/io/github/yixing233/hyperduo/WifiPresence.java" -Raw
$m=[regex]::Match($s,'(?s)static boolean resolve\(boolean iconVisible, int cachedLevel\) \{.*?\n    \}').Value
if(!$m){throw 'Production resolver missing'}
$out="$root/work/issue13/tests"; New-Item -ItemType Directory -Force $out | Out-Null
@"
public class WifiTest {
 static Boolean connected;
 $m
 public static void main(String[] args){
  int count=0;
  for(Boolean live:new Boolean[]{null,false,true})for(boolean icon:new boolean[]{false,true})for(int level:new int[]{-1,0,4}){
   connected=live;boolean want=live==null?(icon||level>=0):live;
   if(resolve(icon,level)!=want)throw new AssertionError("stale icon overrides live state");count++;
  }
  connected=false;if(resolve(true,4))throw new AssertionError("disabled wifi stale cache");
  connected=true;if(!resolve(false,-1))throw new AssertionError("reconnected wifi");
  connected=false;if(resolve(true,4))throw new AssertionError("disconnected again");
  System.out.println("PASS: "+count+" live/icon/cache cases and 3 reconnect transitions");
 }
}
"@ | Set-Content "$out/WifiTest.java" -Encoding utf8NoBOM
javac -d $out "$out/WifiTest.java"; if($LASTEXITCODE){throw 'Compilation failed'}
java -Xmx64m -cp $out WifiTest; if($LASTEXITCODE){throw 'Test failed'}
