package com.gamepadbt.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.gamepadbt.controller.CommandMap.Dir;

import java.util.HashSet;
import java.util.Set;

/**
 * Mando táctil con aspecto de control de consola (pensado para pantalla horizontal):
 * cruceta, stick izquierdo, stick derecho, ✕ ○ □ △, L1/L2/R1/R2, Share, Options y un
 * botón STOP rojo en el centro. Soporta varios dedos a la vez (multitáctil).
 *
 * La vista solo dibuja y detecta toques; qué texto se manda lo decide {@link ControlLogic}.
 */
public final class GamepadView extends View {

    private static final int T_NONE = 0, T_LS = 1, T_RS = 2, T_DPAD = 3, T_BTN = 4, T_STOP = 5, T_IGNORE = 6;

    // colores
    private static final int C_BG = 0xFF101216;
    private static final int C_BODY_TOP = 0xFF2B2F38;
    private static final int C_BODY_BOTTOM = 0xFF1B1E25;
    private static final int C_PART = 0xFF3A3F4B;
    private static final int C_PART_ON = 0xFF6B7385;
    private static final int C_TEXT = 0xFFC9CED8;
    private static final int C_TRIANGLE = 0xFF4CC38A;
    private static final int C_CIRCLE = 0xFFE0524B;
    private static final int C_CROSS = 0xFF4A90E2;
    private static final int C_SQUARE = 0xFFE27FB4;
    private static final int C_STOP = 0xFFD32F2F;

    private final int[] ptrType = new int[32];
    private final String[] ptrBtn = new String[32];
    private final Set<String> pressed = new HashSet<>();

    private ControlLogic logic;

    // estado visual
    private float lsX, lsY, rsX, rsY;
    private Dir dpadDir = Dir.NONE;
    private boolean stopPressed;
    private boolean connected;

    // geometría
    private float w, h;
    private float dpadCx, dpadCy, dpadR;
    private float lsCx, lsCy, rsCx, rsCy, stickR, knobR;
    private float faceCx, faceCy, faceD, faceR;
    private float stopCx, stopCy, stopR;
    private final RectF l1 = new RectF(), l2 = new RectF(), r1 = new RectF(), r2 = new RectF();
    private final RectF share = new RectF(), options = new RectF(), touchpad = new RectF(), body = new RectF();

    // pinceles
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private LinearGradient bodyShader;

