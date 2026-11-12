package org.yi4kplus.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Accessible, high-contrast camera/gallery/settings navigation glyphs. */
public final class NavGlyph extends View {
    private final int type;
    private final int tint;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public NavGlyph(Context context, int type, int tint) {
        super(context);
        this.type = type;
        this.tint = tint;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float size = Math.min(getWidth(), getHeight());
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 32f, size / 32f);
        paint.setColor(tint);
        paint.setStrokeWidth(2.2f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (type == 0) {
            // Recognisable camera silhouette, lens and flash housing.
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(3, 10, 29, 27), 4, 4, paint);
            canvas.drawRoundRect(new RectF(8, 6, 19, 14), 2, 2, paint);
            paint.setColor(0xFF152031);
            canvas.drawCircle(16, 18.5f, 7.1f, paint);
            paint.setColor(tint);
            canvas.drawCircle(16, 18.5f, 4.7f, paint);
            paint.setColor(0xFF152031);
            canvas.drawCircle(16, 18.5f, 2.3f, paint);
            paint.setColor(0xFF152031);
            canvas.drawCircle(25, 13, 1.3f, paint);
        } else if (type == 1) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(new RectF(4, 8, 24, 27), 2.7f, 2.7f, paint);
            canvas.drawRoundRect(new RectF(9, 4, 29, 23), 2.7f, 2.7f, paint);
            Path mountains = new Path();
            mountains.moveTo(12, 19); mountains.lineTo(17, 14);
            mountains.lineTo(20, 17); mountains.lineTo(24, 12); mountains.lineTo(26, 16);
            canvas.drawPath(mountains, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(16, 9, 1.4f, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                canvas.drawLine(16 + dx * 10.5f, 16 + dy * 10.5f,
                    16 + dx * 13f, 16 + dy * 13f, paint);
            }
            paint.setStrokeWidth(3f);
            canvas.drawCircle(16, 16, 10f, paint);
            canvas.drawCircle(16, 16, 4.1f, paint);
        }
        canvas.restore();
    }
}
