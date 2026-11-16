package dev.oos.nativepanels;

import android.graphics.Insets;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import static dev.oos.nativepanels.TestDisplay.expect;

public class TestSplitHeader {
  public static void main(String[] args) throws Exception {
    FrameLayout window = new FrameLayout(), panel = new FrameLayout(), slot = new FrameLayout(), embedded = new FrameLayout();
    window.w = 2048; window.h = 1024;
    window.addView(panel, new FrameLayout.LayoutParams(-1,-1,48)); panel.id = 8;
    ViewGroup.MarginLayoutParams original = new ViewGroup.MarginLayoutParams(1024, -1);
    original.topMargin = 119; original.bottomMargin = 110;
    panel.addView(slot, original); slot.id = 7; slot.w = 1024; slot.top=119;
    slot.addView(embedded, new FrameLayout.LayoutParams(-1,-2,48));
    View row = new View(); embedded.addView(row, new FrameLayout.LayoutParams(-1,125,48));
    WindowInsets w = new WindowInsets();
    window.window = w; w.safe = new Insets(0,66,0,75); w.nav = new Insets(0,0,0,75);
    SplitShadeChrome.SharedHeader head = new SplitShadeChrome.SharedHeader();
    head.place(slot,row,embedded,38);
    expect(row.getParent()==panel && row.params.width==-1 && row.params.height==66,"full-width shared row at native status-bar height");
    expect(row.getPaddingTop()==0 && row.getPaddingLeft()==38,"clock and date inside row");
    expect(original.topMargin==66 && original.bottomMargin==75 && head.reserve==0,"exactly one header and one navigation clearance");
    slot.top=66;
    int requests=row.layouts+slot.layouts;
    head.place(slot,row,embedded,38);
    expect(row.layouts+slot.layouts==requests,"stable frame needs no additional layout request");
    ViewGroup group=new ViewGroup(); panel.addView(group, new FrameLayout.LayoutParams(-1,-1,48)); group.id=10;
    TestDisplay.Stack stack=new TestDisplay.Stack(); ViewGroup.MarginLayoutParams listParams=new ViewGroup.MarginLayoutParams(1024,-1);
    listParams.topMargin=119;listParams.bottomMargin=110;group.addView(stack,listParams);
    stack.w=1024;stack.h=1024;stack.x=1024;
    stack.row=null;
    SplitShadeChrome.Surface bg=new SplitShadeChrome.Surface(group,stack);
    expect(bg.place(stack,slot,0xff876543,255),"empty notification stack still has a surface");
    expect(TestDisplay.rect(bg).equals("1024,66,2048,949"),"right column matches reference status and navigation boundaries");
    expect(stack.padding==82 && listParams.topMargin==66 && listParams.bottomMargin==75,"native rows and margins follow reference bounds");
    stack.top=66; stack.y=66; stack.h=883;
    bg.place(stack,slot,0xff876543,255);
    expect(stack.padding==16 && bg.scrollEnd(0)==867 && bg.scrollEnd(16)==883,"inner gutters counted once after layout");
    bg.hideColumn();
    expect(listParams.topMargin==119 && listParams.bottomMargin==110 && stack.padding==78,"notification margins and padding restored");
    // Immersive mode and an asymmetric cutout: the top row uses the new insets, in the same instance.
    w.nav=Insets.NONE;w.safe=new Insets(52,48,0,0);w.cutout=new DisplayCutout();w.cutout.top.set(750,9,865,79);
    slot.top=25;original.topMargin=25;original.bottomMargin=30;
    head.place(slot,row,embedded,38);
    expect(row.params.height==88 && head.reserve==63 && original.bottomMargin==0 && row.getPaddingLeft()==90,"cutout, hidden nav and new native margins");
    FrameLayout recreated=new FrameLayout();
    head.restore(row,recreated);
    expect(row.getParent()==recreated && panel.indexOfChild(row)<0 && original.topMargin==25 && original.bottomMargin==30,"recreated Compose host owns the live row after rotation");
    panel.left=52;panel.w=1996;recreated.addView(embedded,new FrameLayout.LayoutParams(-1,-2,48));
    head.place(slot,row,recreated,38);
    expect(head.placed() && row.getPaddingLeft()==38,"host already inset around side camera counts clearance once");
    head.restore(row,recreated);
    slot.parent=null;
    head.place(slot,row,recreated,38);
    expect(!head.placed() && row.getParent()==recreated,"unknown host retains embedded header");
    System.out.println("PASS: full-width live header, separate placement and reserved space, stable two-column bounds, empty notification view, cutouts, visible/hidden navigation and host recreation.");
  }
}
