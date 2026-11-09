# Protocolo de comunicación (v1)

## Paquete de frecuencia cardíaca

`HeartRatePacket` (JSON, `kotlinx.serialization`), común a Data Layer y red local:

```json
{
  "protocolVersion": 1,
  "sessionId": "s-3f9c0e1a2b4d5e6f708192a3",
  "sequence": 125,
  "device": "SM-R950",
  "metric": "heart_rate",
  "bpm": 92,
  "timestampMs": 1794150000000,
  "sensorStatus": "active",
  "quality": "high",
  "source": "exercise"
}
```

| Campo | Regla |
|---|---|
| `protocolVersion` | Debe estar en `1..1`; otra versión se rechaza con error explícito. |
| `sessionId` | `[A-Za-z0-9_-]{8,64}`, aleatorio por sesión (96 bits). Cambiarlo reinicia el historial del receptor. |
| `sequence` | ≥ 0, monótono dentro de la sesión (incluye paquetes de estado). |
| `bpm` | `null`/ausente en paquetes de **estado**; si existe, 25–250. El reloj **nunca** repite ni inventa valores. |
| `timestampMs` | Hora de **medición** (epoch ms del reloj), ≥ 2020-01-01. |
| `sensorStatus` | `active · acquiring · off_body · unavailable · permission_denied · busy · stopped · error · unknown` |
| `quality` | `high · medium · low · unreliable · unknown` (de `HeartRateAccuracy`). |

**Compatibilidad futura:** se ignoran claves desconocidas y los valores de enum desconocidos se leen como
`unknown`. Los cambios aditivos no cambian la versión; los incompatibles la incrementan.

## Control en el receptor (`HeartRateEngine`)

1. **Validación** (`PacketValidator`): inválidos → contador `invalid`.
2. **Secuencias** (`SequenceTracker`, ventana de 64): duplicados descartados; desordenados aceptados en el
   historial sin cambiar el BPM actual; huecos → contador `lost`; anteriores a la ventana → `tooOld`.
3. **Tiempo** (`ClockSync`): desfase = mínimo deslizante (30 muestras) de `recepción − medición`. Retraso de
   cada muestra = diferencia respecto a ese mínimo. Retraso ≥ 2,5 s → lectura **atrasada** (se dibuja en su
   instante real y se cuenta). Las posiciones en la gráfica usan el reloj monotónico del receptor.
4. **Precisión (desde 2026-11-09)**:
   - Un paquete de estado más reciente que la última lectura que indique que el sensor no mide (fuera de la
     muñeca, buscando pulso, sin permiso, ocupado, error, detenido) **oculta el BPM al momento**.
   - Calidad `unreliable`: la lectura real se muestra marcada como «No fiable», sin entrar en mín/media/máx
     ni en la línea de la gráfica.
   - Saltos inverosímiles (> max(30 BPM, 12 BPM/s × Δt) respecto de la última lectura fiable de los
     últimos 10 s) quedan retenidos («Verificando»): si la siguiente muestra lo confirma (±15 BPM en ≤ 5 s)
     se guardan ambas; si no, el pico se descarta y se cuenta. No se interpola ni se sustituye nada.
   - El reloj descarta en origen lecturas con marca temporal repetida o anterior.
5. **Actualidad**: < 3 s `LIVE`, 3–5 s `STALE` (datos marcados como desactualizados), ≥ 5 s `LOST`
   (se oculta el BPM). Al reconectar se continúa con datos nuevos; no se reenvía nada antiguo.

## Modo smartphone — Wear Data Layer

- Ruta: `MessageClient` → `/flexheart/v1/hr`, cuerpo = JSON del paquete.
- El reloj envía a los nodos que anuncian la capability `flex_heart_receiver` (`FILTER_REACHABLE`).
- La plataforma solo entrega mensajes entre apps con el **mismo paquete y la misma firma** y cifra el canal
  (Bluetooth; o nube de Google con cifrado extremo a extremo cuando no hay Bluetooth). Por eso no se añade
  cifrado propio aquí. `Node.isNearby` se muestra como «Conexión directa».

## Modo tablet — WebSocket local

