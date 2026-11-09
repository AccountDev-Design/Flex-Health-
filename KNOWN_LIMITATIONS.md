# Limitaciones conocidas y bloqueos

## Entorno de desarrollo
- **Sin hardware ni emulador.** Ninguna función se ha probado en el SM-R950, el Galaxy A55 5G ni la Galaxy
  Tab S10 FE, y no había virtualización (`/dev/kvm`) para un emulador. Todo lo verificado está en TESTING.md §1.
- No se pudieron leer las páginas de **developer.samsung.com** (bloqueadas por la red del entorno).

## Sensor (reloj)
- **Samsung Health Sensor SDK no integrado**: requiere descargar un AAR fuera de Maven y aprobación del
  Samsung Partner Program para distribuir. Ver DEVICE_COMPATIBILITY.md.
- **No hay garantía de 1 Hz.** ExerciseClient agrupa las entregas con la pantalla apagada. Con
  `HEART_RATE_5_SECONDS` (si el dispositivo lo admite) las muestras pueden llegar en lotes cada ~5 s; el
  receptor puede marcar *STALE* entre lotes aunque las mediciones sean continuas. Las muestras del lote se
  dibujan en su instante real y se cuentan como «atrasadas». «Baja latencia» (*wake lock*) puede reducirlo a
  costa de batería; está sin medir.
- Solo un ejercicio activo por dispositivo: iniciar un entrenamiento en Samsung Health detiene la sesión de
  Flex Heart (se informa con estado `busy`).
- MeasureClient solo entrega datos con la app visible.

## Smartphone (Data Layer)
- Data Layer **no es exclusivamente Bluetooth**: si no hay Bluetooth puede enrutar por Wi-Fi/LTE a través de
  servidores de Google (cifrado extremo a extremo). La app lo indica con «Conexión directa» (`Node.isNearby`).
- **No se implementó un enlace Bluetooth GATT propio.** Wear OS ya mantiene la conexión Bluetooth con el
  teléfono y Data Layer es el canal oficial. Un servidor GATT añadiría otra conexión BLE, permisos
  `BLUETOOTH_*` en ambos lados y un comportamiento de anuncio en Wear OS no verificado, sin cumplir mejor el
  requisito. Si se necesitara una ruta *estrictamente* Bluetooth, esa sería la alternativa.
- La app del teléfono no se abre sola: el servicio receptor debe iniciarse desde la app. Android 12+ limita
  arrancar servicios en primer plano desde segundo plano.
- Reloj y teléfono deben compartir applicationId **y firma** (compilar ambos en la misma máquina).

## Tablet (Wi-Fi local)
- Requiere que el reloj tenga guardada la red Wi-Fi (*Ajustes › Conexiones › Wi-Fi* en el reloj). Si el reloj
  no puede conectarse, `requestNetwork` no responde; la app muestra «Esperando red Wi-Fi del reloj».
- La conexión directa sin teléfono (P-10) y sin Internet (P-11) **está implementada pero sin verificar** en
  el hardware. Si Wear OS no levantara el Wi-Fi sin la condición `INTERNET`, la alternativa documentada es fijar
  el Wi-Fi del reloj en «Activado» o usar un router con salida a Internet. El tráfico sigue siendo local.
- La «Dirección manual» del reloj solo acepta **IP locales** (no nombres de host): la política de tráfico en
  claro solo autoriza los alias de IP privadas (ver CONNECTION_PROTOCOL.md).
- mDNS puede estar bloqueado por algunos routers (aislamiento de clientes o multicast): usa «Dirección manual».
- **Hotspot de la tablet:** no implementado ni verificado. Que el modelo Wi-Fi de la Tab S10 FE ofrezca punto de
  acceso depende del modelo y del firmware. `LocalOnlyHotspot` daría un SSID y una contraseña aleatorios que
  habría que introducir en el reloj. Queda como opción futura tras comprobarlo en el dispositivo.
- El transporte es `ws://` con cifrado y autenticación de aplicación (AES-256-GCM tras ECDH), no TLS.
- Android 17+: si se sube `targetSdk` a 37, podrían aplicarse restricciones nuevas de acceso a red local.
  Revisarlas antes de hacerlo.

## Overlay
- Necesita el permiso «Mostrar sobre otras apps». Android no permite superponerse a **todas** las interfaces:
  la barra de estado, los ajustes del sistema, instaladores, pantallas de bloqueo y contenido protegido
  (`FLAG_SECURE`) pueden quedar por encima o bloquear overlays.
- Desde Android 12, para que los toques atraviesen el widget (modo juego) la opacidad de la ventana se limita a
  `InputManager.getMaximumObscuringOpacityForTouch()` (0,8 por defecto).
- Con «toques a través» activado el widget no recibe gestos: para moverlo, desactívalo desde la app o desde la
  acción **Desbloquear toques** de la notificación.
- Fuera del modo «toques a través», los toques sobre el área del widget (≈190 × 65 dp) no llegan al juego.
- El overlay no usa desenfoque real del contenido de otras apps: la API pública solo ofrece *blur behind* de
  pantalla completa, inaceptable sobre un juego. El efecto vidrio se dibuja con capas translúcidas sin coste
  por fotograma.
- En la app principal, el vidrio desenfoca el **fondo** de la app, no el contenido que pasa por debajo al
  desplazarse; por eso la barra de navegación lleva un velo casi opaco. En las capturas de Robolectric con
  `RenderEffect` aparece una franja vertical dentro de algunas tarjetas que desaparece sin `RenderEffect`
  (API 30). Hay que comprobar en un dispositivo real si se trata solo de un artefacto del renderizador.

## Rendimiento
- **No medido.** El diseño minimiza el trabajo: sin bucles de animación continuos, redibujo con cada muestra
  más 450 ms de animación, búfer circular de tamaño fijo y temporizador de actualidad que solo despierta en las
  transiciones. El impacto real en los FPS de Geometry Dash y en la batería del reloj está pendiente (P-18,
  P-20).

## Release
- Las APK *release* se generan sin firmar (`*-release-unsigned.apk`) y no se han probado. Para producción hay
  que configurar un keystore propio (fuera del repositorio) y verificar las reglas de R8 en un dispositivo.
