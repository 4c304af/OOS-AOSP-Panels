package dev.oos.nativepanels;

import static dev.oos.nativepanels.TestDisplay.*;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.List;

public class TestGesture {
 static <T> T alloc(Class<T> c)throws Exception{
  Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return c.cast(((sun.misc.Unsafe)f.get(null)).allocateInstance(c));
 }
 public static class F extends android.app.Fragment {S scrollState=new S();public boolean editing(){return false;}}
 public static class S{int offset=80; public int getValue(){return offset;}public float dispatchRawDelta(float dy){offset+=(int)dy;return dy;}}
 public static class Host extends View {public Host semantics(){return this;}}
 public static class Value {float n;Value(float n){this.n=n;}public Object invoke(){return n;}}
 public static class Node {
  public float left=20,top=120,right=900,bottom=700;
  float value=30,max=320;
  public boolean contains(Object k){return true;}
  public Node get(Object k){return this;}
  public Value value(){return new Value(value);}public Value max(){return new Value(max);}
  public boolean reverse(){return false;}
  public Node node(){return this;}
 }
 public static Node node=new Node();
 public static List<Node> all(Host h, boolean a, boolean b){return List.of(node);}
 public static void main(String[] args)throws Exception{
  PanelRuntime runtime=alloc(PanelRuntime.class);CombinedQs qs=alloc(CombinedQs.class);
  ShadeLayout layout=alloc(ShadeLayout.class);ShadeDecor target=alloc(ShadeDecor.class);CombinedTouch touch=new CombinedTouch(runtime);
  NotificationStackScrim n=new NotificationStackScrim();F fragment=new F();ViewGroup window=new ViewGroup();window.w=2000;window.h=900;
  FrameLayout frame=new FrameLayout();frame.root=window;frame.parent=window;frame.w=950;frame.h=900;frame.id=7;frame.context.res.config.orientation=2;
  Host host=new Host();host.parent=frame;host.root=window;
  set(runtime,"combinedQs",qs);set(runtime,"settingsLaunch",new CombinedSettingsLaunch(runtime));set(qs,"layout",layout);
  set(layout,"touch",touch);set(layout,"runtime",runtime);set(layout,"notificationScrim",n);set(layout,"decor",new WeakReference<>(target));set(layout,"selected",new WeakReference<>(fragment));
  set(layout,"editing",F.class.getDeclaredMethod("editing"));set(layout,"open",true);set(target,"frame",frame);set(target,"context",frame.context);set(target,"layout",layout);
  set(target,"clock",new TextClock());set(target,"battery",new TextView());set(touch,"host",host);set(touch,"scrollAxis","vertical");
  set(touch,"all",TestGesture.class.getMethod("all",Host.class,boolean.class,boolean.class));set(touch,"semantics",Host.class.getMethod("semantics"));set(touch,"bounds",Node.class.getMethod("node"));set(touch,"config",Node.class.getMethod("node"));set(touch,"getProperty",Node.class.getMethod("get",Object.class));set(touch,"contains",Node.class.getMethod("contains",Object.class));set(touch,"scrollValue",Node.class.getMethod("value"));set(touch,"scrollMax",Node.class.getMethod("max"));set(touch,"scrollReverse",Node.class.getMethod("reverse"));set(touch,"invokeValue",Value.class.getMethod("invoke"));for(String name:List.of("left","right","top","bottom"))set(touch,name,Node.class.getField(name));
  Method prepare=CombinedTouch.class.getDeclaredMethod("prepareSplit",ShadeDecor.class,View.class,MotionEvent.class);prepare.setAccessible(true);
  Method decide=CombinedTouch.class.getDeclaredMethod("decideSplitDirection",float.class,float.class,int.class);decide.setAccessible(true);
  MotionEvent e=new MotionEvent();e.x=400;e.y=300;prepare.invoke(touch,target,window,e);expect(field(touch,"scrollHost").get(touch)==host,"native left pane scroll range");
  decide.invoke(touch,0f,-50f,12);expect(field(touch,"committed").getBoolean(touch),"upward drag stays in QS");touch.cancelSplitGesture();
  node.value=node.max;prepare.invoke(touch,target,window,e);decide.invoke(touch,0f,-50f,12);expect(field(touch,"scrollHost").get(touch)==null,"bottom edge gives gesture to shade");
  e.x=1200;prepare.invoke(touch,target,window,e);expect(field(touch,"scrollHost").get(touch)==null,"notification column unaffected");
  e.x=400;e.y=10;prepare.invoke(touch,target,window,e);expect(field(touch,"scrollHost").get(touch)==null,"header can close");
  e.y=300;node.max=0;node.value=0;prepare.invoke(touch,target,window,e);expect(field(touch,"scrollHost").get(touch)==null,"fitting tile page yields vertical gesture");
  node.max=320;node.value=30;prepare.invoke(touch,target,window,e);
  set(layout,"headerTranslation",-135.8f);frame.context.res.config.orientation=1;window.w=900;window.h=2000;
  layout.resetGeometry(target,true);expect(fragment.scrollState.offset==0 && layout.contentOffset()==0f,"old scroll and translation removed");
  prepare.invoke(touch,target,window,e);expect(field(touch,"scrollHost").get(touch)==null,"portrait never inherits split gesture");
  System.out.println("PASS: production scroll region hit test, upward gesture, edge handoff, notification/header exclusion, and landscape-to-portrait cleanup.");
 }
}
