# Plan de desarrollo y estado real

Leyenda: ✅ completada y verificada en este entorno · 🟡 implementada, verificación en hardware pendiente ·
⛔ bloqueada.

| Fase | Contenido | Estado | Verificación realizada |
|---|---|---|---|
| 0 | Auditoría del repositorio | ✅ | Repositorio vacío (sin commits ni ramas remotas). Rama `feature/flex-heart-monitor` creada. |
| 1 | Arquitectura y documentación | ✅ | Gradle multi-módulo, catálogo de versiones verificado contra Maven. |
| 2 | Prueba real del sensor SM-R950 | ⛔ | Sin reloj físico en este entorno. Procedimiento en TESTING.md (P-01…P-04). |
| 3 | Flex Heart Watch | 🟡 | Compila (debug y release R8), lint sin incidencias, pruebas JVM de `PacketFactory`. APIs de Health Services verificadas con `javap` sobre `health-services-client:1.1.0`. |
| 4 | Comunicación Bluetooth (Data Layer) | 🟡 | Compila; APIs verificadas con `javap` sobre `play-services-wearable:20.0.1`. Requiere reloj + teléfono emparejados. |
| 5 | Wi-Fi directa a tablet | 🟡 | 7 pruebas de integración sobre TCP real (emparejamiento, autenticación, cifrado, rechazo, reconexión). mDNS y radio Wi-Fi del reloj pendientes en hardware. |
| 6 | Motor de datos compartido | ✅ | 13 pruebas (umbrales 3/5 s, duplicados, desorden, atrasos, ventanas 15/30/60/120 s, memoria acotada). |
| 7 | Interfaz Liquid Glass | 🟡 | Capturas renderizadas con Robolectric (móvil, tablet, Android 11); se corrigieron defectos visibles en ellas. Desenfoque real pendiente de ver en dispositivo. |
| 8 | Overlay redimensionable | 🟡 | 6 pruebas de geometría + 9 pruebas Robolectric del overlay real (arrastre, límites, bloqueo, menú, redimensionado, rotación, modo juego, persistencia, sin permiso). Sobre Geometry Dash: pendiente. |
| 9 | Gráfica en tiempo real | ✅ | Pruebas de `SparklineLayout` (cortes en huecos, sin puntos inventados) y del historial del motor. |
| 10 | Integración | 🟡 | Las 3 APK compilan; prueba JVM reloj→motor del receptor con paquetes del `PacketFactory` real. Extremo a extremo en hardware pendiente. |
| 11 | Optimización y pruebas finales | 🟡 | Lint limpio en todos los módulos Android. FPS/CPU/batería **no medidos** (sin hardware); procedimiento de medición en TESTING.md. |

| 12 | Flex Link Dual (BLE) | 🟡 | Protocolo/seguridad/reconexión sobre GATT simulado (12 pruebas). Radio real pendiente (P-21). |
| 13 | Lag del reloj | 🟡 | Causas L1–L5 corregidas; release firmada con perfiles; medición en el reloj pendiente (P-22). |

## Próximos pasos (requieren hardware)

1. Ejecutar P-01…P-20 de TESTING.md con los tres dispositivos y anotar resultados.
2. Medir la frecuencia real de entrega de ExerciseClient con pantalla encendida/apagada y con «Baja latencia».
3. Medir el impacto del overlay en Geometry Dash (`dumpsys gfxinfo`).
4. Si Samsung concede acceso al Health Sensor SDK, añadir `SamsungHeartRateSource` implementando `HeartRateSource`.
