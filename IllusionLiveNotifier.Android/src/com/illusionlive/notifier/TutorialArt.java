package com.illusionlive.notifier;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The example pictures behind each first-run tutorial step: a small mock-up of the app itself with
 * the step's gesture drawn on top. Painted rather than shipped as PNGs — the mock-up has to follow
 * the live palette in both themes and stay sharp on every density, which one canvas does and a set
 * of bitmaps would not.
 */
final class TutorialArt extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final DashPathEffect dash;
    private int step;

    TutorialArt(Context context) {
        super(context);
        setWillNotDraw(false);
        float unit = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, unit * 1.4f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        dash = new DashPathEffect(new float[]{unit * 4f, unit * 3f}, 0f);
    }

    void setStep(int value) {
        if (step == value) return;
        step = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float w = getWidth();
        if (h <= 0f || w <= 0f) return;

        float phoneW = h * 0.56f;
        float left = (w - phoneW) / 2f;
        float right = left + phoneW;
        float headerH = h * 0.17f;

        // phone shell: body first, then the header band clipped back to the rounded top
        roundRect(canvas, left, 0f, right, h, h * 0.08f, MainActivity.CANVAS, MainActivity.LINE);
        canvas.save();
        canvas.clipRect(left, 0f, right, headerH);
        roundRect(canvas, left, 0f, right, h, h * 0.08f, MainActivity.BRAND, 0);
        canvas.restore();
        roundRect(canvas, left + phoneW * 0.09f, headerH * 0.39f, left + phoneW * 0.5f,
                headerH * 0.63f, headerH * 0.12f, alpha(MainActivity.ON_BRAND, 0xCC), 0);
        gear(canvas, right - phoneW * 0.16f, headerH * 0.51f, headerH * 0.2f, step == 0);

        float rowLeft = left + phoneW * 0.09f;
        float rowRight = right - phoneW * 0.09f;
        float top = headerH + h * 0.07f;
        float rowH = h * 0.135f;
        float pitch = rowH + h * 0.05f;

        switch (step) {
            case 0: // pick boards in settings
                for (int i = 0; i < 4; i++) {
                    float t = top + i * pitch;
                    checkRow(canvas, rowLeft, t, rowRight, t + rowH, i != 2);
                }
                break;
            case 1: // pull the list down to refresh
                for (int i = 0; i < 3; i++) {
                    float t = top + h * 0.16f + i * pitch;
                    postRow(canvas, rowLeft, t, rowRight, t + rowH, false);
                }
                pullDown(canvas, (left + right) / 2f, top + h * 0.05f, h);
                break;
            case 2: // tap a post, the original opens
                for (int i = 0; i < 4; i++) {
                    float t = top + i * pitch;
                    postRow(canvas, rowLeft, t, rowRight, t + rowH, i != 0);
                }
                touch(canvas, rowLeft + (rowRight - rowLeft) * 0.32f, top + rowH * 0.5f, h * 0.055f);
                browser(canvas, right - phoneW * 0.2f, h * 0.44f, right + phoneW * 0.62f, h * 0.95f, h);
                break;
            case 3: // notified while the app is closed
                for (int i = 0; i < 4; i++) {
                    float t = top + i * pitch;
                    postRow(canvas, rowLeft, t, rowRight, t + rowH, true);
                }
                notification(canvas, left - phoneW * 0.3f, h * 0.09f, right + phoneW * 0.3f,
                        h * 0.37f, h);
                break;
            default: // posts from before the first launch stay silent
                for (int i = 0; i < 4; i++) {
                    float t = top + i * pitch;
                    postRow(canvas, rowLeft, t, rowRight, t + rowH, i >= 2);
                    if (i < 2) {
                        fill.setColor(MainActivity.ACCENT);
                        canvas.drawCircle(rowRight - h * 0.045f, t + rowH * 0.34f, h * 0.018f, fill);
                    }
                }
                float line = top + 2f * pitch - h * 0.025f;
                stroke.setColor(MainActivity.ACCENT);
                stroke.setPathEffect(dash);
                canvas.drawLine(rowLeft, line, rowRight, line, stroke);
                stroke.setPathEffect(null);
                break;
        }
    }

    // ------------------------------------------------------------------ parts

    /** A post row: card, title bar, meta bar. Dimmed rows read as "not for you right now". */
    private void postRow(Canvas canvas, float l, float t, float r, float b, boolean dim) {
        float height = b - t;
        float pad = height * 0.22f;
        roundRect(canvas, l, t, r, b, height * 0.26f,
                dim ? alpha(MainActivity.SURFACE, 0x99) : MainActivity.SURFACE, MainActivity.LINE);
        roundRect(canvas, l + pad, t + pad, r - pad * 3f, t + pad + height * 0.2f, height * 0.1f,
                alpha(MainActivity.INK, dim ? 0x33 : 0x99), 0);
        roundRect(canvas, l + pad, b - pad - height * 0.15f, l + (r - l) * 0.5f, b - pad,
                height * 0.08f, alpha(MainActivity.MUTED, dim ? 0x2A : 0x66), 0);
    }

    /** A settings row: checkbox plus label bar. */
    private void checkRow(Canvas canvas, float l, float t, float r, float b, boolean checked) {
        float height = b - t;
        float pad = height * 0.24f;
        roundRect(canvas, l, t, r, b, height * 0.26f, MainActivity.SURFACE, MainActivity.LINE);

        float side = height * 0.44f;
        float bx = l + pad;
        float by = t + (height - side) / 2f;
        roundRect(canvas, bx, by, bx + side, by + side, side * 0.28f,
                checked ? MainActivity.ACCENT : 0, checked ? 0 : MainActivity.LINE);
        if (checked) {
            stroke.setColor(MainActivity.SURFACE);
            canvas.drawLine(bx + side * 0.24f, by + side * 0.52f, bx + side * 0.44f,
                    by + side * 0.72f, stroke);
            canvas.drawLine(bx + side * 0.44f, by + side * 0.72f, bx + side * 0.76f,
                    by + side * 0.3f, stroke);
        }
        roundRect(canvas, bx + side + pad * 0.8f, t + height * 0.38f, r - pad * 2f,
                t + height * 0.62f, height * 0.12f,
                alpha(MainActivity.INK, checked ? 0x99 : 0x55), 0);
    }

    private void gear(Canvas canvas, float cx, float cy, float radius, boolean highlight) {
        if (highlight) {
            fill.setColor(alpha(MainActivity.ACCENT, 0x66));
            canvas.drawCircle(cx, cy, radius * 2.1f, fill);
            stroke.setColor(MainActivity.ACCENT);
            canvas.drawCircle(cx, cy, radius * 2.1f, stroke);
        }
        stroke.setColor(MainActivity.ON_BRAND);
        canvas.drawCircle(cx, cy, radius, stroke);
        fill.setColor(MainActivity.ON_BRAND);
        canvas.drawCircle(cx, cy, radius * 0.34f, fill);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * i / 4.0;
            float dx = (float) Math.cos(angle) * radius * 1.45f;
            float dy = (float) Math.sin(angle) * radius * 1.45f;
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke);
        }
    }

    /** Finger arrow dragging the list downwards. */
    private void pullDown(Canvas canvas, float cx, float cy, float h) {
        float reach = h * 0.075f;
        fill.setColor(alpha(MainActivity.ACCENT, 0x33));
        canvas.drawCircle(cx, cy, reach * 1.5f, fill);
        stroke.setColor(MainActivity.ACCENT);
        canvas.drawLine(cx, cy - reach, cx, cy + reach, stroke);
        canvas.drawLine(cx - reach * 0.6f, cy + reach * 0.4f, cx, cy + reach, stroke);
        canvas.drawLine(cx + reach * 0.6f, cy + reach * 0.4f, cx, cy + reach, stroke);
    }

    private void touch(Canvas canvas, float cx, float cy, float radius) {
        fill.setColor(alpha(MainActivity.ACCENT, 0x59));
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setColor(MainActivity.ACCENT);
        canvas.drawCircle(cx, cy, radius, stroke);
        canvas.drawCircle(cx, cy, radius * 1.8f, stroke);
    }

    /** The opened original: a browser window sliding out over the list. */
    private void browser(Canvas canvas, float l, float t, float r, float b, float h) {
        roundRect(canvas, l, t, r, b, h * 0.05f, MainActivity.SURFACE, MainActivity.LINE);
        float barB = t + (b - t) * 0.22f;
        canvas.save();
        canvas.clipRect(l, t, r, barB);
        roundRect(canvas, l, t, r, b, h * 0.05f, alpha(MainActivity.MUTED, 0x24), 0);
        canvas.restore();
        fill.setColor(alpha(MainActivity.MUTED, 0x66));
        float dotY = (t + barB) / 2f;
        for (int i = 0; i < 3; i++)
            canvas.drawCircle(l + (b - t) * (0.1f + i * 0.09f), dotY, h * 0.012f, fill);

        float pad = (r - l) * 0.09f;
        float lineH = h * 0.022f;
        for (int i = 0; i < 3; i++) {
            float y = barB + pad + i * lineH * 2.4f;
            roundRect(canvas, l + pad, y, r - pad * (i == 2 ? 3f : 1f), y + lineH, lineH * 0.5f,
                    alpha(MainActivity.INK, i == 0 ? 0x99 : 0x44), 0);
        }
    }

    /** The system notification the background check posts. */
    private void notification(Canvas canvas, float l, float t, float r, float b, float h) {
        roundRect(canvas, l, t, r, b, h * 0.05f, MainActivity.SURFACE, MainActivity.LINE);
        float pad = (b - t) * 0.22f;
        float side = (b - t) - pad * 2f;
        roundRect(canvas, l + pad, t + pad, l + pad + side, b - pad, side * 0.26f,
                MainActivity.BRAND, 0);
        fill.setColor(MainActivity.ON_BRAND);
        canvas.drawCircle(l + pad + side / 2f, t + pad + side / 2f, side * 0.2f, fill);

        float textL = l + pad * 2f + side;
        roundRect(canvas, textL, t + pad, r - pad * 3f, t + pad + h * 0.028f, h * 0.014f,
                alpha(MainActivity.INK, 0x99), 0);
        roundRect(canvas, textL, b - pad - h * 0.024f, r - pad * 6f, b - pad, h * 0.012f,
                alpha(MainActivity.MUTED, 0x88), 0);
    }

    // ---------------------------------------------------------------- drawing

    private void roundRect(Canvas canvas, float l, float t, float r, float b, float radius,
                           int fillColor, int strokeColor) {
        box.set(l, t, r, b);
        if (fillColor != 0) {
            fill.setColor(fillColor);
            canvas.drawRoundRect(box, radius, radius, fill);
        }
        if (strokeColor != 0) {
            stroke.setColor(strokeColor);
            canvas.drawRoundRect(box, radius, radius, stroke);
        }
    }

    private static int alpha(int color, int value) {
        return (color & 0x00FFFFFF) | (value << 24);
    }
}
