# ADR-006: Business Release Gate contra falso verde

- **Estado:** Propuesto
- **Fecha:** 2026-09-16
- **Equipo:** Plataforma BankPulse / CI

## Contexto

La disponibilidad de los seis servicios, un HTTP 200 o un health check `UP` no demuestra que BankPulse haya protegido el valor financiero. La asignacion exige identificar un falso verde, automatizarlo, bloquear el release ante una falla, provocar la falla deliberadamente y demostrar la recuperacion.

## Decision

GitHub Actions incorporara un job obligatorio `business-release-gate` despues del contrato de arquitectura y de levantar el stack real. El escenario critico sera la idempotencia de pagos y la entrega eventual de auditoria:

1. Enviar varias solicitudes concurrentes con la misma `X-Idempotency-Key`.
2. Exigir que todas devuelvan el mismo `payment.id`.
3. Exigir exactamente un pago y un evento outbox para esa clave.
4. Esperar una auditoria y exigir exactamente un `eventId` equivalente.
5. Detener `audit-api`, crear otro pago y comprobar que Payments continua operativo y conserva el outbox.
6. Recuperar Audit y exigir que el outbox se drene y la auditoria quede exactamente una vez.

El job sera requisito para merge y conservara logs, respuestas y conteos como artefactos. Una ejecucion controlada con el defecto introducido debe fallar; la correccion debe devolver el job a verde.

## Source of Truth

Payments es la fuente de verdad del pago y del outbox. Audit es la fuente de verdad de su proyeccion. El script del gate no escribira directamente en ninguna base: verificara los contratos HTTP y, para evidencia de laboratorio, usara endpoints de introspeccion ya publicados.

## Datos externos requeridos

El gate solo necesita la URL del edge, una clave de idempotencia unica por ejecucion y datos de prueba no sensibles. No se usan credenciales institucionales ni datos reales.

## Integracion

El workflow ejecutara Compose, el gate y la recoleccion de evidencia con cleanup `if: always()`. Los reintentos tendran limites y esperas acotadas. La prueba debe distinguir una respuesta repetida correcta de un pago duplicado y debe guardar el estado antes y despues de recuperar Audit.

## Consistencia

- Identidad y cantidad del pago: fuerte en Payments.
- Pago versus auditoria: eventual, con una espera acotada y criterio de convergencia.
- Entrega durante la caida de Audit: el pago debe ser aceptado; el outbox debe permanecer pendiente.
- Release: fuerte respecto al resultado final del gate; cualquier violacion bloquea el merge.

## Seguridad y privacidad

Las pruebas usan cuentas y montos efimeros. No se imprimen secretos. Los artefactos se limitan a IDs, estados, conteos y logs necesarios para diagnostico. Las credenciales demo de observabilidad siguen siendo solo de laboratorio.

## Observabilidad

El gate debe registrar `run_id`, idempotency key anonimizada, payment ID, event ID, conteo de pagos, conteo de outbox, conteo de auditorias y tiempos de convergencia. Prometheus/Grafana sirven como evidencia adicional, pero no reemplazan las aserciones de negocio.

## Alternativas consideradas

- **Solo health checks y smoke feliz:** rechazada; detecta disponibilidad, no perdida de negocio.
- **Prueba unitaria aislada de PaymentService:** insuficiente; no demuestra Compose, Outbox, Audit ni recuperacion.
- **Prueba end-to-end solo desde el navegador:** rechazada como unico gate; oculta contratos y dificulta contar invariantes.
- **Transaccion distribuida Payments-Audit:** rechazada; el objetivo es resiliencia con consistencia eventual y outbox.

## Consecuencias

### Positivas

- El pipeline puede bloquear una regresion que mantendria todos los health checks en verde.
- La evidencia de fallo y recuperacion es reproducible desde Codespaces y CI.
- La decision convierte la idempotencia y la auditoria en una capacidad verificable del negocio.

### Negativas / trade-offs

- El job aumenta el tiempo y el consumo de CI.
- Requiere datos de prueba aislados y cleanup confiable.
- El ADR no se considera implementado hasta que exista el job y el script de aserciones; el workflow actual aun solo ejecuta smoke y observabilidad.