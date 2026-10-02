# Sistema de Buses Intermunicipales — Proyecto Final

API REST para la operación de una empresa de transporte intermunicipal: catálogo de rutas y paradas, flota de buses, programación de viajes, venta de tiquetes por tramos con reserva temporal de asientos y sobreventa controlada, encomiendas con entrega por OTP, despacho (asignación de conductor, abordaje y salida), cierre de caja y configuración de reglas de negocio.

## Stack

| Componente | Versión / librería |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.5.7 (Web, Data JPA, Security, Validation) |
| Base de datos | PostgreSQL 16 (Docker) |
| Migraciones | Flyway (`ddl-auto=validate`) |
| Seguridad | JWT (`com.auth0:java-jwt`, HMAC512) + `@PreAuthorize` por rol |
| Mapeo DTO ↔ entidad | MapStruct 1.6.3 + Lombok |
| Documentación | springdoc-openapi (Swagger UI) |
| Tests | JUnit 5, Mockito, AssertJ, `@WebMvcTest`, Testcontainers, JaCoCo |

## Arquitectura

Arquitectura por capas dentro del paquete `com.web`:

```
controller/   Endpoints REST (validación con @Valid, autorización con @PreAuthorize)
service/      Lógica de negocio por módulo: admin, auth, catalog, dispatch, incident, parcel, payment, ticket, trip, user
repository/   Spring Data JPA (consultas derivadas y JPQL)
entity/       Entidades JPA
dto/          Records de request/response y mappers MapStruct por módulo
exception/    Excepciones de negocio + GlobalExceptionHandler (respuesta de error uniforme)
config/       SecurityConfig, filtro JWT, UserDetailsService, OpenAPI
util/         JwtTokenProvider, OtpGenerator, QrCodeGenerator
```

### Entidades

`User`, `Bus`, `Seat`, `Route`, `Stop`, `FareRule`, `Trip`, `Assignment`, `SeatHold`, `Ticket`, `Baggage`, `Parcel`, `Incident`, `Config`, `OverbookingPolicy`.

Estados principales:

- **Trip:** `SCHEDULED → BOARDING → DEPARTED → ARRIVED` (o `CANCELLED`)
- **Ticket:** `SOLD`, `CANCELLED`, `NO_SHOW`
- **SeatHold:** `HOLD` (10 min por defecto), `EXPIRED`, `SOLD`. Es por tramo, igual que la venta
- **Parcel:** `CREATED → IN_TRANSIT → DELIVERED` (solo con OTP y foto) o `FAILED` (OTP incorrecto o destinatario ausente). `DELIVERED` y `FAILED` son finales

### Roles

| Rol | Qué puede hacer |
|---|---|
| `PASSENGER` | Buscar viajes, reservar asiento, comprar y cancelar sus tiquetes |
| `CLERK` | Vender, confirmar pagos, registrar encomiendas, cierre de caja, reportar incidentes |
| `DRIVER` | Ver sus asignaciones, registrar el abordaje por QR, entregar encomiendas, dar salida al viaje, reportar incidentes |
| `DISPATCHER` | Asignar bus/conductor, checklist, abrir/cerrar abordaje, ver ocupación, % de overbooking por ruta y franja, reportar y consultar incidentes |
| `ADMIN` | Catálogo (rutas, paradas, buses, tarifas), viajes y su reprogramación, configuración, gestión de usuarios (alta con rol, estado y rol), consulta de incidentes |

El registro público (`/api/v1/auth/register`) siempre crea usuarios `PASSENGER`; solo un `ADMIN` autenticado puede registrar otros roles.

## Reglas de negocio destacadas

