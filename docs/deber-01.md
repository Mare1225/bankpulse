# Deber 01 — El falso verde: cuando la tecnología funciona y el negocio pierde

**Universidad San Francisco de Quito · Arquitectura de Software Moderna**

---

## 1. Capacidad de negocio seleccionada

**Un pago con el mismo identificador debe procesarse exactamente una vez.**

BankPulse garantiza idempotencia mediante el header `X-Idempotency-Key`. Si un cliente reenvía el mismo request (retry de red, doble clic, bug del cliente), el banco debe devolver el mismo pago sin crear una segunda transacción.

---

## 2. Escenario de falso verde

| Capa técnica | Estado |
|---|---|
| `payments-api` health | 🟢 UP |
| MariaDB | 🟢 UP |
| Health check `/health/payments` | 🟢 HTTP 200 |
| Contenedores | 🟢 Running |
| Smoke test de infraestructura | 🟢 PASS |

| Resultado de negocio | Estado |
|---|---|
| Request #1 con key `X` → pago creado | ✅ |
| Request #2 con key `X` → **pago duplicado** | 🔴 PÉRDIDA |
| Cliente cobrado dos veces | 🔴 RECLAMO |

**El dashboard técnico muestra todo verde mientras el banco cobra dos veces.**

---

## 3. Riesgo y pérdida potencial

- **Financiero:** cada retry crea una transacción real → pérdida directa para el cliente o el banco.
- **Reputacional:** el cliente recibe dos cargos por la misma compra.
- **Regulatorio:** duplicidad de transacciones es una violación de controles financieros.
- **Silencioso:** ningún monitor técnico lo detecta — CPU verde, API verde, DB verde.

---

## 4. Pipeline — Release Gate

```
PR abierto
    │
    ▼
Architecture Contract       (~7s)
Verifica que existan los 6 servicios,
Dockerfiles, compose y docs de ownership.
    │
    ▼
Build + Integration Test    (~4-5 min)
Construye el stack completo, ejecuta
smoke-v2.sh (health, endpoints, seat hold,
social split). Verifica infraestructura.
    │
    ▼
Release Gate                (~4 min)
scripts/business-test.sh:
  1. Envía el mismo pago dos veces
  2. Verifica que ambas respuestas tienen el mismo ID
  3. Verifica que solo existe 1 registro en la base
  4. Verifica que el outbox drena a 0
    │
    ├── ✅ PASS  → merge permitido
    └── ❌ BLOCK → merge bloqueado
```

**La diferencia clave:** el integration test verifica que el sistema está vivo. El Release Gate verifica que el sistema hace lo que prometió al negocio.

---

## 5. PR exitoso 🟢

**PR #4 — `feature/deber-01-release-gate`**
https://github.com/Mare1225/bankpulse/pull/4

Agrega `scripts/business-test.sh` y el job `release-gate` en `ci.yml`. El código de `PaymentService` está correcto — la idempotencia funciona.

| Check | Resultado | Tiempo |
|---|---|---|
| Architecture contract | ✅ PASS | 7s |
| Build, integration and observability | ✅ PASS | 4m 42s |
| Release Gate — Idempotencia de pagos | ✅ PASS | 4m 8s |

---

## 6. PR con falso verde 🔴

**PR #5 — `deber-01-false-green`**
https://github.com/Mare1225/bankpulse/pull/5

### Cambios introducidos (intencionalmente rotos)

**`PaymentService.java`** — eliminada la búsqueda por idempotency key:
```java
// ANTES (correcto)
return payments.findByIdempotencyKey(idempotencyKey)
               .orElseGet(() -> persist(idempotencyKey, request));

// DESPUÉS (roto)
return persist(idempotencyKey, request);  // siempre crea un pago nuevo
```

**`Payment.java`** — eliminada la restricción `unique` en `idempotency_key`:
```java
// ANTES
@Column(name = "idempotency_key", nullable = false, unique = true, length = 100)

// DESPUÉS
@Column(name = "idempotency_key", nullable = false, length = 100)
```

