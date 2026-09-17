# ADR-001: Data ownership de Experiences

- **Estado:** Aceptado para el laboratorio
- **Fecha:** 2026-09-16
- **Equipo:** Equipo de Gastronomia

## Contexto

`experiences-api` implementa el bounded context de Gastronomia. El servicio necesita publicar un catalogo de experiencias, restaurantes, partners y ofertas sin convertirse en dueño de pagos ni de datos de otros dominios.

## Decision

`experiences-api` es el unico writer de `Experience` y de sus metadatos comerciales. El agregado se persiste como documento en la base `experiences` de MongoDB. Las garantias o cobros relacionados con una experiencia se ejecutan en `payments-api`; Experiences no copia ni modifica entidades financieras.

## Source of Truth

- **Persistencia:** MongoDB, base `experiences`.
- **Writer:** `experiences-api`.
- **Acceso:** el servicio usa el repositorio Spring Data de Experiences; ningun otro servicio consulta directamente esta base.

## Datos externos requeridos

Para iniciar una garantia se requiere el identificador de la experiencia y datos minimos de la cuenta. La operacion financiera pertenece a Payments y debe invocarse mediante su API o un evento definido. No se replica el pago dentro de Experiences.

## Integracion

La consulta del catalogo es REST y puede tolerar fallos de otros dominios. La garantia demo actual es orquestada por el frontend y llama a Payments por separado. Cualquier integracion futura debe usar timeout, propagacion de un identificador de correlacion e idempotencia de la operacion financiera.

## Consistencia

- Catalogo y metadatos comerciales: consistencia fuerte dentro de MongoDB.
- Estado de una garantia o pago: consistencia fuerte en Payments; Experiences solo observa el resultado.
- Proyecciones o indicadores derivados: consistencia eventual y reconstruibles desde el owner.

## Seguridad y privacidad

No se almacenan PAN, saldos ni el objeto completo de un pago. Las credenciales de MongoDB llegan por variables de entorno. La base `experiences` esta separada logicamente de `audit` y `travel`.

## Observabilidad

Se deben observar disponibilidad de `/actuator/health`, metricas de `/actuator/prometheus`, latencia y errores de los endpoints de catalogo. Las garantias deben incluir un correlation ID en logs y metricas de la llamada a Payments.

## Alternativas consideradas

- **Base compartida:** rechazada; permitiria que otros dominios escribieran el catalogo y diluiria el ownership.
- **Acceso directo a la base de Payments:** rechazada; viola el limite del bounded context y acopla el modelo financiero.
- **PostgreSQL para el catalogo:** no adoptada en este laboratorio; MongoDB encaja con documentos de catalogo y reduce infraestructura adicional.

## Consecuencias

### Positivas

- El catalogo puede evolucionar sin modificar Payments.
- Los datos financieros permanecen bajo un unico owner.
- La separacion se puede verificar mediante el Compose y los repositorios del servicio.

### Negativas / trade-offs

- Una garantia distribuida no es una transaccion local de Experiences.
- El frontend actual coordina parte del flujo; una evolucion productiva requeriria un BFF u orquestador.
- La creacion de experiencias no tiene aun una politica de idempotencia formal.