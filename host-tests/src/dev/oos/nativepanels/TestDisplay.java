package dev.oos.nativepanels;
import android.graphics.*;
import android.view.*;
import java.lang.reflect.*;
public class TestDisplay {
 static Field field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
 static void set(Object o,String n,Object v)throws Exception{field(o,n).set(o,v);}
 static void expect(boolean c,String what){if(!c)throw new AssertionError(what);}
 static class Panel extends ViewGroup {}
 public static class Stack extends View {public int scroll;public View row=new View();public View getFirstChildNotGone(){return row;}public int padding=78;public int getTopPadding(){return padding;}public void updateTopPadding(float value,boolean animate){padding=(int)value;}public int getOwnScrollY(){return scroll;}}
 static class Controller {public Rect clip=new Rect();public Holder mNotificationStackScrollLayoutController=new Holder();}
 static class Holder {View v;public View getView(){return v;}}
 static String rect(Object o)throws Exception{return ((Rect)field(o,"bounds").get(o)).toShortString();}
 public static void main(String[] args)throws Exception{
  NotificationStackScrim n=new NotificationStackScrim();set(n,"installed",true);set(n,"panelType",Panel.class);set(n,"lastClip",Controller.class.getField("clip"));
  Panel panel=new Panel();panel.w=1900;panel.h=950; ViewGroup group=new ViewGroup();group.w=1900;group.h=950;group.root=panel;group.parent=panel;group.context=panel.context;
  Stack stack=new Stack();stack.row.top=90;stack.row.h=150;View frame=new View(),scrim=new View();stack.root=frame.root=scrim.root=panel;stack.context=frame.context=panel.context;
  group.list.add(stack);stack.parent=group;frame.parent=panel;frame.w=920;frame.h=950;frame.id=7;frame.left=18;frame.x=18;
  panel.children.put(1,scrim);stack.x=1010;stack.y=65;stack.top=65;stack.w=810;stack.h=800;
  Controller c=new Controller();c.mNotificationStackScrollLayoutController.v=stack;c.clip.set(0,760,1900,900);
  n.bind(c,true);frame.context.res.config.orientation=2;
  n.update(frame,1,1);SplitShadeChrome.Surface surface=(SplitShadeChrome.Surface)field(n,"splitLayer").get(n);
  expect(group.list.indexOf(surface)==0 && group.list.indexOf(stack)==1,"surface immediately under live list");
  expect(rect(surface).equals("950,65,1900,865"),"gutter around narrow column: "+rect(surface));
  stack.y=-215;
  n.update(frame,.45f,1);
  expect(rect(surface).equals("950,65,1900,865"),"split pane fades at full height even if OOS translates the list");
  expect(!surface.containsTouch(50,200) && surface.containsTouch(50,325),"touch clipping follows the fixed pane in moving list coordinates");
  stack.y=65;n.update(frame,1,1);
  Paint fill=(Paint)field(surface,"paint").get(surface);
  int light=fill.getColor();frame.context.res.config.uiMode=32;n.update(frame,1,1);
  expect(fill.getColor()!=light,"day/night surface color");frame.context.res.config.uiMode=16;
  Canvas canvas=new Canvas();surface.onDraw(canvas);expect(canvas.draws==1,"four round corners");
  surface.clipRows(canvas);
  expect(canvas.clipped.t==0 && canvas.clipped.b==800,"card clipping uses the actual column in stack coordinates");
  expect(!surface.containsTouch(20,-1)&&surface.containsTouch(50,120)&&!surface.containsTouch(50,802),"invisible rows cannot accept taps");
  stack.scroll=150;stack.row.top=-60;n.update(frame,1,1);expect(rect(surface).equals("950,65,1900,865"),"viewport stays put when the first row scrolls");
  stack.scroll=0;stack.row.top=90;
  expect(stack.padding==0,"native stack begins below the header in this example");
  expect(surface.onSystemPadding(84f)==0,"native updates preserved while active");
  n.update(frame,.5f,1);expect(((Paint)field(surface,"paint").get(surface)).getAlpha()==Math.round(128 * ShadePullMotion.splitContentAlpha(.5f)),"split fade");
  stack.x=0;stack.w=1900;n.update(frame,1,1);expect(rect(surface).equals("950,65,1900,865"),"full-window scroller limited to right pane");
  frame.x=980;frame.rtl=1;n.update(frame,1,1);expect(rect(surface).equals("0,65,950,865"),"mirrored left pane");
  stack.visibility=8;n.update(frame,1,1);expect(surface.visibility==4,"hidden stack hides column");
  stack.visibility=0;frame.rtl=0;frame.context.res.config.orientation=1;panel.w=950;panel.h=1900;frame.w=950;
  n.update(frame,1,0);expect(stack.padding==84,"latest native row padding restored");expect(!n.isSplit() && !group.list.contains(surface),"portrait removes split surface even with stale controller flag");
  expect(rect(n).equals("0,760,1900,900"),"portrait clip restored");
  expect(((Paint)field(n,"paint").get(n)).getColor()==light,"landscape and portrait match including alpha");
  ViewGroup translated=new ViewGroup();translated.parent=panel;translated.top=30;translated.y=-135;frame.parent=translated;frame.top=4;frame.y=275;
  expect(SplitShadeChrome.layoutCoordinate(frame,false)==34,"layout origin excludes pull translation");
  frame.context.res.config.orientation=2;panel.w=1900;panel.h=950;frame.w=920;n.bind(c,false);n.update(frame,1,1);
  expect(n.isSplit() && group.list.size()==2,"second rotation infers visible split without original flag");
  SplitShadeChrome chrome=new SplitShadeChrome();View h=new View(),icons=new View(),battery=new View(),privacy=new View();
  h.root=panel;h.children.put(5,icons);h.children.put(6,battery);panel.children.put(4,h);
  chrome.showNativeHeader(frame,true);expect(icons.visibility==4 && battery.visibility==4 && privacy.visibility==0,"only OEM pair hidden");
  chrome.restore();expect(icons.visibility==0 && battery.visibility==0,"restore after closing");
  battery.visibility=8;chrome.showNativeHeader(frame,true);chrome.showNativeHeader(frame,false);expect(battery.visibility==8,"respect OEM gone");
  System.out.println("PASS: live stack, anchored top, shared row clip and hit region, constant viewport while scrolling, color and alpha parity, RTL, rotation, and visibility restoration.");
 }
}
