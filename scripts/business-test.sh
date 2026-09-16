#!/usr/bin/env bash
# Release Gate — BankPulse
# Verifica que un pago con el mismo X-Idempotency-Key se procesa exactamente una vez.
# Infraestructura UP + este test en ROJO = falso verde detectado.
set -euo pipefail

BASE="${BANKPULSE_URL:-http://localhost:8080}"
KEY="gate-$(date +%s%N)"
PASS=0
FAIL=0

ok()   { echo "  ✔ $*"; }
fail() { echo "  ✘ $*"; FAIL=$((FAIL+1)); }

echo ""
echo "══════════════════════════════════════════"
echo "  RELEASE GATE — Idempotencia de pagos"
echo "══════════════════════════════════════════"
echo ""

# 1. Infraestructura
echo "[ INFRA ] Health checks"
for svc in payments audit; do
  if curl -fsS "$BASE/health/$svc" | grep -q '"status":"UP"'; then
    ok "$svc UP"
  else
    fail "$svc DOWN"
  fi
done

# 2. Primer request
echo ""
echo "[ NEGOCIO ] Enviando pago #1 (key=$KEY)"
R1=$(curl -fsS -X POST "$BASE/api/payments" \
  -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: $KEY" \
  -d '{"account":"EC-TEST","amount":500.00,"currency":"USD"}')
ID1=$(echo "$R1" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
ok "Pago #1 creado id=$ID1"

# 3. Segundo request — mismo key, mismo payload
echo ""
echo "[ NEGOCIO ] Reenviando pago #2 con el mismo key (simula retry / bug de cliente)"
R2=$(curl -fsS -X POST "$BASE/api/payments" \
  -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: $KEY" \
  -d '{"account":"EC-TEST","amount":500.00,"currency":"USD"}')
ID2=$(echo "$R2" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
ok "Pago #2 respondido id=$ID2"

# 4. Invariante: ambos requests deben devolver el MISMO id
echo ""
echo "[ GATE ] Verificando invariante: 1 key → 1 pago"
if [ "$ID1" = "$ID2" ]; then
  ok "IDs idénticos — pago procesado exactamente una vez"
  PASS=$((PASS+1))
else
  fail "IDs distintos — pago duplicado detectado (id1=$ID1 id2=$ID2)"
fi

# 5. Verificar que solo existe 1 registro en la base
echo ""
echo "[ GATE ] Verificando conteo en payments-api"
COUNT=$(curl -fsS "$BASE/api/payments" | grep -o "\"id\":\"$ID1\"" | wc -l | tr -d ' ')
if [ "$COUNT" = "1" ]; then
  ok "Exactamente 1 registro con id=$ID1 en la base"
  PASS=$((PASS+1))
else
  fail "Se encontraron $COUNT registros con id=$ID1 — inconsistencia"
fi

# 6. Consistencia eventual: outbox publicado
echo ""
echo "[ GATE ] Verificando consistencia eventual (outbox)"
for _ in $(seq 1 20); do
  PENDING=$(curl -fsS "$BASE/api/outbox" | sed -n 's/.*"pending":\([0-9]*\).*/\1/p')
  [ "$PENDING" = "0" ] && break
  sleep 1
done
if [ "${PENDING:-1}" = "0" ]; then
  ok "Outbox drenado — evento auditado correctamente"
  PASS=$((PASS+1))
else
  fail "Outbox con $PENDING evento(s) pendiente(s) — consistencia no alcanzada"
fi

# Resultado final
echo ""
echo "══════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
  echo "  ✔ RELEASE GATE — PASS ($PASS checks)"
  echo "══════════════════════════════════════════"
  exit 0
else
  echo "  ✘ RELEASE GATE — BLOCKED ($FAIL fallo(s), $PASS ok)"
  echo "  La tecnología puede estar UP pero el negocio está en riesgo."
  echo "══════════════════════════════════════════"
  exit 1
fi