- **Venta por tramos:** un asiento puede venderse varias veces en el mismo viaje si los tramos no se solapan. El solapamiento se calcula con el **orden** de las paradas (`desde < hastaOtro && hasta > desdeOtro`).
- **Reserva temporal (hold):** bloquea el asiento **solo en el tramo solicitado** (`fromStopId` → `toStopId`) durante `hold.duration.minutes` (10 por defecto). Solo se permite en viajes `SCHEDULED`. Si el mismo usuario reserva otro tramo que se solapa, el hold anterior se reemplaza. Al comprar, los holds del pasajero pasan a `SOLD`. Una tarea programada expira los holds vencidos cada 60 s.
- **Overbooking controlado:** las sillas por encima de la capacidad solo se venden si un **DISPATCHER** las aprueba (`POST /trips/{id}/overbooking/approve`). Cada aprobación suma **una** silla, siempre que la ocupación supere el 95 % y falten menos de 30 minutos para la salida, hasta `capacidad × porcentaje máximo`. El porcentaje es el de la **política de la ruta** cuya franja contiene la hora de salida (`start_hour <= hora < end_hour`) y, si no hay, `overbooking.max.percentage` (5 % por defecto). Cada aprobación queda registrada como incidente `OVERBOOK`. Sin aprobación, la compra responde **403**
- **% de overbooking por ruta y franja horaria:** un DISPATCHER o ADMIN define franjas `[startHour, endHour)` (0–23 / 1–24, inicio < fin) con un porcentaje entre 0 y 1 (`0.10` = 10 %). Las franjas de una misma ruta no se pueden solapar (**409**)
- **Tarifas y precio dinámico:** si existe una `FareRule` para el tramo, se usa su precio base y sus descuentos. Los multiplicadores por demanda (ocupación **del tramo** > 60 % u 80 %) y por hora pico solo se aplican si la regla tiene `dynamicPricing` activo, o si no hay regla. Descuentos válidos: `STUDENT`, `SENIOR` y `CHILD` (0–100 %); `ADULT` o vacío va sin descuento, y cualquier otro tipo responde 400
- **Gestión de tarifas (ADMIN):** una `FareRule` por tramo (ruta, origen, destino); duplicarla responde **409**. Las paradas deben ser de la ruta y en orden (`origen < destino`), el precio base mayor que 0 y los descuentos solo `STUDENT`/`SENIOR`/`CHILD` con valores de 0 a 100 (las claves se guardan en mayúsculas). La consulta de tarifas de una ruta es pública
- **Gestión de usuarios (ADMIN):** listado filtrable por rol y estado (nunca incluye el hash), cambio de estado y de rol. Un ADMIN no puede desactivarse ni cambiarse el rol a sí mismo (**400**). Un usuario desactivado no puede iniciar sesión y su token deja de funcionar en la siguiente petición; el cambio de rol aplica también de inmediato (el rol se lee de la BD). Cualquier autenticado consulta su perfil (`/users/me`) y cambia su contraseña indicando la actual (**400** si no coincide; la nueva, mínimo 8 caracteres)
- **Incidentes:** DRIVER, DISPATCHER y CLERK reportan incidentes `SECURITY`, `VEHICLE`, `DELIVERY_FAIL` u `OVERBOOK` sobre un viaje, tiquete o encomienda existente (**404** si no existe); quien reporta es el usuario autenticado. ADMIN y DISPATCHER los consultan con filtros por tipo, entidad y fechas (`from`/`to` inclusivas). Un incidente **no cambia el estado** de la entidad: un incidente `VEHICLE` o `SECURITY` sobre un viaje `DEPARTED` solo queda registrado (el estado opcional `INCIDENT` del diagrama no se implementa)
- **Reprogramación de viajes (ADMIN):** solo viajes `SCHEDULED` (**422** en otro estado). Se puede cambiar la salida, la llegada y el bus; la fecha del viaje se toma de la nueva salida. La llegada debe ser posterior a la salida y la nueva salida, futura. El bus debe estar `ACTIVE` y libre ese día (sin contar el propio viaje), y si ya hay tiquetes en sillas que no existen en el bus nuevo (capacidad + overbooking aprobado) responde **409**
- **Asignaciones por usuario:** el conductor consulta las suyas (`/assignments/me`, todas o de una fecha) y el despachador las que hizo (`/assignments`, desde hoy o de una fecha)
- **Cancelación de tiquetes:** reembolso escalonado según la anticipación (48 h, 24 h, 12 h, 6 h, menos de 6 h). No se puede cancelar si el viaje ya salió o si el pasajero ya abordó.
- **Cancelación de viajes:** al cancelar un viaje se cancelan todos sus tiquetes vendidos con reembolso del 100 % y se liberan sus holds activos.
- **Abordaje y no-show:** el conductor asignado o un despachador registra el abordaje escaneando el QR (`boardedAt`). Se permite con el viaje en `BOARDING` y también en `DEPARTED`, para quien sube en paradas intermedias. Los tiquetes sin abordar de la parada de origen pasan a `NO_SHOW` y se les registra el fee configurable (`no.show.fee`) en tres momentos: al cerrar el abordaje, al dar salida y, cada 5 minutos, cuando faltan 5 minutos o menos para salir con el abordaje abierto. Si el pasajero llega antes de la salida y su silla no se revendió, puede abordar igual y se le anula el no-show
- **Lista de pasajeros por tramo:** incluye a todos los que van a bordo en algún punto del tramo, no solo a quienes tienen exactamente ese origen y destino.
- **Buses disponibles:** para una fecha, se listan los buses `ACTIVE` que no tienen otro viaje (no cancelado) ese día.
- **Encomiendas:** código de rastreo público (sin exponer el OTP) y entrega con OTP de 6 dígitos + foto. Un OTP inválido marca la encomienda como `FAILED` y registra un incidente.
- **Despacho:** para dar salida, el viaje necesita una asignación con checklist, SOAT y revisión técnica vigentes. Solo el **conductor asignado** puede dar salida, registrar la llegada, validar QR y ver la lista de pasajeros de su viaje. Los estados `BOARDING` y `DEPARTED` solo se asignan por los endpoints de despacho, y las transiciones inválidas responden 422.
- **Cierre de caja:** efectivo esperado del día = tiquetes en efectivo vendidos ese día (en cualquier estado, porque el dinero se recibió) + cobros por exceso de equipaje − reembolsos en efectivo de ese día. Se devuelve la diferencia contra el monto contado.
- **Concurrencia:** la compra, el hold y la aprobación de overbooking bloquean la fila del viaje (`SELECT … FOR UPDATE`). Dos peticiones simultáneas por la misma silla no pueden venderla dos veces.
- **Métricas:** ocupación por viaje (promedio, p50 y p95), ingresos por método de pago y por canal (taquilla o app), puntualidad de salida y llegada, tasa de no-show, cancelaciones, incidentes y encomiendas entregadas vs fallidas por tramo.