    public GamepadView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setHapticFeedbackEnabled(true);
    }

    public void setLogic(ControlLogic logic) {
        this.logic = logic;
    }

    /** Solo cambia el aspecto (los controles se ven apagados si no hay conexión). */
    public void setConnected(boolean connected) {
        this.connected = connected;
        invalidate();
    }

    /** Suelta todos los dedos "virtuales" (al salir de la pantalla o perder la conexión). */
    public void resetTouches() {
        for (int i = 0; i < ptrType.length; i++) ptrType[i] = T_NONE;
        pressed.clear();
        lsX = lsY = rsX = rsY = 0;
        dpadDir = Dir.NONE;
        stopPressed = false;
        invalidate();
    }

    // ---------------------------------------------------------------------
    // Geometría
    // ---------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw;
        h = nh;

        body.set(0.015f * w, 0.02f * h, 0.985f * w, 0.98f * h);
        bodyShader = new LinearGradient(0, body.top, 0, body.bottom, C_BODY_TOP, C_BODY_BOTTOM, Shader.TileMode.CLAMP);

        dpadCx = 0.20f * w;  dpadCy = 0.45f * h;  dpadR = 0.17f * h;
        faceCx = 0.80f * w;  faceCy = 0.45f * h;  faceD = 0.13f * h;  faceR = 0.075f * h;

        lsCx = 0.37f * w;  lsCy = 0.79f * h;
        rsCx = 0.63f * w;  rsCy = 0.79f * h;
        stickR = 0.14f * h;  knobR = 0.083f * h;

        stopCx = 0.5f * w;  stopCy = 0.48f * h;  stopR = 0.095f * h;

        l2.set(0.05f * w, 0.04f * h, 0.19f * w, 0.125f * h);
        l1.set(0.05f * w, 0.14f * h, 0.19f * w, 0.225f * h);
        r2.set(0.81f * w, 0.04f * h, 0.95f * w, 0.125f * h);
        r1.set(0.81f * w, 0.14f * h, 0.95f * w, 0.225f * h);

        share.set(0.29f * w, 0.09f * h, 0.37f * w, 0.155f * h);
        options.set(0.63f * w, 0.09f * h, 0.71f * w, 0.155f * h);
        touchpad.set(0.40f * w, 0.06f * h, 0.60f * w, 0.26f * h);
    }

    private float faceX(String id) {
        if ("square".equals(id)) return faceCx - faceD;
        if ("circle".equals(id)) return faceCx + faceD;
        return faceCx;
    }

    private float faceY(String id) {
        if ("triangle".equals(id)) return faceCy - faceD;
        if ("cross".equals(id)) return faceCy + faceD;
        return faceCy;
    }

    private static final String[] FACE_IDS = {"triangle", "circle", "cross", "square"};

    // ---------------------------------------------------------------------
    // Toques
    // ---------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    pointerMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) pointerUp(e.getPointerId(i));
                break;
            default:
                break;
        }
        return true;
    }

    private boolean taken(int type) {
        for (int t : ptrType) if (t == type) return true;
        return false;
    }

    private static boolean inCircle(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private void pointerDown(int id, float x, float y) {
        if (id < 0 || id >= ptrType.length || logic == null) return;
        final long now = SystemClock.uptimeMillis();

        if (inCircle(x, y, stopCx, stopCy, stopR * 1.25f)) {
            ptrType[id] = T_STOP;
            stopPressed = true;
            haptic();
            // Que el dedo que seguía en un stick no reanude el movimiento justo después de parar.
            for (int i = 0; i < ptrType.length; i++) {
                int t = ptrType[i];
                if (t == T_LS || t == T_RS || t == T_DPAD) ptrType[i] = T_IGNORE;
            }
            lsX = lsY = rsX = rsY = 0;
            dpadDir = Dir.NONE;
            logic.emergencyStop();
            invalidate();
            return;
        }

        if (!taken(T_LS) && inCircle(x, y, lsCx, lsCy, stickR * 1.35f)) {
            ptrType[id] = T_LS;
            moveStick(T_LS, x, y, now);
            return;
        }
        if (!taken(T_RS) && inCircle(x, y, rsCx, rsCy, stickR * 1.35f)) {
            ptrType[id] = T_RS;
            moveStick(T_RS, x, y, now);
            return;
        }
        if (!taken(T_DPAD) && inCircle(x, y, dpadCx, dpadCy, dpadR * 1.25f)) {
            ptrType[id] = T_DPAD;
            moveDpad(x, y, now);
            return;
        }

        String b = hitButton(x, y);
        if (b != null && !pressed.contains(b)) {
            ptrType[id] = T_BTN;
            ptrBtn[id] = b;
            pressed.add(b);
            haptic();
            logic.onButton(b, true);
            invalidate();
            return;
        }
        ptrType[id] = T_IGNORE;
    }

    private void pointerMove(int id, float x, float y) {
        if (id < 0 || id >= ptrType.length || logic == null) return;
        final long now = SystemClock.uptimeMillis();
        switch (ptrType[id]) {
            case T_LS: moveStick(T_LS, x, y, now); break;
            case T_RS: moveStick(T_RS, x, y, now); break;
            case T_DPAD: moveDpad(x, y, now); break;
            default: break;
        }
    }

    private void pointerUp(int id) {
        if (id < 0 || id >= ptrType.length || logic == null) return;
        final long now = SystemClock.uptimeMillis();
        int t = ptrType[id];
        ptrType[id] = T_NONE;
        switch (t) {
            case T_LS:
                lsX = lsY = 0;
                logic.onLeftStick(0f, 0f, now);
                break;
            case T_RS:
                rsX = rsY = 0;
                logic.onRightStick(0f, 0f, now);
                break;
            case T_DPAD:
                dpadDir = Dir.NONE;
                logic.onDpad(Dir.NONE, now);
                break;
            case T_BTN: {
                String b = ptrBtn[id];
                ptrBtn[id] = null;
                if (b != null) {
                    pressed.remove(b);
                    logic.onButton(b, false);
                }
                break;
            }
            case T_STOP:
                stopPressed = false;
                break;
            default:
                break;
        }
        invalidate();
    }

    private void moveStick(int type, float x, float y, long now) {
        float cx = type == T_LS ? lsCx : rsCx;
        float cy = type == T_LS ? lsCy : rsCy;
        float dx = (x - cx) / stickR;
        float dy = (y - cy) / stickR;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) {
            dx /= len;
            dy /= len;
        }
        if (type == T_LS) {
            lsX = dx; lsY = dy;
            logic.onLeftStick(dx, dy, now);
        } else {
            rsX = dx; rsY = dy;
            logic.onRightStick(dx, dy, now);
        }
        invalidate();
    }

    private void moveDpad(float x, float y, long now) {
        Dir d = CommandMap.direction4((x - dpadCx) / dpadR, (y - dpadCy) / dpadR, 0.2f);
        if (d != dpadDir) {
            if (d != Dir.NONE) haptic();
            dpadDir = d;
            logic.onDpad(d, now);
            invalidate();
        }
    }

    private String hitButton(float x, float y) {
        for (String id : FACE_IDS) {
            if (inCircle(x, y, faceX(id), faceY(id), faceR * 1.3f)) return id;
        }
        final float pad = 0.012f * h;
        if (contains(l1, x, y, pad)) return "l1";
        if (contains(l2, x, y, pad)) return "l2";
        if (contains(r1, x, y, pad)) return "r1";
        if (contains(r2, x, y, pad)) return "r2";
        if (contains(share, x, y, pad)) return "share";
        if (contains(options, x, y, pad)) return "options";
        return null;
    }

    private static boolean contains(RectF r, float x, float y, float pad) {
        return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad;
    }

    private void haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ---------------------------------------------------------------------
    // Dibujo
    // ---------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(C_BG);
        if (w <= 0 || h <= 0) return;

        // cuerpo del mando
        fill.setShader(bodyShader);
        c.drawRoundRect(body, 0.16f * h, 0.16f * h, fill);
        fill.setShader(null);

        // panel táctil decorativo (como el del mando de PS4)
        fill.setColor(0xFF222630);
        c.drawRoundRect(touchpad, 0.03f * h, 0.03f * h, fill);

        drawShoulder(c, l2, "L2", pressed.contains("l2"));
        drawShoulder(c, l1, "L1", pressed.contains("l1"));
        drawShoulder(c, r2, "R2", pressed.contains("r2"));
        drawShoulder(c, r1, "R1", pressed.contains("r1"));
        drawPill(c, share, "SHARE", pressed.contains("share"));
        drawPill(c, options, "OPTIONS", pressed.contains("options"));

        drawDpad(c);
        drawStick(c, lsCx, lsCy, lsX, lsY);
        drawStick(c, rsCx, rsCy, rsX, rsY);
        drawFaceButtons(c);
        drawStop(c);
    }

    private void drawShoulder(Canvas c, RectF r, String label, boolean on) {
        fill.setColor(on ? C_PART_ON : C_PART);
        c.drawRoundRect(r, 0.03f * h, 0.03f * h, fill);
        text.setColor(C_TEXT);
        text.setTextSize(0.05f * h);
        drawCentered(c, label, r.centerX(), r.centerY());
    }

    private void drawPill(Canvas c, RectF r, String label, boolean on) {
        fill.setColor(on ? C_PART_ON : C_PART);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
        text.setColor(C_TEXT);
        text.setTextSize(0.032f * h);
        drawCentered(c, label, r.centerX(), r.centerY());
    }

    private void drawDpad(Canvas c) {
        float arm = dpadR * 0.62f;      // ancho de cada brazo
        float half = arm / 2f;
        float rad = arm * 0.25f;
        fill.setColor(C_PART);
        tmp.set(dpadCx - half, dpadCy - dpadR, dpadCx + half, dpadCy + dpadR);
        c.drawRoundRect(tmp, rad, rad, fill);
        tmp.set(dpadCx - dpadR, dpadCy - half, dpadCx + dpadR, dpadCy + half);
        c.drawRoundRect(tmp, rad, rad, fill);

        // brazo presionado
        fill.setColor(C_PART_ON);
        float len = dpadR - half;
        switch (dpadDir) {
            case UP: tmp.set(dpadCx - half, dpadCy - dpadR, dpadCx + half, dpadCy - half); break;
            case DOWN: tmp.set(dpadCx - half, dpadCy + half, dpadCx + half, dpadCy + dpadR); break;
            case LEFT: tmp.set(dpadCx - dpadR, dpadCy - half, dpadCx - half, dpadCy + half); break;
            case RIGHT: tmp.set(dpadCx + half, dpadCy - half, dpadCx + dpadR, dpadCy + half); break;
            default: tmp.setEmpty(); break;
        }
        if (!tmp.isEmpty()) c.drawRoundRect(tmp, rad, rad, fill);

        // flechitas
        fill.setColor(C_TEXT);
        float a = arm * 0.22f;
        float off = half + len * 0.45f;
        drawArrow(c, dpadCx, dpadCy - off, a, 0);
        drawArrow(c, dpadCx, dpadCy + off, a, 2);
        drawArrow(c, dpadCx - off, dpadCy, a, 3);
        drawArrow(c, dpadCx + off, dpadCy, a, 1);
    }

    /** dir: 0 arriba, 1 derecha, 2 abajo, 3 izquierda */
    private void drawArrow(Canvas c, float x, float y, float a, int dir) {
        path.reset();
        switch (dir) {
            case 0: path.moveTo(x, y - a); path.lineTo(x - a, y + a); path.lineTo(x + a, y + a); break;
            case 2: path.moveTo(x, y + a); path.lineTo(x - a, y - a); path.lineTo(x + a, y - a); break;
            case 3: path.moveTo(x - a, y); path.lineTo(x + a, y - a); path.lineTo(x + a, y + a); break;
            default: path.moveTo(x + a, y); path.lineTo(x - a, y - a); path.lineTo(x - a, y + a); break;
        }
        path.close();
        c.drawPath(path, fill);
    }

    private void drawStick(Canvas c, float cx, float cy, float nx, float ny) {
        // base (hueco)
        fill.setColor(0xFF14161B);
        c.drawCircle(cx, cy, stickR, fill);
        stroke.setColor(0xFF2E323C);
        stroke.setStrokeWidth(0.012f * h);
        c.drawCircle(cx, cy, stickR, stroke);

        // palanca: se mueve hasta el borde de la base
        float travel = stickR - knobR * 0.35f;
        float kx = cx + nx * travel;
        float ky = cy + ny * travel;
        fill.setShader(new RadialGradient(kx - knobR * 0.3f, ky - knobR * 0.3f, knobR * 1.6f,
                0xFF5A6070, 0xFF2A2E38, Shader.TileMode.CLAMP));
        c.drawCircle(kx, ky, knobR, fill);
        fill.setShader(null);
        stroke.setColor(0xFF1B1E25);
        stroke.setStrokeWidth(0.01f * h);
        c.drawCircle(kx, ky, knobR * 0.62f, stroke);
    }

    private void drawFaceButtons(Canvas c) {
        for (String id : FACE_IDS) {
            float x = faceX(id), y = faceY(id);
            boolean on = pressed.contains(id);
            fill.setColor(on ? C_PART_ON : C_PART);
            c.drawCircle(x, y, faceR, fill);

            float s = faceR * 0.5f;
            stroke.setStrokeWidth(0.016f * h);
            if ("triangle".equals(id)) {
                stroke.setColor(C_TRIANGLE);
                path.reset();
                path.moveTo(x, y - s * 1.05f);
                path.lineTo(x - s, y + s * 0.8f);
                path.lineTo(x + s, y + s * 0.8f);
                path.close();
                c.drawPath(path, stroke);
            } else if ("circle".equals(id)) {
                stroke.setColor(C_CIRCLE);
                c.drawCircle(x, y, s * 0.95f, stroke);
            } else if ("cross".equals(id)) {
                stroke.setColor(C_CROSS);
                c.drawLine(x - s * 0.9f, y - s * 0.9f, x + s * 0.9f, y + s * 0.9f, stroke);
                c.drawLine(x - s * 0.9f, y + s * 0.9f, x + s * 0.9f, y - s * 0.9f, stroke);
            } else {
                stroke.setColor(C_SQUARE);
                c.drawRect(x - s * 0.85f, y - s * 0.85f, x + s * 0.85f, y + s * 0.85f, stroke);
            }
        }
    }

    private void drawStop(Canvas c) {
        fill.setColor(stopPressed ? 0xFF8E1B1B : C_STOP);
        c.drawCircle(stopCx, stopCy, stopR, fill);
        stroke.setColor(0xFFFFB4AB);
        stroke.setStrokeWidth(0.01f * h);
        c.drawCircle(stopCx, stopCy, stopR * 0.88f, stroke);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(0.055f * h);
        drawCentered(c, "STOP", stopCx, stopCy);

        // lucecita de estado de conexión bajo el botón STOP
        fill.setColor(connected ? 0xFF4CC38A : 0xFF8A8F9A);
        c.drawCircle(stopCx, stopCy + stopR + 0.04f * h, 0.014f * h, fill);
    }

    private void drawCentered(Canvas c, String s, float cx, float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }
}
