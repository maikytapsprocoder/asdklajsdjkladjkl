# Mando Bluetooth (estilo consola) para robots con HC‑05 / HC‑06

App Android con un mando táctil estilo PS4: **cruceta, dos sticks, ✕ ○ □ △, L1/L2/R1/R2, Share, Options y un botón STOP rojo**.
Manda letras por Bluetooth clásico (perfil serie SPP, el mismo que usan las apps de "terminal Bluetooth" para Arduino),
así que funciona con cualquier robot cuyo Arduino lea caracteres del módulo HC‑05.

Sin anuncios, sin internet, sin permisos de ubicación. Un solo permiso: Bluetooth.

---

## 1. Cómo conseguir el .apk

### Opción A — sin instalar nada (GitHub Actions)
1. Crea una cuenta en github.com y un repositorio nuevo (privado está bien).
2. Sube **todo el contenido de esta carpeta** (incluida la carpeta oculta `.github`).
3. Entra en la pestaña **Actions** → "Generar APK". Se ejecuta sola al subir los archivos (≈ 3–5 min).
4. Abre la ejecución terminada → abajo, **Artifacts** → descarga `MandoBluetooth-apk`. Dentro está `app-debug.apk`.
5. Pásalo al teléfono e instálalo (hay que permitir "instalar apps de origen desconocido").

### Opción B — Android Studio
1. Instala Android Studio, **File ▸ Open** y elige esta carpeta.
2. Espera a que termine "Gradle sync" (descarga lo necesario solo).
3. **Build ▸ Build APK(s)**. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.
   O conecta el teléfono con depuración USB y pulsa ▶ Run.

## 2. Usarla

1. Enciende el robot y **empareja el HC‑05 en los ajustes Bluetooth del teléfono** (PIN `1234` o `0000`). Solo hace falta una vez.
2. Abre **Mando Bluetooth** → concede el permiso → toca el módulo del robot en la lista.
3. Cuando arriba diga **Conectado** (y la luz bajo STOP se ponga verde), maneja.

Controles de la pantalla del mando:

| Botón | Qué hace |
|---|---|
| **STOP** (centro) | Manda el comando de parada y suelta cualquier stick/cruceta que estuviera apretado |
| ⚙ | Cambia qué letra manda cada control (se guarda en el teléfono) |
| ☰ | Registro de lo enviado (→) y recibido (←) — útil para depurar |
| ↻ | Reconectar |

## 3. Comandos por defecto

| Control | Manda |
|---|---|
| Stick izquierdo **o** cruceta ↑ ↓ ← → | `F` `B` `L` `R` |
| Soltar stick / cruceta | `S` |
| Botón STOP | `S` |
| Stick derecho, ✕ ○ □ △, L1 R1 L2 R2, Share, Options | **nada** (vacío) hasta que los configures en ⚙ |

* El comando se **repite cada 120 ms** mientras mantienes el stick inclinado (configurable; 0 = no repetir).
* La cruceta tiene prioridad sobre el stick izquierdo.
* En ⚙ puedes escribir `X|x` para mandar `X` al presionar y `x` al soltar.
* Opcionalmente agrega un salto de línea (`\n`) a cada comando, y activa diagonales dándoles un comando.
* **Las letras F/B/L/R/S son las habituales; deben coincidir con tu sketch de Arduino.** Si tu robot usa otras, cámbialas en ⚙.

### Ejemplo mínimo en Arduino (HC‑05 en `Serial1`, p. ej. Mega pines 18/19)

```cpp
unsigned long ultimoComando = 0;

void setup() {
  Serial1.begin(9600);          // velocidad habitual del HC-05 en modo datos
}

void loop() {
  if (Serial1.available()) {
    char c = Serial1.read();
    ultimoComando = millis();
    switch (c) {
      case 'F': /* adelante  */ break;
      case 'B': /* atrás     */ break;
      case 'L': /* izquierda */ break;
      case 'R': /* derecha   */ break;
      case 'S': /* parar     */ break;
    }
  }
  // Recomendado: como la app repite el comando, si pasan 500 ms sin recibir nada, parar.
  if (millis() - ultimoComando > 500) { /* parar motores */ }
}
```

## 4. Seguridad (importante si el robot tiene cuchillas)

* Al **salir de la pantalla del mando** (botón inicio, llamada, bloqueo) o **perder la conexión**, la app manda el comando de parada.
* Si la conexión se corta, la app intenta reconectar sola hasta 3 veces; **no retoma el movimiento por sí sola**.
* Prueba siempre primero con el robot **levantado del suelo** y las cuchillas desconectadas.

## 5. Probar la lógica sin Android

```
./tools/run-tests.sh
```
Necesita solo Java. Comprueba zonas muertas, direcciones, prioridad de la cruceta, repetición, parada, etc.

## 6. Estructura

```
app/src/main/java/com/gamepadbt/controller/
  DeviceActivity.java    lista de dispositivos emparejados + permisos
  GamepadActivity.java   pantalla del mando, ajustes, registro, ciclo de vida
  GamepadView.java       dibujo del mando y detección multitáctil
  ControlLogic.java      traduce toques en comandos (Java puro, con pruebas)
  CommandMap.java        tabla de comandos y direcciones de los sticks (Java puro)
  BluetoothLink.java     conexión SPP/RFCOMM, reintentos, lectura y escritura
```
