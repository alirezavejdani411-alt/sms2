package com.seveneast.smscontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Lightning-bolt logo drawn in code (no image files needed). */
public class BoltView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();
    private final RectF rect = new RectF();
    private final boolean framed;

    public BoltView(Context context, boolean framed) {
        super(context);
        this.framed = framed;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // needed for the glow on older Android versions
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);
        if (s <= 0) return;
        float ox = (w - s) / 2f;
        float oy = (h - s) / 2f;

        if (framed) {
            rect.set(ox + 1, oy + 1, ox + s - 1, oy + s - 1);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(ox, oy, ox + s, oy + s,
                    Color.rgb(12, 42, 84), Color.rgb(30, 27, 75), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, s * 0.26f, s * 0.26f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, s * 0.035f));
            paint.setColor(Color.rgb(56, 189, 248));
            canvas.drawRoundRect(rect, s * 0.26f, s * 0.26f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        float height = framed ? s * 0.62f : s * 0.92f;
        float k = height / 20f;
        float cx = ox + s / 2f;
        float cy = oy + s / 2f;
        // Bolt polygon on a 24x24 grid, centred on (12,12).
        float[] pts = {14, 2, 5, 13.2f, 11, 13.2f, 10, 22, 19, 10.2f, 13, 10.2f};
        bolt.reset();
        for (int i = 0; i < pts.length; i += 2) {
            float x = cx + (pts[i] - 12f) * k;
            float y = cy + (pts[i + 1] - 12f) * k;
            if (i == 0) bolt.moveTo(x, y); else bolt.lineTo(x, y);
        }
        bolt.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx, cy - height / 2f, cx, cy + height / 2f,
                Color.rgb(253, 224, 71), Color.rgb(245, 158, 11), Shader.TileMode.CLAMP));
        paint.setShadowLayer(s * 0.10f, 0, 0, Color.argb(190, 250, 204, 21));
        canvas.drawPath(bolt, paint);
        paint.clearShadowLayer();
        paint.setShader(null);
    }
}