## Endpoints

Base: `/api/v1`. Todos, salvo los marcados como públicos, requieren `Authorization: Bearer <token>`.

| Método | Ruta | Roles |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | Público |
| GET / PUT | `/admin/config` | ADMIN |
| GET | `/admin/metrics?startDate=&endDate=` | ADMIN |
| GET | `/routes`, `/routes/{id}`, `/routes/{id}/stops` | Público |
| POST / PUT / DELETE | `/routes`, `/routes/{id}` | ADMIN |
| POST / DELETE | `/routes/{routeId}/stops`, `/routes/{routeId}/stops/{stopId}` | ADMIN |
| GET | `/routes/{routeId}/fares` (tarifas por tramo) | Público |
| POST | `/routes/{routeId}/fares` | ADMIN |
| PUT / DELETE | `/fares/{id}` | ADMIN |
| GET / POST | `/routes/{routeId}/overbooking-policies` (% de overbooking por franja) | DISPATCHER, ADMIN |
| DELETE | `/overbooking-policies/{id}` | DISPATCHER, ADMIN |
| GET | `/buses`, `/buses/{id}`, `/buses/plate/{plate}`, `/buses/available` | ADMIN, DISPATCHER |
| POST / PUT / DELETE | `/buses`, `/buses/{id}` | ADMIN |
| GET | `/trips`, `/trips/{id}`, `/trips/{id}/seats` | Público |
| GET | `/trips/{tripId}/passengers` | DRIVER, DISPATCHER |
| POST / PUT | `/trips`, `/trips/{id}/status` | ADMIN |
| DELETE | `/trips/{id}` (cancelar) | ADMIN |
| PUT | `/trips/{id}` (reprogramar: salida, llegada, bus) | ADMIN |
| POST | `/trips/{tripId}/seats/{seatNumber}/hold` | Autenticado |
| POST | `/trips/{tripId}/tickets` | Autenticado |
| POST | `/tickets/{id}/cancel` | Autenticado |
| GET | `/tickets/{id}`, `/tickets/my-tickets` | Autenticado |
| GET | `/tickets/qr/{qrCode}` | DRIVER, DISPATCHER, CLERK |
| POST | `/tickets/qr/{qrCode}/board` (registrar abordaje) | DRIVER, DISPATCHER |
| POST | `/trips/{tripId}/assign` | DISPATCHER |
| GET | `/trips/{tripId}/assignment` | DISPATCHER, DRIVER |
| PUT | `/trips/{tripId}/assignment` (checklist / conductor) | DISPATCHER |
| POST | `/trips/{tripId}/boarding/{open\|close}` | DISPATCHER |
| POST | `/trips/{tripId}/depart`, `/trips/{tripId}/arrive` | DRIVER (asignado) |
| POST | `/trips/{tripId}/overbooking/approve` | DISPATCHER |
| GET | `/trips/{tripId}/baggage` (conteo de equipaje) | DISPATCHER, DRIVER, CLERK |
| GET | `/assignments/me?date=` (asignaciones del conductor autenticado) | DRIVER |
| GET | `/assignments?date=` (asignaciones hechas por el despachador autenticado) | DISPATCHER |
| POST | `/incidents` | DRIVER, DISPATCHER, CLERK |
| GET | `/incidents?type=&entityType=&entityId=&from=&to=` | ADMIN, DISPATCHER |
| GET | `/admin/users?role=&status=` | ADMIN |
| PATCH | `/admin/users/{id}/status`, `/admin/users/{id}/role` | ADMIN |
| GET | `/users/me` | Autenticado |
| PUT | `/users/me/password` | Autenticado |
| GET / POST | `/parcels` | CLERK, ADMIN |
| GET | `/parcels/{code}/track` | Público |
| POST | `/parcels/{code}/deliver` | DRIVER, CLERK |
| PUT | `/parcels/{code}/status` | CLERK, DRIVER |
| POST | `/payments/confirm` | CLERK |
| POST | `/cash/close` | CLERK, DRIVER |

