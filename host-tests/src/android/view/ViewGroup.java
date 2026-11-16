package android.view; public class ViewGroup extends View implements ViewParent {
 public static class LayoutParams{public LayoutParams(){}public int width,height;public LayoutParams(int w,int h){width=w;height=h;}}
 public static class MarginLayoutParams extends LayoutParams {public int topMargin,bottomMargin;public MarginLayoutParams(int w,int h){super(w,h);}}
 public void addView(View v,LayoutParams lp){addView(v,list.size(),lp);} public void addView(View v){addView(v,list.size(),v.params);}
 public java.util.List<View> list=new java.util.ArrayList<>();
 public int indexOfChild(View v){return list.indexOf(v);}
 public void addView(View v,int index,LayoutParams params){list.add(index,v);v.params=params;v.parent=this;v.root=root;v.w=w;v.h=h;v.x=x;v.y=y;}
 public void removeView(View v){list.remove(v);v.parent=null;}
}