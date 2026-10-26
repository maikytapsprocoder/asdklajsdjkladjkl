#!/bin/sh
# Prueba la lógica de control (sin Android). Requiere solo Java 8+.
set -e
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
javac -d "$OUT" app/src/main/java/com/gamepadbt/controller/CommandMap.java \
                app/src/main/java/com/gamepadbt/controller/ControlLogic.java \
                tools/LogicTest.java
java -cp "$OUT" LogicTest
