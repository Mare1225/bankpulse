# ADR-005: Ownership financiero y auditoria mediante Transactional Outbox

- **Estado:** Aceptado con una brecha de concurrencia pendiente
- **Fecha:** 2026-09-16
- **Equipo:** Plataforma BankPulse

## Contexto

El falso verde principal del deber es que un sistema puede responder correctamente y estar saludable mientras cobra dos veces. Payments debe ser la autoridad financiera, conservar el pago aunque Audit este caido y publicar auditoria sin perder eventos.

## Decision

`payments-api` es el unico writer de `Payment`, su estado financiero y `OutboxEvent`, en MariaDB `bankpulse`. El pago y su evento `PAYMENT_CREATED` se guardan en la misma transaccion local. `audit-api` mantiene una proyeccion inmutable en MongoDB `audit`; no modifica Payments.

## Source of Truth

- **Finanzas:** MariaDB `bankpulse`, escrita solo por Payments.
- **Entrega pendiente:** tabla outbox de Payments.
- **Auditoria:** MongoDB `audit`, escrita solo por Audit.
- **Identidad de evento:** `eventId`, unico para deduplicacion de la proyeccion.

## Datos externos requeridos

Audit requiere el payload minimo del evento y su `eventId`. No debe recibir ni persistir mas datos financieros de los necesarios para la evidencia de auditoria. Otros servicios reciben una referencia de pago, no acceso a MariaDB.

## Integracion

`OutboxPublisher` entrega eventos a Audit y reintenta los no publicados. La entrega se considera at-least-once; Audit debe ser idempotente por `eventId`. La clave `X-Idempotency-Key` identifica una solicitud de negocio y debe devolver el mismo pago para reintentos seguros.

## Consistencia

- Pago y outbox: fuerte, en una transaccion local.
- Pago versus auditoria: eventual; Audit puede retrasarse o estar caido.
- Duplicacion de entrega: permitida en transporte, prohibida en la proyeccion final.
- Concurrencia con la misma clave: la restriccion unica de base debe convertirse en una respuesta idempotente determinista, no en un error de release.

## Seguridad y privacidad

Las bases no se comparten entre servicios. El endpoint interno de Audit debe protegerse en una evolucion productiva. Las credenciales y secretos llegan por variables de entorno y el payload de auditoria debe minimizar datos sensibles.

## Observabilidad

Metricas: pagos aceptados, reintentos por idempotencia, conflictos de clave, outbox pendiente, antiguedad del evento mas antiguo, publicaciones exitosas/fallidas, duplicados deduplicados y auditorias por evento. Health de Payments no debe depender de que Audit este disponible.

## Alternativas consideradas

- **Escritura sincrona de Payments y Audit en una transaccion distribuida:** rechazada; agrega acoplamiento y disponibilidad compartida.
- **Compartir la base financiera con Audit:** rechazado; elimina ownership y aumenta superficie de acceso.
- **Publicar directamente desde la peticion HTTP:** rechazada; una caida de Audit podria perder el evento o bloquear el pago.
- **Broker externo:** reservado para una escala mayor; el outbox HTTP es suficiente para el laboratorio y conserva la evidencia local.

## Consecuencias

### Positivas

- Un pago no se pierde si Audit esta caido.
- La auditoria puede recuperarse sin duplicar el evento final.
- La invariante es observable y apta para un Release Gate.

### Negativas / trade-offs

- La auditoria no es inmediata.
- El patron requiere limpieza/reintentos y monitoreo de la cola.
- El codigo actual hace `find` seguido de `insert`; el caso concurrente con la misma clave debe endurecerse y probarse.