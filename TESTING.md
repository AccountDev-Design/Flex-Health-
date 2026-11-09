# Pruebas

## 1. Resumen honesto

| Tipo | Estado |
|---|---|
| Pruebas automatizadas JVM | ✅ 86 pruebas: protocolo 22, motor 22, transporte LAN + BLE 35 (1 omitida si la máquina no tiene IP privada), reloj 7 |
| Pruebas del receptor (Robolectric + configuración) | ✅ 24 pruebas: geometría del overlay 6, overlay real 9, estado del overlay 2, capturas 4, política de tráfico en claro 1, selección de transporte 2 (se ejecutan para ambos *flavors*) |
| Pruebas en emulador | ❌ **No ejecutadas**: el entorno de desarrollo no tiene `/dev/kvm`, así que no puede arrancar un emulador Android. |
| Pruebas en hardware físico | ❌ **No ejecutadas**: no hay acceso al SM-R950, al Galaxy A55 5G ni a la Galaxy Tab S10 FE. |
| Lint | ✅ 0 incidencias en `:wear`, `:receiver` (mobile y tablet) y `:core:android` |
| Compilación | ✅ `assembleDebug` y `assembleRelease` (R8) de las tres APK |

```bash
./gradlew test       # 134 ejecuciones (110 pruebas únicas; las de :receiver corren en mobile y tablet)
```

## 2. Pruebas automatizadas

| Requisito | Prueba |
|---|---|
| Validación de paquetes, datos nulos, compatibilidad futura | `core/protocol/.../PacketTest` |
| Seguridad de mensajes (HKDF RFC 5869, ECDH, MITM, compromiso alterado, replay, manipulación, clave errónea) | `core/protocol/.../CryptoTest` |
| Pérdida de señal 3 s / 5 s, reconexión, duplicados, desorden, lecturas atrasadas, ventanas 15/30/60/120 s, memoria acotada, temporizador sin sondeo | `core/engine/.../EngineTest`, `ComponentsTest` |
| Gráfica sin interpolación ni puntos inventados | `ComponentsTest.sparklineBreaksAtGapsAndNeverInventsPoints` |
| Emparejamiento, autenticación, cifrado, rechazo, tramas no autenticadas, reconexión tras reinicio del servidor | `core/lan/.../LanIntegrationTest` (sockets TCP reales en loopback) |
| Paquetes del reloj aceptados por el motor del receptor | `wear/.../PacketFactoryTest` |
| Tráfico en claro solo hacia IP locales: alias de IP dinámicas, rechazo de IP públicas y nombres manipulados, NSC del reloj, receptor sin excepciones | `core/lan/.../LocalAddressAliasTest`, `LanIntegrationTest.clientRefusesNonLocalDestinationsWithoutConnecting`, `LanIntegrationTest.pairsAndStreamsThroughPrivateLanAddress`, `wear/.../NetworkSecurityConfigTest`, `receiver/.../CleartextPolicyTest` |
| Movimiento, límites de pantalla, bloqueo, pulsación larga → menú, redimensionado desde esquina, rotación, persistencia, modo juego (toques a través, opacidad ≤ 0,8), minimizar, presets, ocultar 30 s, sin permiso | `receiver/.../OverlayControllerTest`, `OverlayGeometryTest` |
| Evidencia de causas de desconexión (Java-WebSocket corta sin PONG; OkHttp falla con un PONG tardío) | `core/lan/.../LivenessEvidenceTest` |
| Reconexiones: actividad sin PONG, sesión silenciosa, red lenta, suspensión corta/larga, reconexión inmediata | `core/lan/.../LanLivenessTest` (proxy TCP con retardo/congelación) |
| Lecturas inválidas: fuera de la muñeca, no fiables, picos aislados, cambios reales confirmados, datos antiguos | `core/engine/.../AccuracyTest`, `wear/.../ReadingGateTest`, `receiver/.../OverlayStatusTest` |
| Rendimiento (JVM, límites holgados): ingesta, fotograma de gráfica, cifrado; UI del reloj sin emisiones invisibles | `PerformanceTest`, `CryptoPerformanceTest`, `wear/.../MonitorUiFlowTest` |
| BLE: fragmentación (MTU 23–517), emparejamiento y datos con MTU mínima, clave falsa, repetición e inyección, cliente sin autenticar, pérdida de notificaciones, corte y reconexión, sesión medio abierta sustituida sin duplicados, inactividad | `core/lan/.../ble/BleFramingTest`, `BleTransportTest` (enlace GATT **simulado**) |
| Benchmark (PC) del trabajo por muestra del reloj | `core/lan/.../ble/WatchPipelineBenchmarkTest` |
| Selección de transporte en la tablet; BLE sin permiso informa del motivo | `receiver/.../TransportSelectionTest` |
| Revisión visual | `receiver/.../ScreenshotTest` → `build/screenshots/*.png` |

## 3. Instalación en los dispositivos

```bash
./gradlew assembleDebug
```

> Instala las APK **release** (`*-release.apk`) para usar y medir; las *debug* son solo para depurar.

### Galaxy Watch6 Classic SM-R950
1. *Ajustes › Acerca del reloj › Software* → toca **Versión de software** 5 veces (opciones de desarrollador).
2. *Ajustes › Opciones de desarrollador*: activa **Depuración ADB** y **Depuración inalámbrica**.
3. Con el PC en la misma Wi-Fi: `adb pair <ip>:<puerto-emparejamiento>` (código del reloj) y `adb connect <ip>:<puerto>`.
4. `adb -s <ip>:<puerto> install -r wear/build/outputs/apk/debug/wear-debug.apk`
5. `adb -s <ip>:<puerto> shell getprop ro.build.version.sdk` — anota la versión real.

