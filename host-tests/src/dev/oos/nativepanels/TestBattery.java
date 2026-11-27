package dev.oos.nativepanels;
import static dev.oos.nativepanels.TestDisplay.*;
import android.graphics.RectF;
import android.view.View;
import android.widget.*;
public class TestBattery {
 public static class Meter extends LinearLayout {
  public Meter getBatteryIcon(){return this;}
  public int color(){return 0xff142029;}
  public float offset(){return 0f;}
 }
 public static void main(String[] args)throws Exception {
  HomeStatusBattery h=TestGesture.alloc(HomeStatusBattery.class);Meter original=new Meter();h.context=original.context;View icon=new View(),old=new View(),rendered=new View(),percentage=new View();
  Space spacer=new Space();original.list.add(old);original.list.add(icon);original.list.add(percentage);
  set(h,"original",original);set(h,"icon",icon);set(h,"oldStyle",old);set(h,"attributionSpace",spacer);set(h,"lastForeground",original.color());
  set(h,"currentIcon",Meter.class.getMethod("getBatteryIcon"));set(h,"tintColor",Meter.class.getMethod("color"));set(h,"contentOffset",Meter.class.getMethod("offset"));
  set(h,"bounds",new RectF());set(h,"chargingIndicator",new HomeStatusBattery.ChargingIndicator(null));BatteryFrameSize frameSize=new BatteryFrameSize(24f,13f,1.5f);set(h,"frameSize",frameSize);
  ShadeBattery bat=TestGesture.alloc(ShadeBattery.class);set(bat,"view",rendered);set(h,"battery",bat);
  for(int height:new int[]{26,39}) for(int rtl:new int[]{0,1}) {
   h.rtl=rtl;icon.w=height*2;icon.h=height;rendered.h=height;
   int normal=frameSize.normalWidth(height);rendered.w=normal;
   h.onPreDraw();float scale=rendered.sx,position=rendered.tx;
   expect(scale>0 && original.list.size()==3,"neutral layout");
   rendered.w+=9;expect(!h.onPreDraw(),"remeasure after plug");
   expect(rendered.sx==scale && rendered.sy==scale,"same painted body height");
   expect(Math.abs(rendered.tx+(rtl==0?0:9*scale)-position)<.001f,"same body anchor");
   expect(original.list.get(2)==spacer && original.list.get(3)==percentage,"bolt space before percentage");
   expect(spacer.params.width==(int)Math.ceil(9*scale) && h.onPreDraw(),"charging layout settles");
   rendered.w=normal;expect(!h.onPreDraw() && h.onPreDraw() && original.list.size()==3,"unplug releases bolt width");
  }
  System.out.println("PASS: both display scales and directions, normal / plug / unplug maintain measured battery body size and reserve only glyph width before separate percent.");
 }
}
