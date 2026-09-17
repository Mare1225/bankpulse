# ADR-003: Data ownership y HOLDs de Events

- **Estado:** Aceptado para HOLD temporal; confirmacion de compra propuesta
- **Fecha:** 2026-09-16
- **Equipo:** Equipo de Eventos

## Contexto

`events-api` administra eventos, venues, localidades y disponibilidad temporal. Un HOLD debe evitar que dos clientes adquieran el mismo asiento durante una ventana corta, sin convertir Redis en el ledger durable de reservas o pagos.

## Decision

Events es el unico writer de `Event`, venue, asiento y disponibilidad durable. PostgreSQL, schema `events`, es la persistencia canonica. Redis contiene unicamente el estado efimero de HOLD, usando `SETNX` y TTL. Un HOLD no equivale a una compra confirmada.

## Source of Truth

- **Persistencia durable:** PostgreSQL schema `events`, usuario `events_owner`.
- **Estado temporal:** Redis, claves `seat-hold:<eventId>:<seatId>`.
- **Writers:** `events-api` para ambos; Payments nunca escribe en Events DB.

## Datos externos requeridos

La confirmacion futura requerira una referencia de pago emitida por Payments. Events almacena la referencia y el estado de la reserva, no la transaccion, saldo o autorizacion completa.

## Integracion

La adquisicion de HOLD es local a Events y atomica en Redis. La confirmacion debe usar API o eventos con timeout, idempotency key, correlacion y compensacion explicita. La liberacion valida que el `holdId` corresponda al valor actual antes de borrar la clave.

## Consistencia

- Competencia por el mismo asiento durante el TTL: fuerte respecto a Redis.
- Evento, mapa de asientos y reserva durable: fuerte dentro de PostgreSQL.
- Confirmacion con Payments: eventual y modelada como una maquina de estados o Saga.
- Redis no es source of truth y su perdida invalida HOLDs activos.

## Seguridad y privacidad

No se almacenan datos financieros en Redis. El endpoint de introspeccion de HOLDs es de laboratorio y no debe exponerse sin autorizacion en produccion. Los usuarios de PostgreSQL estan separados por schema.

## Observabilidad

Metricas: HOLDs creados, conflictos HTTP 409, expiraciones, liberaciones, latencia Redis, errores de PostgreSQL y reservas pendientes de confirmacion. El health check debe distinguir disponibilidad de Redis y PostgreSQL.

## Alternativas consideradas

- **Solo PostgreSQL para el HOLD:** rechazada para este laboratorio; aumenta la contencion de la ventana temporal.
- **Redis como ledger de reservas:** rechazada; la perdida o expiracion de Redis no puede borrar una compra.
- **Acceso directo a Payments DB:** rechazado; Events debe integrar por contrato.
- **Otra tecnologia de cache:** no adoptada; Redis ya ofrece `SETNX` y TTL atomicos.

## Consecuencias

### Positivas

- La carrera por el mismo asiento se resuelve de forma simple y observable.
- La autoridad de eventos y la autoridad financiera permanecen separadas.
- El comportamiento actual se demuestra en `scripts/smoke-v2.sh` con HTTP 409.

### Negativas / trade-offs

- Un HOLD no garantiza disponibilidad luego de su expiracion.
- Aun no existe confirmacion durable ni Saga Events-Payments.
- `KEYS` en la introspeccion solo es aceptable para el dataset educativo pequeno.