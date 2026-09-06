import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class FunctionTrace {
 public static void main(String[]args)throws Exception {
  LaunchingConnector connector=Bootstrap.virtualMachineManager().defaultConnector();var options=connector.defaultArguments();
  options.get("options").setValue("-Xmx128m -cp "+args[0]);options.get("main").setValue("FunctionTraceTarget "+args[1]+" "+args[2]+" "+args[3]+" "+args[4]);
  VirtualMachine vm=connector.launch(options);MethodEntryRequest request=vm.eventRequestManager().createMethodEntryRequest();request.addClassFilter("io.johnsonlee.graphite.cypher.CypherFunctions");request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.enable();
  var calls=new ArrayList<Object>();boolean done=false;vm.resume();
  while(!done){EventSet events=vm.eventQueue().remove(30000);if(events==null)throw new IllegalStateException("trace timeout");for(Event event:events){
   if(event instanceof MethodEntryEvent entry && entry.method().name().equals("dispatch")){
    var arguments=entry.thread().frame(0).getArgumentValues();String function=((StringReference)arguments.get(0)).value();
    if(function.equals(args[3])){var frames=new ArrayList<String>();for(StackFrame frame:entry.thread().frames())frames.add(frame.location().declaringType().name()+"."+frame.location().method().name());calls.add(Map.of("function",function,"stack",frames));}
   } else if(event instanceof VMDeathEvent || event instanceof VMDisconnectEvent)done=true;
  }if(!done)events.resume();}
  Process child=vm.process();int status=child.waitFor();String stdout=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);String stderr=new String(child.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  var result=new LinkedHashMap<String,Object>();result.put("mode",args[2]);result.put("function",args[3]);result.put("cross",Boolean.parseBoolean(args[4]));result.put("calls",calls);result.put("exitCode",status);result.put("stdout",stdout);result.put("stderr",stderr);
  Files.writeString(Path.of(args[5]),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
 }
}