**`smoke.sh`** — eliminado el check de idempotencia del smoke test (simula un desarrollador que "simplificó" los tests técnicos):
```bash
# Eliminado: el retry con cmp que comparaba las dos respuestas
```

### Resultado del CI

| Check | Resultado | Tiempo | Explicación |
|---|---|---|---|
| Architecture contract | ✅ PASS | 5s | Dockerfiles y docs existen |
| Build, integration and observability | ✅ PASS | 4m 10s | Infra UP, smoke pasa sin el check de idempotencia |
| Release Gate — Idempotencia de pagos | ❌ BLOCKED | 4m 18s | Pago duplicado detectado |

![PR #5 — falso verde bloqueado por Release Gate](../Screenshot%202026-09-16%20at%209.22.05%20PM.png)

### Evidencia del bloqueo

```
══════════════════════════════════════════
  RELEASE GATE — Idempotencia de pagos
══════════════════════════════════════════

[ INFRA ] Health checks
  ✔ payments UP
  ✔ audit UP

[ NEGOCIO ] Enviando pago #1
  ✔ Pago #1 creado id=2bf59cad-dacb-4168-a5ec-d5bc651b560a

[ NEGOCIO ] Reenviando pago #2 con el mismo key
  ✔ Pago #2 respondido id=44aa98f2-1beb-4956-827a-2e491000517c

[ GATE ] Verificando invariante: 1 key → 1 pago
  ✘ IDs distintos — pago duplicado detectado
    id1=2bf59cad-dacb-4168-a5ec-d5bc651b560a
    id2=44aa98f2-1beb-4956-827a-2e491000517c

══════════════════════════════════════════
  ✘ RELEASE GATE — BLOCKED (1 fallo)
  La tecnología puede estar UP pero el negocio está en riesgo.
══════════════════════════════════════════
```

---

## 7. Diagnóstico y corrección

### Diagnóstico

El Release Gate detectó que dos requests con el mismo `X-Idempotency-Key` producen IDs de pago distintos — el sistema procesó el pago dos veces. La causa raíz es la eliminación de `payments.findByIdempotencyKey()` en `PaymentService.create()`.

La restricción `unique` en la base de datos era una segunda línea de defensa que también fue eliminada, permitiendo que el duplicado llegara a persistirse silenciosamente con HTTP 200.

### Corrección

Restaurar en `PaymentService.java`:
```java
return payments.findByIdempotencyKey(idempotencyKey)
               .orElseGet(() -> persist(idempotencyKey, request));
```

Restaurar en `Payment.java`:
```java
@Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
```

Restaurar el check de idempotencia en `smoke.sh`.

### Ciclo completo

```
Código roto pushed
        │
        ▼
Architecture contract   ✅
Integration test        ✅  ← infra verde (falso verde sin gate)
Release Gate            ❌  ← negocio rojo → MERGE BLOQUEADO
        │
        ▼
Diagnóstico: PaymentService.create() no verifica idempotency key
        │
        ▼
Fix: restaurar findByIdempotencyKey + unique constraint
        │
        ▼
Release Gate            ✅  ← negocio verde → merge permitido
```

---

## 8. Reproducción desde Codespace limpio

```bash
# 1. Levantar el stack
cp .env.example .env
docker compose up -d --build --wait

# 2. Ejecutar el Release Gate manualmente
bash scripts/business-test.sh

# 3. Para reproducir el falso verde
git checkout deber-01-false-green
docker compose up -d --build payments-api --wait
bash scripts/business-test.sh  # → BLOCKED

# 4. Restaurar estado correcto
git checkout main
docker compose up -d --build payments-api --wait
bash scripts/business-test.sh  # → PASS
```

---

## Conclusión

El Release Gate transforma una pregunta técnica ("¿está el sistema UP?") en una pregunta de negocio ("¿está el sistema haciendo lo que prometió?"). Sin él, un cambio que rompe la idempotencia llegaría a producción con todos los monitores en verde mientras el banco cobra dos veces al cliente.
