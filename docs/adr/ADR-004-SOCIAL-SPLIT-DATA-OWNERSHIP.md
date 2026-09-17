# ADR-004: Data ownership de Social Split

- **Estado:** Aceptado para la sesion local; integridad financiera cross-service propuesta
- **Fecha:** 2026-09-16
- **Equipo:** Equipo de Social Split

## Contexto

`social-split-api` administra sesiones de reparto, participantes, shares y el estado de cierre. El servicio necesita relacionar cada participacion con un pago sin apropiarse de Payment ni Authorization.

## Decision

Social Split es el unico writer de `SplitSession`, `SplitParticipant` y `Share`. La persistencia canonica es PostgreSQL schema `social_split`, usuario `split_owner`. El servicio almacena un `paymentReference` como referencia opaca y nunca copia el objeto financiero.

## Source of Truth

- **Persistencia:** PostgreSQL schema `social_split`.
- **Writer:** `social-split-api`.
- **Payments:** `payments-api` es la autoridad de existencia, monto, moneda y estado del pago.

## Datos externos requeridos

Para autorizar una participacion se requiere consultar o recibir de Payments una referencia valida, monto, moneda y estado. La respuesta externa debe ser minima. Social Split no puede consultar tablas de Payments ni escribir en su base.

## Integracion

La API actual recibe la referencia desde el cliente y la persiste. La decision objetivo es sustituir esa confianza por una validacion server-to-server o por un evento firmado de Payments, con timeout, correlacion e idempotencia. El cierre solo debe aceptarse cuando las shares sumen el total y cada participante requerido tenga una autorizacion valida.

## Consistencia

- Sesion, participantes y shares: fuerte dentro del schema `social_split`.
- Existencia y estado financiero: fuerte en Payments al autorizar.
- Proyecciones y notificaciones: eventual.
- Cierre distribuido: estado explicito; nunca asumir que una respuesta de red implica cobro confirmado.

## Seguridad y privacidad

`paymentReference` no debe ser un token reutilizable fuera del contrato. No se persisten PAN, credenciales ni detalles completos del pago. La autorizacion debe estar vinculada a participante, monto, moneda y sesion para evitar referencias falsas o reutilizadas.

## Observabilidad

Metricas: sesiones creadas/cerradas, shares invalidas, autorizaciones rechazadas, referencias no encontradas, conflictos de idempotencia, latencia y errores de Payments, y sesiones pendientes. Los logs deben incluir `splitId` y correlation ID, nunca secretos financieros.

## Alternativas consideradas

- **Copiar pagos dentro de Social Split:** rechazada; crea doble ownership y riesgo de divergencia.
- **Acceso directo a la base de Payments:** rechazado; rompe el bounded context.
- **Confiar en cualquier `paymentReference` enviada por el navegador:** rechazada para produccion; solo describe el estado actual de la demo.
- **Eventos en lugar de REST:** alternativa valida para desacoplar, pero requiere outbox, contrato de eventos y reconciliacion adicionales.

## Consecuencias

### Positivas

- Social Split mantiene un modelo pequeno y orientado a referencias.
- Payments conserva el control financiero.
- Las invariantes de reparto pueden probarse localmente y las financieras, en el contrato entre servicios.

### Negativas / trade-offs

- La validacion server-to-server agrega dependencia y fallos parciales.
- El codigo actual todavia permite referencias arbitrarias y no valida que la suma de shares coincida con el total; esas brechas deben resolverse antes de declarar cerrada la integridad financiera.