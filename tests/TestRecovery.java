import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.lang.reflect.*;
public class TestRecovery {
  static Class<?> type;
  static String exportOperation;
  static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception {
    if ((name.equals("readExport") || name.equals("clearExport")) && args.length == 1) {
      types = new Class<?>[]{String.class,int.class}; args = new Object[]{exportOperation,args[0]};
    }
    try {
      Object value=type.getMethod(name,types).invoke(target,args);
      if(name.equals("prepareExport") && value!=null)exportOperation=(String)value.getClass().getMethod("getId").invoke(value);
      return value;
    }catch(InvocationTargetException e){throw (Exception)e.getCause();}
  }
  static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
  static void denied(RunnableWithException f)throws Exception {try{f.run();throw new AssertionError("expected rejection");}catch(IOException|IllegalArgumentException expected){}}
  interface RunnableWithException{void run()throws Exception;}
  static byte[] withoutProperty(byte[] frame,String key)throws Exception {
    DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));
    int version=in.readInt();String name=in.readUTF();int size=in.readInt();byte[] oldDigest=new byte[32];in.readFully(oldDigest);
    byte[] bytes=new byte[size];in.readFully(bytes);Properties p=new Properties();p.load(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8));p.remove(key);
    ByteArrayOutputStream body=new ByteArrayOutputStream();Writer writer=new OutputStreamWriter(body,StandardCharsets.UTF_8);p.store(writer,null);writer.flush();byte[] payload=body.toByteArray();
    ByteArrayOutputStream result=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(result);out.writeInt(version);out.writeUTF(name);out.writeInt(payload.length);
    out.write(java.security.MessageDigest.getInstance("SHA-256").digest(payload));out.write(payload);out.flush();return result.toByteArray();
  }
  public static void main(String[]args)throws Exception {
    try{type=Class.forName("com.openai.pixivbatchbookmark.RecoveryStore");}catch(ClassNotFoundException e){throw new AssertionError("durable recovery/export core required");}
    File dir=Files.createTempDirectory("pixiv-state-test").toFile();
    Object store=type.getConstructor(File.class).newInstance(dir);
    Class<?>[] map={Map.class}, none={}, list={String.class,List.class};
    Map<String,String> snapshot=new HashMap<>();snapshot.put("input","97003966\n103816477");snapshot.put("results","some results");snapshot.put("active","true");snapshot.put("index","1");
    call(store,"saveSnapshot",map,snapshot);Object recreated=type.getConstructor(File.class).newInstance(dir);
    Map<?,?> recovered=(Map<?,?>)call(recreated,"loadSnapshot",none);eq(recovered.get("input"),snapshot.get("input"));eq(recovered.get("active"),"false");eq(recovered.get("interrupted"),"true");
    System.out.println("PASS recovery restores content stopped and never replays active task");
    Map<String,String> secret=new HashMap<>();secret.put("csrf","secret");denied(()->call(store,"saveSnapshot",map,secret));eq(((Map<?,?>)call(store,"loadSnapshot",none)).get("input"),snapshot.get("input"));
    System.out.println("PASS snapshots reject session secrets without destroying previous state");
    call(store,"markSubmitted",list,"71",Arrays.asList("97003966","103816477"));
    eq(call(recreated,"pendingIds",none),Arrays.asList("97003966","103816477"));
    call(store,"markVerified",new Class<?>[]{String.class,String.class},"70","97003966");eq(((List<?>)call(store,"pendingIds",none)).size(),2);
    call(store,"markVerified",new Class<?>[]{String.class,String.class},"71","97003966");eq(call(recreated,"pendingIds",none),Arrays.asList("103816477"));
    System.out.println("PASS submitted mutations survive process death and stale tokens cannot clear them");
    call(store,"prepareExport",new Class<?>[]{int.class,String.class},1002,"exact snapshot\n");
    denied(()->call(store,"prepareExport",new Class<?>[]{int.class,String.class},1003,"second"));
    denied(()->call(recreated,"readExport",new Class<?>[]{int.class},1003));
    eq(call(recreated,"readExport",new Class<?>[]{int.class},1002),"exact snapshot\n");
    call(recreated,"clearExport",new Class<?>[]{int.class},1003);eq(call(recreated,"readExport",new Class<?>[]{int.class},1002),"exact snapshot\n");
    call(recreated,"clearExport",new Class<?>[]{int.class},1002);denied(()->call(store,"readExport",new Class<?>[]{int.class},1002));
    denied(()->call(store,"prepareExport",new Class<?>[]{int.class,String.class},1002,""));
    System.out.println("PASS export snapshot survives recreation, excludes concurrent/mismatched saves and refuses blank success");
    File damaged=Files.createTempDirectory("pixiv-damaged-state").toFile();
    Object independent=type.getConstructor(File.class).newInstance(damaged);
    call(independent,"markSubmitted",list,"81",Arrays.asList("97003966"));
    Files.write(new File(damaged,"task-state").toPath(), "input=bad\\uXYZ\n".getBytes(StandardCharsets.UTF_8));
    Object restored;
    try { restored=call(independent,"restoreStopped",none); }
    catch(NoSuchMethodException e) { throw new AssertionError("independent snapshot/journal restore decision required"); }
    eq(restored.getClass().getMethod("getPendingIds").invoke(restored),Arrays.asList("97003966"));
    if(((List<?>)restored.getClass().getMethod("getErrors").invoke(restored)).isEmpty())throw new AssertionError("corrupt state must report error");
    call(independent,"saveSnapshot",map,snapshot);
    if(damaged.listFiles((d,n)->n.startsWith("task-state.invalid")).length==0)throw new AssertionError("invalid snapshot must be retained, not default-overwritten");
    System.out.println("PASS corrupt UI snapshot cannot suppress valid journal and invalid bytes are preserved");
    File integrity=Files.createTempDirectory("pixiv-integrity").toFile();Object protectedStore=type.getConstructor(File.class).newInstance(integrity);
    call(protectedStore,"saveSnapshot",map,snapshot);
    File ui=new File(integrity,"task-state"), export=new File(integrity,"pending-export");
    byte[] original=Files.readAllBytes(ui.toPath());
    Files.write(ui.toPath(),Arrays.copyOf(original,original.length-5));
    Map<?,?> complete=(Map<?,?>)call(protectedStore,"loadSnapshot",none);eq(complete.get("input"),snapshot.get("input"));
    eq(complete.get("results"),"some results");
    // Even a valid envelope cannot silently supply defaults for a missing required field.
    Files.write(ui.toPath(),withoutProperty(original,"results"));
    Map<?,?> schemaRecovered=(Map<?,?>)call(protectedStore,"loadSnapshot",none);
    eq(schemaRecovered.get("results"),"some results");eq(schemaRecovered.get("interrupted"),"true");
    System.out.println("PASS incomplete but self-consistent snapshot schema falls back to verified complete UI state");
    call(protectedStore,"prepareExport",new Class<?>[]{int.class,String.class},1002,"full first part and complete remaining results");
    byte[] full=Files.readAllBytes(export.toPath());
    Files.write(export.toPath(),Arrays.copyOf(full,full.length-10));
    denied(()->call(protectedStore,"readExport",new Class<?>[]{int.class},1002));
    // Corrupt both copies: a valid-looking content prefix may never be exported as complete.
    byte[] shortened=Arrays.copyOf(full,full.length-10);
    Files.write(export.toPath(),shortened);Files.write(new File(integrity,"pending-export.bak").toPath(),shortened);
    denied(()->call(protectedStore,"readExport",new Class<?>[]{int.class},1002));
    // An older verified same-request-code export must never replace a corrupt new operation.
    Files.write(new File(integrity,"pending-export.bak").toPath(),full);
    call(protectedStore,"prepareExport",new Class<?>[]{int.class,String.class},1002,"new operation exact content");
    byte[] changed=Files.readAllBytes(export.toPath());changed[changed.length-2]^=1;Files.write(export.toPath(),changed);
    denied(()->call(protectedStore,"readExport",new Class<?>[]{int.class},1002));
    if(integrity.listFiles((d,n)->n.startsWith("pending-export.invalid")).length<2)throw new AssertionError("corrupt export bytes must be quarantined");
    System.out.println("PASS digest rejects same-length alteration and never substitutes older same-type export");
    System.out.println("PASS versioned length/digest validation recovers UI backup and rejects incomplete export without substitution");
    File missing=Files.createTempDirectory("pixiv-missing-primary").toFile();Object missingStore=type.getConstructor(File.class).newInstance(missing);
    call(missingStore,"saveSnapshot",map,snapshot);
    Files.delete(new File(missing,"task-state").toPath());
    Map<?,?> backupRestored=(Map<?,?>)call(missingStore,"loadSnapshot",none);
    eq(backupRestored.get("input"),snapshot.get("input"));eq(backupRestored.get("results"),snapshot.get("results"));
    eq(backupRestored.get("active"),"false");eq(backupRestored.get("interrupted"),"true");
    // Model Activity's ordinary stopped checkpoint, then another missing-primary boundary.
    Map<String,String> stopped=new HashMap<>();for(Map.Entry<?,?> entry:backupRestored.entrySet())if(!entry.getKey().equals("interrupted"))stopped.put((String)entry.getKey(),(String)entry.getValue());
    call(missingStore,"saveSnapshot",map,stopped);Files.delete(new File(missing,"task-state").toPath());
    eq(((Map<?,?>)call(missingStore,"loadSnapshot",none)).get("input"),snapshot.get("input"));
    System.out.println("PASS missing UI primary restores verified backup stopped and later checkpoint preserves saved content");
    File invalidBackup=Files.createTempDirectory("pixiv-invalid-backup").toFile();Object invalidStore=type.getConstructor(File.class).newInstance(invalidBackup);
    Files.write(new File(invalidBackup,"task-state.bak").toPath(),"not a complete snapshot".getBytes(StandardCharsets.UTF_8));
    denied(()->call(invalidStore,"loadSnapshot",none));
    if(invalidBackup.listFiles((d,n)->n.startsWith("task-state.bak.invalid")).length!=1)throw new AssertionError("invalid missing-primary backup must be preserved and reported");
    System.out.println("PASS missing-primary invalid backup is rejected and quarantined rather than trusted");
    byte[] exactly=new byte[2*1024*1024];Arrays.fill(exactly,(byte)'a');
    eq(((String)call(null,"readText",new Class<?>[]{InputStream.class},new ByteArrayInputStream(exactly))).length(),exactly.length);
    denied(()->call(null,"readText",new Class<?>[]{InputStream.class},new ByteArrayInputStream(new byte[exactly.length+1])));
    eq(call(null,"readText",new Class<?>[]{InputStream.class},new ByteArrayInputStream("\ufeff97003966".getBytes(StandardCharsets.UTF_8))),"\ufeff97003966");
    System.out.println("PASS TXT byte size capped at 2 MiB with exact boundary accepted");
  }
}
