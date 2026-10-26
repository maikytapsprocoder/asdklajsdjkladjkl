package com.gamepadbt.controller;

import com.gamepadbt.controller.CommandMap.Dir;

/**
 * Traduce lo que hace el dedo (sticks, cruceta, botones) en texto para el robot.
 * Java puro: no depende de Android, se prueba con LogicTest.
 *
 * Reglas:
 *  - Mientras un stick/cruceta está inclinado se manda su comando al cambiar de dirección
 *    y se REPITE cada "repeatMs" (el robot sabe que seguimos ahí).
 *  - Al soltar se manda el comando de "parar" (por defecto "S").
 *  - La cruceta tiene prioridad sobre el stick izquierdo (los dos manejan el movimiento).
 *  - Los botones mandan al presionar y, si el comando es "X|x", también al soltar.
 */
public final class ControlLogic {

    public interface Sender {
        /** @param payload texto exacto a mandar  @param label descripción corta para el registro */
        void send(String payload, String label);
    }

    private final CommandMap map;
    private final Sender sender;

    private Dir leftStick = Dir.NONE;
    private Dir dpad = Dir.NONE;
    private Dir rightStick = Dir.NONE;

    private Dir activeMove = Dir.NONE;
    private Dir activeRs = Dir.NONE;
    private long lastMoveSend;
    private long lastRsSend;

    public ControlLogic(CommandMap map, Sender sender) {
        this.map = map;
        this.sender = sender;
    }

    // ---- entradas -------------------------------------------------------

    /** x,y entre -1 y 1 (y hacia abajo es positivo). */
    public void onLeftStick(float x, float y, long nowMs) {
        leftStick = map.resolve("move", x, y);
        refreshMove(nowMs);
    }

    public void onDpad(Dir d, long nowMs) {
        dpad = d;
        refreshMove(nowMs);
    }

    public void onRightStick(float x, float y, long nowMs) {
        rightStick = map.resolve("rs", x, y);
        if (rightStick != activeRs) {
            Dir previous = activeRs;
            activeRs = rightStick;
            if (activeRs != Dir.NONE) {
                emit(map.commandFor("rs", activeRs), "stick der. " + activeRs);
                lastRsSend = nowMs;
            } else if (previous != Dir.NONE) {
                emit(map.stopCommandFor("rs"), "stick der. suelto");
            }
        }
    }

    /** id: cross, circle, square, triangle, l1, r1, l2, r2, share, options, stop */
    public void onButton(String id, boolean down) {
        String cmd = map.get("btn_" + id);
        String part = down ? CommandMap.pressPart(cmd) : CommandMap.releasePart(cmd);
        emit(part, "botón " + id + (down ? "" : " (soltar)"));
    }

    /** Llamar cada ~30-50 ms: repite el comando mientras el dedo sigue inclinado. */
    public void onTick(long nowMs) {
        int every = map.repeatMs();
        if (every <= 0) return;
        if (activeMove != Dir.NONE && nowMs - lastMoveSend >= every) {
            emit(map.commandFor("move", activeMove), null);
            lastMoveSend = nowMs;
        }
        if (activeRs != Dir.NONE && nowMs - lastRsSend >= every) {
            emit(map.commandFor("rs", activeRs), null);
            lastRsSend = nowMs;
        }
    }

    /** Botón STOP: manda el comando de emergencia y deja todo en reposo. */
    public void emergencyStop() {
        leftStick = Dir.NONE;
        dpad = Dir.NONE;
        rightStick = Dir.NONE;
        activeMove = Dir.NONE;
        activeRs = Dir.NONE;
        emit(CommandMap.pressPart(map.get("btn_stop")), "STOP");
    }

    /** Se llama al salir de la pantalla o perder la conexión: si se estaba moviendo, parar. */
    public void releaseAll() {
        boolean wasMoving = activeMove != Dir.NONE;
        boolean rsWasActive = activeRs != Dir.NONE;
        leftStick = Dir.NONE;
        dpad = Dir.NONE;
        rightStick = Dir.NONE;
        activeMove = Dir.NONE;
        activeRs = Dir.NONE;
        if (wasMoving) emit(map.stopCommandFor("move"), "parar");
        if (rsWasActive) emit(map.stopCommandFor("rs"), "stick der. suelto");
    }

    // ---- internos -------------------------------------------------------

    private void refreshMove(long nowMs) {
        Dir wanted = dpad != Dir.NONE ? dpad : leftStick;
        if (wanted == activeMove) return;
        Dir previous = activeMove;
        activeMove = wanted;
        if (wanted != Dir.NONE) {
            emit(map.commandFor("move", wanted), "mover " + wanted);
            lastMoveSend = nowMs;
        } else if (previous != Dir.NONE) {
            emit(map.stopCommandFor("move"), "parar");
        }
    }

    private void emit(String cmd, String label) {
        String payload = map.payload(cmd);
        if (payload.isEmpty()) return;
        sender.send(payload, label);
    }
}
