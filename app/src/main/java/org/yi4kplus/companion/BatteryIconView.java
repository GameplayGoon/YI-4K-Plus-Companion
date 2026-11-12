package org.yi4kplus.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A capacity indicator driven by the camera's own battery percentage. */
public final class BatteryIconView extends View {
    private int level = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public BatteryIconView(Context context) { super(context); }
    public void setLevel(int percent) {
        level = percent < 0 ? -1 : Math.max(0, Math.min(100, percent));
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cap = w * .095f;
        RectF outer = new RectF(1.5f, h * .14f, w - cap - 2f, h * .86f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, h * .09f));
        paint.setColor(0xFFB5CAD5);
        canvas.drawRoundRect(outer, h * .16f, h * .16f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(w - cap + 0.5f, h * .37f, w, h * .63f), 1.5f, 1.5f, paint);
        if (level >= 0) {
            paint.setColor(level <= 20 ? 0xFFF25C63 : level <= 45 ? 0xFFFFBC5C : 0xFF32DCAD);
            float width = (outer.width() - 5f) * level / 100f;
            if (width > 0) canvas.drawRoundRect(new RectF(outer.left + 2.5f, outer.top + 2.5f,
                outer.left + 2.5f + width, outer.bottom - 2.5f), h * .07f, h * .07f, paint);
        }
    }
}