### Galaxy A55 5G
1. Opciones de desarrollador + Depuración USB.
2. `adb -s <serie> install -r receiver/build/outputs/apk/mobile/debug/receiver-mobile-debug.apk`
3. El reloj debe estar emparejado con este teléfono en **Galaxy Wearable**, y ambas APK deben salir de la
   misma máquina (misma firma de depuración).

### Galaxy Tab S10 FE
1. Opciones de desarrollador + Depuración USB.
2. `adb -s <serie> install -r receiver/build/outputs/apk/tablet/debug/receiver-tablet-debug.apk`

## 4. Pruebas físicas pendientes (procedimiento)

Marca cada una como ✅/❌ con fecha, versión de firmware y observaciones.

| ID | Prueba | Procedimiento | Criterio de aceptación |
|---|---|---|---|
| P-01 | Permisos del sensor | Primera apertura en el reloj → *Conceder*. Repetir denegando. | Con permiso: arranca. Sin permiso: mensaje claro, no se inicia el servicio. |
| P-02 | Captura real de BPM | Reloj puesto → *Iniciar*. Comparar con la app de FC de Samsung. | Diferencia típica ≤ ±5 BPM en reposo. |
| P-03 | Frecuencia de entrega | Diagnóstico de la tablet durante 5 min con pantalla del reloj encendida, apagada y con «Baja latencia». | Anotar intervalo medio y «Atrasados». No se exige 1 Hz. |
| P-04 | Fuera de la muñeca | Quitarse el reloj 10 s. | Estado «Fuera de la muñeca», el receptor pasa a STALE y LOST, sin BPM inventados. |
| P-05 | Otra app de ejercicio | Iniciar un entrenamiento en Samsung Health y luego Flex Heart. | Aparece «Otra app está usando el sensor» y la opción explícita de iniciar igualmente. |
| P-06 | Reloj → smartphone | A55 en modo Smartphone, *Iniciar* en ambos. | BPM en ≤ 5 s; «Conexión directa: Sí». |
| P-07 | Desconexión Bluetooth | Apagar el Bluetooth del A55 30 s y reactivarlo. | STALE → LOST → reanuda solo con datos nuevos. |
| P-08 | Emparejamiento tablet | Tablet: Conexión › Emparejar. Reloj: Ajustes › Emparejar tablet. | Mismo código de 6 dígitos en ambos; tras aceptar, el reloj aparece en «Relojes emparejados». |
| P-09 | Reloj → tablet por Wi-Fi | Activar «Tablet (Wi-Fi directo)» en el reloj y *Iniciar*. | Estado «Conectado», RTT visible, BPM en vivo. |
| P-10 | **Sin teléfono** | Apagar el A55 (o su Bluetooth) y repetir P-09. | Funciona igual. Si no, documentar el bloqueo exacto. |
| P-11 | **Sin Internet** | Router sin WAN (o desconectado de Internet) y repetir P-09. | Funciona igual. |
| P-12 | mDNS bloqueado | Red con aislamiento multicast: usar «Dirección manual» con la IP mostrada en la tablet. | Conecta con la IP manual. |
| P-13 | Reconexión Wi-Fi | Apagar el Wi-Fi de la tablet 20 s y reactivarlo. | El reloj reconecta solo (backoff ≤ 15 s). |
| P-14 | Seguridad | Desde un PC: `websocat ws://<ip>:47820/flexheart` y enviar texto. | Conexión cerrada, sin datos en la tablet; tras 5 intentos `rate_limited`. |
| P-15 | Overlay: mover/redimensionar/menú | Mover a las 4 esquinas, redimensionar desde bordes y esquinas, abrir menú. | Nunca sale de la pantalla; tamaños dentro de 120–420 × 44–180 dp. |
| P-16 | Rotación y persistencia | Rotar, cerrar la app, reiniciar el servicio. | Recupera posición y tamaño por orientación. |
| P-17 | **Overlay sobre Geometry Dash** | Modo juego activado, jugar un nivel completo. | El widget se ve; los toques sobre él llegan al juego; sin menús permanentes. |
| P-18 | Rendimiento en el juego | `adb shell dumpsys gfxinfo com.robtopx.geometryjump reset`, jugar 2 min con y sin overlay, `dumpsys gfxinfo … framestats`. | Diferencia de *janky frames* y percentil 90 anotada. |
| P-19 | Segundo plano | Cerrar la app receptora (deslizar en recientes) con el servicio activo 30 min. | El overlay y la recepción continúan. |
| P-20 | Sesión prolongada | 2 h de sesión. Reloj: `adb shell dumpsys batterystats`; receptor: `dumpsys meminfo com.flexhealth.heartmonitor.tablet` cada 30 min. | Memoria estable (sin crecimiento); consumo de batería anotado. |
| P-21 | **BLE reloj → tablet** | Tablet en «Tablet · BLE» (+ permiso Bluetooth cercano). Reloj: Emparejar tablet › Bluetooth, o Ajustes › Conexión con la tablet › Bluetooth LE si ya estaba emparejado por Wi-Fi. Wi-Fi de ambos apagado. | Conectado, BPM en vivo, sin router ni teléfono. Repetir alejándose hasta perder el enlace y volviendo: reconexión automática. |
| P-22 | **Fluidez del reloj (release)** | Instalar `wear-release.apk`. `adb shell cmd package compile -m speed-profile -f com.flexhealth.heartmonitor` (o esperar al dexopt en reposo) y comprobar `adb shell dumpsys package dexopt \| grep -A2 heartmonitor` → `speed-profile`. Navegar 1 min y `adb shell dumpsys gfxinfo com.flexhealth.heartmonitor framestats`. Repetir con la APK debug. | Anotar *janky frames* y p90/p95 de ambas. Se espera una mejora clara con release; **no medida aún**. |