La documentación interactiva está en **Swagger UI**: `http://localhost:8080/swagger-ui.html` (OpenAPI en `/v3/api-docs`).

### Formato de error

Todas las excepciones pasan por `GlobalExceptionHandler`:

- `400`: validación, JSON mal formado, parámetros inválidos, reglas de negocio.
- `401`: no autenticado.
- `403`: rol insuficiente, conductor no asignado o política de overbooking.
- `404`: recurso o ruta inexistente.
- `405` / `415`: método HTTP o tipo de contenido no soportado.
- `409`: conflicto: silla ocupada, hold activo, bus ocupado ese día, tarifa duplicada para el tramo, franja de overbooking solapada, sillas vendidas fuera del bus nuevo al reprogramar o dato duplicado.
- `422`: transición de estado inválida.
- `500`: error genérico, sin exponer detalles internos.

## Cómo ejecutarlo

Requisitos: JDK 21 y Docker. Maven no hace falta, porque se usa el wrapper `mvnw`.

```bash
# 1. Base de datos PostgreSQL (puerto 5433, BD ProyectoFinalDB, usuario Cristian / 2242)
docker compose up -d

# 2. Aplicación (Flyway crea el esquema y carga los datos de prueba al arrancar)
./mvnw spring-boot:run
```

La API queda en `http://localhost:8080`.

