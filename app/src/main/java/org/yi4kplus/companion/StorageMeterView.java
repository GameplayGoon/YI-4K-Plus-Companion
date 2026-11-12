package org.yi4kplus.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Compact storage-capacity bar (filled section is USED storage). */
public final class StorageMeterView extends View {
    private int used = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public StorageMeterView(Context context) { super(context); }
    public void setUsedPercent(int pct) { used = pct < 0 ? -1 : Math.max(0, Math.min(100, pct)); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(), h=getHeight();
        if (w<=0 || h<=0) return;
        RectF track=new RectF(0,0,w,h);
        paint.setColor(0xFF344455);
        canvas.drawRoundRect(track,h/2,h/2,paint);
        if (used>=0 && used>0) {
            paint.setColor(used>=90?0xFFF25C63:used>=75?0xFFFFBC5C:0xFF32DCAD);
            canvas.drawRoundRect(new RectF(0,0,Math.max(h,w*used/100f),h),h/2,h/2,paint);
        }
    }
}
