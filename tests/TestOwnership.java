import com.openai.pixivbatchbookmark.RecoveryStore;
import com.openai.pixivbatchbookmark.BatchGate;
import java.io.*;
import java.nio.file.Files;
import java.security.Permission;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
public class TestOwnership {
  static Object invoke(Object target,String name,Object...args)throws Exception {
    for(Method m:target.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
      try{return m.invoke(target,args);}catch(IllegalArgumentException mismatch){}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    throw new AssertionError("production API missing: "+name);
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static Map<String,String> snapshot(String input){Map<String,String> m=new HashMap<>();m.put("input",input);return m;}
  static void journal()throws Exception {
    File dir=Files.createTempDirectory("pixiv-shared-journal").toFile();RecoveryStore old=new RecoveryStore(dir),fresh=new RecoveryStore(new File(dir,"."));
    old.markSubmitted("71",Arrays.asList("10001"));
    CountDownLatch paused=new CountDownLatch(1),release=new CountDownLatch(1),newDone=new CountDownLatch(1);
    List<Throwable> errors=Collections.synchronizedList(new ArrayList<Throwable>());
    SecurityManager previous=System.getSecurityManager();
    System.setSecurityManager(new SecurityManager(){
      public void checkPermission(Permission permission){}
      public void checkWrite(String path){if(Thread.currentThread().getName().equals("old-cleanup")&&path.contains("pending-mutations")&&path.endsWith(".tmp")){
        paused.countDown();try{check(release.await(5,TimeUnit.SECONDS),"cleanup barrier timed out");}catch(InterruptedException e){throw new AssertionError(e);}
      }}
    });
    Thread a=new Thread(()->{try{old.markVerified("71","10001");}catch(Throwable e){errors.add(e);}},"old-cleanup");
    Thread b=new Thread(()->{try{fresh.markSubmitted("72",Arrays.asList("20001"));}catch(Throwable e){errors.add(e);}finally{newDone.countDown();}},"new-submit");
    try {a.start();check(paused.await(5,TimeUnit.SECONDS),"old cleanup did not reach write barrier");b.start();newDone.await(150,TimeUnit.MILLISECONDS);release.countDown();a.join(5000);b.join(5000);
      check(!a.isAlive()&&!b.isAlive(),"journal workers did not settle");check(errors.isEmpty(),"journal errors "+errors);
      check(fresh.pendingIds().equals(Arrays.asList("20001")),"old cleanup lost new durable submission: "+fresh.pendingIds());
    }finally{release.countDown();System.setSecurityManager(previous);}
  }
  static void snapshots()throws Exception {
    File dir=Files.createTempDirectory("pixiv-owner-state").toFile();RecoveryStore old=new RecoveryStore(dir),fresh=new RecoveryStore(dir);
    Object ownerA=invoke(old,"claimOwner"),ticketA=invoke(old,"captureSnapshot",ownerA,snapshot("10001"));
    check((Boolean)invoke(old,"commitSnapshot",ticketA),"first owner snapshot commit");
    invoke(old,"retireOwner",ownerA);Object ownerB=invoke(fresh,"claimOwner");
    Object earlier=invoke(fresh,"captureSnapshot",ownerB,snapshot("20001")),latest=invoke(fresh,"captureSnapshot",ownerB,snapshot("30001"));
    check((Boolean)invoke(fresh,"commitSnapshot",latest),"latest snapshot commit");
    check(!(Boolean)invoke(old,"commitSnapshot",ticketA),"retired executor write accepted");
    check(!(Boolean)invoke(fresh,"commitSnapshot",earlier),"old bridge-captured revision accepted");
    check(fresh.loadSnapshot().get("input").equals("30001"),"newest valid content lost");
  }
  static void exportIdentity()throws Exception {
    File dir=Files.createTempDirectory("pixiv-export-generation").toFile();RecoveryStore old=new RecoveryStore(dir),fresh=new RecoveryStore(dir);
    Object ownerA=invoke(old,"claimOwner");String leaseA=(String)invoke(old,"claimFileOperation",ownerA);
    Object a=invoke(old,"prepareOwnedExport",leaseA,1002,"snapshot A");String idA=(String)invoke(a,"getId");
    invoke(old,"beginProviderIo",leaseA);invoke(old,"retireOwner",ownerA);Object ownerB=invoke(fresh,"claimOwner");
    boolean blocked=false;try{invoke(fresh,"claimFileOperation",ownerB);}catch(IOException expected){blocked=true;}check(blocked,"replacement admitted while old provider I/O owned lease");
    invoke(old,"releaseFileOperation",leaseA);String leaseB=(String)invoke(fresh,"claimFileOperation",ownerB);
    check((Boolean)invoke(fresh,"clearOwnedExport",leaseB,idA,1002),"finish exact old export");
    invoke(fresh,"releaseFileOperation",leaseB);leaseB=(String)invoke(fresh,"claimFileOperation",ownerB);
    Object b=invoke(fresh,"prepareOwnedExport",leaseB,1002,"snapshot B");String idB=(String)invoke(b,"getId");
    check(!idA.equals(idB),"export identity reused");
    boolean staleRefused=false;try{staleRefused=!(Boolean)invoke(old,"clearOwnedExport",leaseA,idA,1002);}catch(IOException expected){staleRefused=true;}
    check(staleRefused,"old completion/cancel deleted replacement export");
    check(invoke(fresh,"readExport",idB,1002).equals("snapshot B"),"new export content missing");
    boolean mismatch=false;try{invoke(old,"readExport",idA,1002);}catch(IOException expected){mismatch=true;}check(mismatch,"stale export read accepted");
    invoke(fresh,"releaseFileOperation",leaseB);
  }
  static void sameUuidCancellation()throws Exception {
    File dir=Files.createTempDirectory("pixiv-same-uuid-cancel").toFile();RecoveryStore old=new RecoveryStore(dir),fresh=new RecoveryStore(dir);
    Object a=invoke(old,"claimOwner");String stale=(String)invoke(old,"claimFileOperation",a);
    String id=(String)invoke(invoke(old,"prepareOwnedExport",stale,1002,"same durable snapshot"),"getId");
    invoke(old,"retireOwner",a);Object b=invoke(fresh,"claimOwner");String current=(String)invoke(fresh,"claimFileOperation",b);
    check(invoke(invoke(fresh,"pendingExport",current,1002),"getId").equals(id),"replacement must resume exact same UUID");
    boolean refused=false;try{invoke(old,"clearOwnedExport",stale,id,1002);}catch(IOException expected){refused=true;}check(refused,"old queued cancellation accepted against resumed same UUID");
    check(invoke(fresh,"readExport",id,1002).equals("same durable snapshot"),"stale cancellation deleted resumed content");
    invoke(fresh,"beginProviderIo",current);invoke(fresh,"retireOwner",b);Object c=invoke(old,"claimOwner");
    boolean held=false;try{invoke(old,"claimFileOperation",c);}catch(IOException expected){held=true;}check(held,"admitted cleanup lease released on retire/dismiss");
    try {check((Boolean)invoke(fresh,"clearOwnedExport",current,id,1002),"admitted cleanup cannot finish after retirement");}
    finally{invoke(fresh,"releaseFileOperation",current);}
    String after=(String)invoke(old,"claimFileOperation",c);invoke(old,"beginProviderIo",after);
    try {throw new IOException("simulated worker failure");}catch(IOException expected){}finally{invoke(old,"releaseFileOperation",after);}
    String recovered=(String)invoke(old,"claimFileOperation",c);invoke(old,"releaseFileOperation",recovered);
  }
  static void pageGeneration()throws Exception {
    BatchGate gate=new BatchGate(100);long old=gate.begin("login","0","https://www.pixiv.net/");gate.cancel();long current=gate.begin("login","0","https://www.pixiv.net/");
    check(!(Boolean)invoke(gate,"pageReady",old,"https://www.pixiv.net/"),"old same-home completion admitted");
    check(!(Boolean)invoke(gate,"acceptsPageError",old,"https://www.pixiv.net/"),"old same-home error admitted");
    check((Boolean)invoke(gate,"pageReady",current,"https://www.pixiv.net/"),"current completion consumed by old generation");
    check((Boolean)invoke(gate,"acceptsPageError",current,"https://www.pixiv.net/"),"current page error ignored");
    check(!(Boolean)invoke(gate,"pageReady",current,"https://www.pixiv.net.evil/"),"wrong origin admitted");
  }
  public static void main(String[]args)throws Exception {
    int failures=0;String[] names={"cross-instance journal transaction preserves new submission","retired/older snapshot commits rejected","export identity and runtime lease reject stale operations","same-home page/error callbacks bound to generation","same-UUID cancellation requires exact lease and releases in finally"};
    for(int i=0;i<5;i++)try{if(i==0)journal();if(i==1)snapshots();if(i==2)exportIdentity();if(i==3)pageGeneration();if(i==4)sameUuidCancellation();System.out.println("PASS "+names[i]);}catch(Throwable e){failures++;System.out.println("FAIL "+names[i]+": "+e);}
    if(failures>0)throw new AssertionError(failures+" ownership failures");
  }
}
