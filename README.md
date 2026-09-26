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
service/      Lógica de negocio por módulo: admin, auth, catalog, dispatch, parcel, payment, ticket, trip, user
repository/   Spring Data JPA (consultas derivadas y JPQL)
entity/       Entidades JPA
dto/          Records de request/response y mappers MapStruct por módulo
exception/    Excepciones de negocio + GlobalExceptionHandler (respuesta de error uniforme)
config/       SecurityConfig, filtro JWT, UserDetailsService, OpenAPI
util/         JwtTokenProvider, OtpGenerator, QrCodeGenerator
```

### Entidades

`User`, `Bus`, `Seat`, `Route`, `Stop`, `FareRule`, `Trip`, `Assignment`, `SeatHold`, `Ticket`, `Baggage`, `Parcel`, `Incident`, `Config`.

Estados principales:

- **Trip:** `SCHEDULED → BOARDING → DEPARTED → ARRIVED` (o `CANCELLED`)
- **Ticket:** `SOLD`, `CANCELLED`, `NO_SHOW`
- **SeatHold:** `HOLD` (10 min por defecto), `EXPIRED`, `SOLD`. Es por tramo, igual que la venta
- **Parcel:** `CREATED → IN_TRANSIT → DELIVERED` (solo con OTP); `FAILED` con reintento a `IN_TRANSIT`

### Roles

| Rol | Qué puede hacer |
|---|---|
| `PASSENGER` | Buscar viajes, reservar asiento, comprar y cancelar sus tiquetes |
| `CLERK` | Vender, confirmar pagos, registrar encomiendas, cierre de caja |
| `DRIVER` | Ver su asignación, registrar el abordaje por QR, entregar encomiendas, dar salida al viaje |
| `DISPATCHER` | Asignar bus/conductor, checklist, abrir/cerrar abordaje, ver ocupación |
| `ADMIN` | Catálogo (rutas, paradas, buses), viajes, configuración y creación de usuarios con rol |

El registro público (`/api/v1/auth/register`) siempre crea usuarios `PASSENGER`; solo un `ADMIN` autenticado puede registrar otros roles.

## Reglas de negocio destacadas

- **Venta por tramos:** un asiento puede venderse varias veces en el mismo viaje si los tramos no se solapan. El solapamiento se calcula con el **orden** de las paradas (`desde < hastaOtro && hasta > desdeOtro`).
- **Reserva temporal (hold):** bloquea el asiento **solo en el tramo solicitado** (`fromStopId` → `toStopId`) durante `hold.duration.minutes` (10 por defecto). Solo se permite en viajes `SCHEDULED`. Si el mismo usuario reserva otro tramo que se solapa, el hold anterior se reemplaza. Al comprar, los holds del pasajero pasan a `SOLD`. Una tarea programada expira los holds vencidos cada 60 s.
- **Sobreventa:** se permite vender hasta `capacidad × overbooking.max.percentage` (5 % por defecto) asientos adicionales. Los números de asiento válidos van de 1 a `capacidad + ⌊capacidad × %⌋`.
- **Precio dinámico:** precio base × multiplicadores por hora pico y por demanda del viaje, con descuentos por tipo de pasajero.
- **Cancelación de tiquetes:** reembolso escalonado según la anticipación (48 h, 24 h, 12 h, 6 h, menos de 6 h). No se puede cancelar si el viaje ya salió o si el pasajero ya abordó.
- **Cancelación de viajes:** al cancelar un viaje se cancelan todos sus tiquetes vendidos y se liberan sus holds activos.
- **Abordaje y no-show:** el conductor o despachador registra el abordaje escaneando el QR (`boardedAt`). Se permite con el viaje en `BOARDING` y también en `DEPARTED`, para quien sube en paradas intermedias. Cada 5 minutos, los tiquetes **sin abordar** de pasajeros que suben en la parada de origen pasan a `NO_SHOW` cuando faltan 5 minutos o menos para la salida.
- **Lista de pasajeros por tramo:** incluye a todos los que van a bordo en algún punto del tramo, no solo a quienes tienen exactamente ese origen y destino.
- **Buses disponibles:** para una fecha, se listan los buses `ACTIVE` que no tienen otro viaje (no cancelado) ese día.
- **Encomiendas:** código de rastreo público (sin exponer el OTP) y entrega con OTP de 6 dígitos + foto. Un OTP inválido marca la encomienda como `FAILED` y registra un incidente.
- **Despacho:** para dar salida, el viaje necesita una asignación con checklist aprobado. El bus debe tener SOAT y revisión técnica vigentes.
- **Cierre de caja:** suma los tiquetes en efectivo vendidos en el rango y devuelve la diferencia contra el monto contado.

## Endpoints

Base: `/api/v1`. Todos, salvo los marcados como públicos, requieren `Authorization: Bearer <token>`.

| Método | Ruta | Roles |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | Público |
| GET / PUT | `/admin/config` | ADMIN |
| GET | `/routes`, `/routes/{id}`, `/routes/{id}/stops` | Público |
| POST / PUT / DELETE | `/routes`, `/routes/{id}` | ADMIN |
| POST / DELETE | `/routes/{routeId}/stops`, `/routes/{routeId}/stops/{stopId}` | ADMIN |
| GET | `/buses`, `/buses/{id}`, `/buses/plate/{plate}`, `/buses/available` | ADMIN, DISPATCHER |
| POST / PUT / DELETE | `/buses`, `/buses/{id}` | ADMIN |
| GET | `/trips`, `/trips/{id}`, `/trips/{id}/seats` | Público |
| GET | `/trips/{tripId}/passengers` | DRIVER, DISPATCHER |
| POST / PUT / DELETE | `/trips`, `/trips/{id}/status`, `/trips/{id}` | ADMIN |
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
| POST | `/trips/{tripId}/depart` | DRIVER |
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
- `403`: rol insuficiente.
- `404`: recurso inexistente.
- `409`: conflicto o dato duplicado.
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
| Integración / historias de usuario | `src/test/java/com/web/integration/**` | Flujos completos HTTP → BD por rol, y regresiones de los errores corregidos |

- Los tests de repositorio e integración usan **Testcontainers** (`postgres:15-alpine`) y se **omiten automáticamente** si no hay Docker (`@Testcontainers(disabledWithoutDocker = true)`).
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

## Pendiente de decisión

Estos puntos no se modificaron porque requieren decisiones de negocio:

- **Propiedad de los tiquetes:** `GET /tickets/{id}` y `POST /tickets/{id}/cancel` no verifican que el tiquete sea del usuario autenticado. Además, la compra y el hold toman el `passengerId`/`userId` del body, así que un pasajero podría comprar o reservar a nombre de otro. Hay que definir si la taquilla vende en nombre de terceros y restringirlo al resto de roles.
- **Cierre de caja por cajero:** `closeCash` suma el efectivo de todos los cajeros del día; no se puede cuadrar la caja de uno solo.
- **Precio:** `calculateFinalPrice` ignora `FareRule.dynamicPricingEnabled` y `FareRule.discounts`.
- **Holds y sobreventa:** los asientos del margen de sobreventa se pueden comprar pero no reservar.
- **Cancelar con el viaje cancelado:** si el viaje se cancela, los tiquetes ya quedan cancelados, pero el reembolso total no se registra en ninguna parte (no hay entidad de pagos o reembolsos).
- **Despacho:** `PUT /trips/{id}/assignment` permite modificar el checklist de un viaje que ya salió, y `assignTrip` toma el `dispatcherId` del body y no del usuario autenticado.
- **No-show en paradas intermedias:** no se marcan porque no hay hora estimada por parada.