### Variables de entorno

| Variable | Valor por defecto |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/ProyectoFinalDB` |
| `DB_USERNAME` | `Cristian` |
| `DB_PASSWORD` | `2242` |
| `JWT_SECRET` | Clave de desarrollo. **Hay que cambiarla en producción.** |
| `JWT_EXPIRATION` | `3600000` (1 hora, en ms) |

### Migraciones Flyway

| Versión | Contenido |
|---|---|
| `V1__Create_initial_schema.sql` | Esquema completo: tablas, llaves, índices y restricciones |
| `V2__insert_test_data.sql` | Datos de prueba: usuarios, buses, asientos, rutas, paradas, viajes y configuración |
| `V3__allow_sold_status_in_seat_holds.sql` | Permite el estado `SOLD` en `seat_holds`, necesario para convertir un hold en compra |
| `V4__add_ticket_boarding_and_segment_holds.sql` | Columna `tickets.boarded_at` (registro de abordaje) y `seat_holds.from_stop_id`/`to_stop_id` (holds por tramo) |
| `V5__reset_seed_user_passwords.sql` | Asigna a los usuarios de prueba la contraseña conocida `Password123` |
| `V6__cash_noshow_overbooking_and_punctuality.sql` | Reembolso, fecha de cancelación, fee de no-show y canal en `tickets`; horas reales de salida y llegada y sillas de overbooking aprobadas en `trips` |
| `V10__overbooking_policies.sql` | Tabla `overbooking_policies`: % máximo de overbooking por ruta y franja horaria de salida |

### Usuarios de prueba

Todos usan la contraseña **`Password123`**. Es solo para desarrollo: en producción no se deben cargar estos datos.

| Rol | Email |
|---|---|
| ADMIN | `cmbarrera@gmail.com` |
| PASSENGER | `juan.perez@email.com`, `maria.lopez@email.com`, `carlos.rodriguez@email.com`, `ana.garcia@email.com` |
| CLERK | `clerk1@transport.com`, `clerk2@transport.com` |
| DRIVER | `driver1@transport.com`, `driver2@transport.com`, `driver3@transport.com` |
| DISPATCHER | `dispatcher1@transport.com`, `dispatcher2@transport.com` |

## Tests

```bash
./mvnw test                   # suite completa
./mvnw test -Dtest='*ServiceImplTest'   # solo una familia
```

| Tipo | Ubicación | Qué cubre |
|---|---|---|
| Unitarios de servicios | `src/test/java/com/web/service/**` | Lógica de negocio con Mockito |
| Controllers | `src/test/java/com/web/controller/**` | `@WebMvcTest`: validación, códigos HTTP y autorización por rol |
| Seguridad y utilidades | `config/`, `util/`, `exception/`, `dto/mapper/` | Filtro JWT, tokens, OTP, QR, mappers, excepciones y handler global |
| Repositorios | `src/test/java/com/web/repository/**` | Consultas JPQL contra PostgreSQL real (Testcontainers) |
| Integración / historias de usuario | `src/test/java/com/web/integration/**` | Flujos completos HTTP → BD por rol, requisitos del documento y regresiones de los errores corregidos |
| Concurrencia | `ConcurrentSeatSaleIntegrationTest` | 8 compras u 8 holds simultáneos de la misma silla y tramo: solo uno gana |

- Los tests de repositorio e integración usan **Testcontainers** (`postgres:15-alpine`) y se **omiten automáticamente** si no hay Docker (`@Testcontainers(disabledWithoutDocker = true)`).
- **Estado actual:** 1230 tests, 0 fallos, ejecutados contra PostgreSQL real. Cobertura: 97 % de líneas y 95 % de ramas.
- **Cobertura:** JaCoCo genera el reporte en `target/site/jacoco/index.html` al ejecutar `./mvnw test`. Se excluyen las clases generadas `*MapperImpl`.

## Errores corregidos en la revisión

| Área | Problema | Corrección |
|---|---|---|
| Venta de tiquetes | El solapamiento de tramos comparaba **IDs** de parada en vez de su **orden**, por lo que se podía vender dos veces el mismo asiento | Se compara el orden de las paradas |
| Venta de tiquetes | Se aceptaban números de asiento inexistentes (p. ej. 999) | Rango válido de 1 a capacidad + margen de sobreventa |
| Venta de tiquetes | La demanda del precio dinámico no se calculaba por viaje | Se cuenta con `countSoldSeats(tripId)` |
| Hold → compra | La compra de un asiento reservado fallaba con 500 (el CHECK de BD no permitía `SOLD`) | Migración `V3` |
| Holds | Se podía reservar en viajes ya salidos o cancelados | Solo se permite en viajes `SCHEDULED` |
| Cancelación | Se podían cancelar tiquetes de viajes ya salidos | `400 TRIP_ALREADY_DEPARTED` |
| Seguridad | Cualquiera podía registrarse como `ADMIN` desde el endpoint público | El registro público fuerza `PASSENGER` |
| Seguridad | Los usuarios inactivos seguían autenticándose con su token | El filtro JWT valida `isEnabled()` |
| Seguridad | `updateUser` guardaba la contraseña en texto plano | Se cifra con `PasswordEncoder` |
| Seguridad | El rastreo público de encomiendas exponía el OTP de entrega, y el incidente de entrega fallida también lo guardaba | Respuesta pública sin OTP; incidente sin OTP |
| Encomiendas | Se aceptaban paradas de otra ruta o en sentido inverso | Validación de ruta y orden |
| Encomiendas | Se podía marcar `DELIVERED` sin OTP; no había transiciones de estado; no se guardaba `deliveredAt` | Máquina de estados; `DELIVERED` solo con OTP; se guarda `deliveredAt` |
| Despacho | No existía endpoint para aprobar el checklist, así que ningún viaje podía salir | `GET/PUT /trips/{tripId}/assignment` |
| Despacho | `updateChecklist` ignoraba `driverId`; `getDriverAssignments` ignoraba la fecha | Corregidos |
| Catálogo | `addStop` devolvía la ruta sin la parada nueva | Se añade y ordena en la respuesta |
| Catálogo | `deleteRoute` hacía DELETE físico (violaba llaves foráneas) e ignoraba los viajes de hoy | Borrado lógico; cuenta los viajes desde hoy |
| Pagos | El cierre de caja usaba el monto esperado enviado por el cliente | Se calcula a partir de los tiquetes en efectivo |
| Pagos | `confirmPayment` de un tiquete no vendido devolvía 500 | `409 INVALID_TICKET_STATUS` |
| Errores HTTP | Accesos denegados, JSON inválido, parámetros erróneos y duplicados devolvían 500, y los 500 exponían mensajes internos | Handlers de 403, 400 y 409; 500 genérico |
| Validación | Login y registro aceptaban email vacío o mal formado | `@NotBlank @Email` |
| Configuración | Credenciales de BD y secreto JWT fijos en el código | Variables de entorno con valor por defecto |
| Tests | Tests de Mockito que fallaban según la hora de ejecución o por mezclar matchers; los tests con BD fallaban sin Docker | Horas fijas, `eq()`, y omisión automática sin Docker |
| Encomiendas | Con OTP inválido, la excepción revertía la transacción y se perdían el estado `FAILED` y el incidente | `noRollbackFor = BusinessException.class` |
| No-show | `processNoShows` marcaba `NO_SHOW` a **todos** los pasajeros 5 minutos antes de la salida, porque no existía registro de abordaje | Abordaje por QR (`boarded_at`); solo se marcan los que no abordaron |
| Viajes | `cancelTrip` dejaba los tiquetes del viaje como vendidos | Se cancelan los tiquetes y se liberan los holds, también al pasar el estado a `CANCELLED` |
| Flota | `getAvailableBuses` ignoraba la fecha | Excluye los buses con viaje ese día |
| Despacho | La lista de pasajeros por tramo solo devolvía coincidencias exactas de origen y destino | Solapamiento por orden de parada |
| Holds | `SeatHoldRequest` pedía `fromStopId`/`toStopId` sin usarlos, y el hold bloqueaba el asiento en todo el viaje | Holds por tramo |
| Datos de prueba | No se conocía la contraseña de los usuarios semilla | `V5` asigna `Password123` |

## Cambios según el documento del proyecto y la revisión senior

| Requisito o hallazgo | Implementación |
|---|---|
| Doble venta con peticiones simultáneas (crítico) | Bloqueo por viaje en compra, hold y overbooking, con test de concurrencia |
| El OTP de entrega llegaba al conductor | Solo lo recibe la taquilla al crear la encomienda |
| El rastreo público mostraba teléfonos y nombres | Respuesta pública sin datos personales |
| La ocupación y la demanda se medían sobre todo el viaje | Se miden sobre el tramo |
| No-show irreversible o que nunca se marcaba | Se marca al cerrar el abordaje y al dar salida; el pasajero que llega tarde puede abordar si su silla sigue libre |
| Regla 8: autenticación del DRIVER en la salida | Solo el conductor asignado puede dar salida, registrar la llegada, validar QR y ver la lista de pasajeros |
| El cierre de caja no cuadraba | Incluye el exceso de equipaje y los tiquetes no-show, y resta los reembolsos |
| `GET /admin/metrics` y KPIs del punto 9 | `MetricsService` |
| Regla 4 y caso de uso 3: overbooking con aprobación | `POST /trips/{id}/overbooking/approve` y 403 |
| FareRule con descuentos y dynamicPricing | Aplicados en el cálculo de precio |
| Regla 3: descuentos con validaciones | Tipos válidos y rangos 0–100 |
| Regla 5: fee de no-show | Se registra en cada tiquete marcado como no-show |
| Regla 6: conteo por maletero | `GET /trips/{id}/baggage` |
| Máquina de estados de encomiendas | `FAILED` es final: no hay reintentos que permitan probar el OTP por fuerza bruta |
| Tabla de errores estándar | 403 para overbooking, 422 para transiciones de viaje y 404/405/415 para rutas y métodos |
| Validaciones faltantes | Configuración, creación de viajes (ruta activa, bus libre, fechas coherentes), compra tras la salida y capacidad y peso positivos |

## Pendiente de decisión

Estos puntos no se modificaron porque requieren decisiones de negocio:

- **Propiedad de los tiquetes:** `GET /tickets/{id}` y `POST /tickets/{id}/cancel` no verifican que el tiquete sea del usuario autenticado. Además, la compra y el hold toman el `passengerId`/`userId` del body, así que un pasajero podría comprar o reservar a nombre de otro. Hay que definir si la taquilla vende en nombre de terceros y restringirlo al resto de roles.
- **Cierre de caja por cajero:** `closeCash` suma el efectivo de todos los cajeros del día; no se puede cuadrar la caja de uno solo.
- **Despacho:** `PUT /trips/{id}/assignment` permite modificar el checklist de un viaje que ya salió, y `assignTrip` toma el `dispatcherId` del body y no del usuario autenticado.
- **No-show en paradas intermedias:** no se marcan porque no hay hora estimada por parada.
- **Otros puntos de la revisión senior (prioridad media o baja):**
  - `assignTrip` no verifica si el conductor está activo o ya tiene otro viaje a esa hora (existe `isDriverAvailable` sin usar);
  - no hay tope de holds por usuario;
  - el mapa de asientos no muestra los que están reservados (`HELD`);
  - al cancelar un viaje sus encomiendas no cambian de estado;
  - el email distingue mayúsculas en el registro y el login;
  - `removeStop` borra la parada físicamente;
  - la lista de pasajeros incluye el email.
- **Funcionalidad del documento aún no implementada:**
  - notificaciones simuladas por WhatsApp/SMS;
  - operación offline de taquilla y conductor con `pendingSync` y reconciliación;
