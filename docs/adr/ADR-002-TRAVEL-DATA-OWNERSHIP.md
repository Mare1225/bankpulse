# ADR-002: Data ownership de Travel Benefits

- **Estado:** Aceptado para el laboratorio; controles offline avanzados propuestos
- **Fecha:** 2026-09-16
- **Equipo:** Equipo de Viajes

## Contexto

`travel-benefits-api` administra elegibilidad, credenciales y redenciones de beneficios de viaje. La experiencia offline necesita una credencial verificable, pero el servicio no debe delegar su autoridad en el navegador ni duplicar datos financieros.

## Decision

Travel Benefits es el unico writer de `Eligibility`, `Credential` y `Redemption` del dominio de viajes. Su persistencia canonica es la base MongoDB `travel`. El navegador puede conservar la ultima credencial como demostracion educativa, pero no es source of truth.

## Source of Truth

- **Persistencia:** MongoDB, base `travel`.
- **Writer:** `travel-benefits-api`.
- **Credencial:** firma emitida por el servicio con `TRAVEL_CREDENTIAL_SECRET`; la copia local del navegador es un cache offline.

## Datos externos requeridos

La elegibilidad puede depender de un miembro o cuenta externo, identificado por `memberId`. No se replica la cuenta bancaria, saldo ni informacion de pagos. Una futura conciliacion de redenciones debe intercambiar IDs y eventos, no documentos financieros.

## Integracion

Eligibility y emision de credenciales son REST. La redencion offline debe registrarse con un nonce o clave de idempotencia, validar firma y expiracion y sincronizarse al recuperar conectividad. Los reintentos deben ser seguros y los timeouts no deben convertir un fallo de red en una redencion confirmada.

## Consistencia

- Emision de credencial y escritura de redencion: fuerte dentro de Travel.
- Validacion offline: local y verificable criptograficamente, pero provisional.
- Conciliacion posterior y revocacion: eventual, con estados pendientes y auditables.

## Seguridad y privacidad

La clave de firma solo llega por variable de entorno. Las credenciales deben expirar y no contener datos sensibles innecesarios. El servicio debe evitar aceptar `usedAt`, firmas o identificadores de redencion arbitrarios sin validacion.

## Observabilidad

Metricas minimas: elegibilidades aceptadas/rechazadas, credenciales emitidas, redenciones aceptadas/rechazadas, replay detectado, redenciones pendientes de sincronizacion y edad de la cola. Health y Prometheus deben permanecer disponibles aunque falle un consumidor externo.

## Alternativas consideradas

- **Base compartida con Payments:** rechazada; el beneficio de viaje no es dueño del estado financiero.
- **Acceso directo a una base externa de miembros:** rechazado; se requiere contrato de API o evento.
- **Credencial sin firma:** rechazada; no permite verificar offline la autenticidad.
- **SQL en lugar de MongoDB:** no adoptada para este laboratorio; el modelo de redenciones y credenciales es documental y ya esta aislado en `travel`.

## Consecuencias

### Positivas

- El dominio de viajes conserva independencia y puede soportar una experiencia offline.
- La informacion financiera no se replica.
- La decision encaja con la persistencia y el endpoint actuales.

### Negativas / trade-offs

- Offline introduce replay, revocacion y reconciliacion como problemas propios.
- La implementacion actual aun necesita validar firma, expiracion, cuota y anti-replay antes de tratar la credencial como una garantia productiva.