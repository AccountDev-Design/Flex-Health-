# Compatibilidad de dispositivos

## Configuración de compilación

| | Reloj (`:wear`) | Móvil/Tablet (`:receiver`) |
|---|---|---|
| `minSdk` | 30 (Wear OS 3) | 30 (Android 11) |
| `targetSdk` | 36 (Wear OS 6 / Android 16) | 36 |
| `compileSdk` | 37 | 37 |
| applicationId | `com.flexhealth.heartmonitor` | `…heartmonitor` (mobile) · `…heartmonitor.tablet` (tablet) |

## Versiones de los dispositivos objetivo

**No se ha podido detectar la versión real de firmware** de ningún dispositivo: no hay hardware conectado a este
entorno. La tabla resume las versiones de lanzamiento públicamente conocidas y **debe confirmarse** con los
comandos de abajo. La app no depende de una versión concreta: detecta capacidades en tiempo de ejecución y la
pantalla *Diagnóstico* muestra modelo y versión Android reales.

| Dispositivo | Lanzado con | Actualizaciones conocidas | Confirmar con |
|---|---|---|---|
| Galaxy Watch6 Classic SM-R950 (43 mm, BT/Wi-Fi) | Wear OS 4 (API 33), One UI 5 Watch | One UI 6 Watch (Wear OS 5, API 34); One UI 8 Watch (Wear OS 6, API 36) | `adb -s <reloj> shell getprop ro.build.version.sdk` |
| Galaxy A55 5G | Android 14 (One UI 6.1) | One UI 7 (Android 15) y posteriores | `adb shell getprop ro.build.version.release` |
| Galaxy Tab S10 FE | Android 15 (One UI 7) | One UI 8 (Android 16) | `adb shell getprop ro.product.model` |

Cobertura: con `minSdk 30` la app funciona en todas esas versiones. Los permisos y tipos de servicio se
adaptan por versión (abajo).

## Acceso al sensor: alternativas evaluadas

| Opción | Estado | Notas verificadas |
|---|---|---|
| **Health Services ExerciseClient** | ✅ Implementado (por defecto) | Dependencia oficial `androidx.health:health-services-client:1.1.0`. Requiere servicio en primer plano (`health`). Un único ejercicio por dispositivo: si otra app empieza uno, el nuestro termina (`AUTO_END_SUPERSEDED`) y viceversa. Con la pantalla apagada las entregas se agrupan (la documentación cita ejemplos de hasta 150 s); `BatchingMode.HEART_RATE_5_SECONDS` se solicita si `supportedBatchingModeOverrides` lo incluye. **No se promete 1 Hz.** |
| **Health Services MeasureClient** | ✅ Implementado (opcional) | Pensado para lecturas rápidas en primer plano; no apto para sesiones largas con la pantalla apagada. No crea ejercicio (no interfiere con Samsung Health). |
| **PassiveMonitoringClient** | ❌ Descartado | Entregas infrecuentes y en lote: no sirve para tiempo real. |
| **Samsung Health Sensor SDK** | ⛔ No incluido | Ver abajo. |

### Samsung Health Sensor SDK — bloqueo de acceso

- Se distribuye como **AAR descargable** desde Samsung Developers (no está en Maven), por lo que no puede
  añadirse como dependencia verificable en este repositorio.
- Para **distribuir** una app que lo use hay que solicitar acceso al **Samsung Partner Program** y obtener
  aprobación.
- Para **desarrollo/pruebas**, la documentación de Samsung describe activar el *Developer Mode* de la
  plataforma de salud en el reloj: *Ajustes › Aplicaciones › Health Platform* y tocar repetidamente el título
  «Health Platform» hasta que aparezca «[Dev mode]». **No se ha podido re-verificar este procedimiento**: el
  dominio developer.samsung.com no era accesible desde este entorno. Confírmalo en la guía oficial antes de usarlo.
- Samsung publicó (abril de 2026) una guía sobre FC continua con la pantalla apagada; no se pudo leer por el
  mismo bloqueo de red.
- **No se simula** que el SDK esté autorizado. Integración futura: implementar `HeartRateSource` en una clase
  `SamsungHeartRateSource` y registrarla en `MonitoringService`.

## Permisos

### Reloj

| Permiso | Cuándo | Motivo |
|---|---|---|
| `BODY_SENSORS` (`maxSdkVersion=35`) | Runtime, hasta Wear OS 5 | Leer FC con Health Services. |
| `android.permission.health.READ_HEART_RATE` | Runtime, Wear OS 6 (API 36) | Sustituye a `BODY_SENSORS` en Wear OS 6. |
| `POST_NOTIFICATIONS` | Runtime, API 33+ | Notificación del servicio (el servicio funciona sin ella). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH` | Instalación | Servicio de tipo `health` (API 34+; en API 30–33 se arranca sin tipo). |
| `WAKE_LOCK` | Instalación | Solo con «Baja latencia» activado (límite 4 h). |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | Instalación | Socket en la red local y `requestNetwork(TRANSPORT_WIFI)`. Ningún tráfico va a Internet. |

### Móvil / Tablet

| Permiso | Motivo |
|---|---|
| `SYSTEM_ALERT_WINDOW` (especial) | Overlay `TYPE_APPLICATION_OVERLAY`. Se concede en Ajustes desde la pantalla *Permisos*. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Servicio `connectedDevice`. |
| `CHANGE_NETWORK_STATE`, `ACCESS_NETWORK_STATE` | Requisito previo del tipo `connectedDevice`; mostrar la IP local. |
| `INTERNET` | Abrir el servidor WebSocket local (Android exige este permiso para cualquier socket). |
| `POST_NOTIFICATIONS` (opcional) | Accesos rápidos en la notificación. |

## Dependencias (verificadas en maven.google.com / Maven Central el 2026-11-08)

AGP 9.5.1 · Gradle 9.9.0 · Kotlin 2.4.21 · Compose BOM 2026.09.00 · Wear Compose Material3 1.7.1 ·
Health Services 1.1.0 · play-services-wearable 20.0.1 · kotlinx-serialization 1.12.0 · coroutines 1.11.0 ·
OkHttp 5.5.1 · Java-WebSocket 1.6.0 · DataStore 1.3.0 · Robolectric 4.17. Lista completa en
`gradle/libs.versions.toml`.