- Servidor en la tablet: `ws://<ip>:47820/flexheart` (si el puerto está ocupado, uno efímero).
- Descubrimiento: mDNS/DNS-SD `_flexheart._tcp.`, TXT `rid=<id del receptor>`, `v=1`. Alternativa: IP manual.
- **Network Security Config (reloj):** el cliente WebSocket (OkHttp) está sujeto a la política de tráfico en
  claro de Android, que no admite rangos de IP. La app del reloj prohíbe el tráfico en claro salvo para el
  dominio `lan.flexheart.local`; el cliente LAN expresa la IP local de la tablet como alias
  (`192.168.1.3` → `ws://192-168-1-3.lan.flexheart.local:47820/flexheart`) y su `Dns` solo resuelve esos
  alias, sin consultar la red, a IP privadas (RFC 1918, ULA), de enlace local o loopback. Además
  `WsConnection` rechaza cualquier destino que no sea un alias local antes de abrir el socket. El receptor
  (servidor) no abre conexiones en claro y mantiene el bloqueo por defecto en ambos *flavors*.
- En el reloj la red se obtiene con `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)` sin exigir
  `NET_CAPABILITY_INTERNET`, y el socket se ata a esa red (no sale por el proxy Bluetooth del teléfono).
- Límites: tramas ≤ 8 KB, autenticación en ≤ 10 s, 5 fallos/min por IP → bloqueo temporal, un solo reloj
  activo (una reconexión del mismo reloj sustituye a la anterior; otro reloj recibe `busy`).
- **Vida del enlace (desde 2026-11-09):** un único mecanismo, tolerante a la suspensión del reloj y al ahorro
  de energía del Wi-Fi:
  - Reloj: ping **cifrado** cada 10 s; si no llega ninguna trama de la tablet en 35 s → reconexión. OkHttp ya
    no usa `pingInterval` (fallaba en cuanto un PONG llegaba tarde).
  - Tablet: cierra una sesión autenticada si no llega **ninguna** trama del reloj en 45 s. La detección de
    Java-WebSocket (`connectionLostTimeout`) está desactivada porque solo contaba PONG de WebSocket y cortaba
    relojes activos con la CPU suspendida.
  - Reconexión: primero la última IP que funcionó y, si falla, mDNS; *backoff* 1→15 s que se omite en cuanto
    vuelve la red Wi-Fi del reloj.

### Emparejamiento (comparación numérica con compromiso)

Solo se acepta mientras el usuario mantiene abierta la ventana de emparejamiento (120 s) en la tablet.

```
Reloj (W)                                    Tablet (T)
pair_hello   {pubW}                 ───▶
                                    ◀───     pair_commit {pubT, C = SHA256(pubT, pubW, nT)}
pair_nonce   {nW}                   ───▶
                                    ◀───     pair_reveal {nT}            W verifica C
        Ambos muestran code = 6 dígitos de SHA256(pubW, pubT, nW, nT, idW, idT)
pair_confirm {HMAC_K("initiator")}  ───▶     (usuario confirma en el reloj)
                                    ◀───     pair_result {accepted, HMAC_K("responder")}  (usuario acepta en la tablet)
```

- ECDH P-256 efímero; `K = HKDF-SHA256(ECDH, salt = transcript, "FHM pairing key v1")`.
- El compromiso impide que un atacante intermedio elija nonces para forzar códigos iguales: probabilidad de
  éxito 10⁻⁶ por intento, y cada intento exige acción del usuario.
- `K` se guarda cifrada con una clave AES-GCM **no exportable del Android Keystore**, en `noBackupFilesDir`.
  No hay secretos en el código fuente.

### Sesión autenticada

```
hello       {pairingId, clientNonce(16 B), clientTimeMs}   ───▶
                                                           ◀───  challenge {serverNonce(16 B), serverTimeMs}
auth        {HMAC(authKey, "client", transcript)}          ───▶
                                                           ◀───  auth_result {ok, HMAC(authKey, "server", transcript)}
secure      {counter, AES-256-GCM(body)}                   ◀──▶
```

- `HKDF(K, salt = SHA256(pairingId, nC, nS))` → claves c→s, s→c y de autenticación (frescas por conexión).
- Autenticación **mutua**: el reloj también verifica a la tablet.
- `SecureChannel`: AES-256-GCM, nonce = contador de 64 bits por dirección, AAD con el contador; el receptor
  exige contadores estrictamente crecientes (anti-replay).
- Cuerpos cifrados: `sample {packet}`, `ping {sentAtMs, lastRttMs}`, `pong {echoSentAtMs}`, `bye`.
- Errores en claro: `unsupported_version`, `pairing_closed`, `unknown_pairing`, `auth_failed`, `busy`,
  `rate_limited`, `rejected`, `timeout`, `protocol_error`. Ante `unknown_pairing`/`auth_failed` el reloj deja
  de reintentar y pide volver a emparejar.
