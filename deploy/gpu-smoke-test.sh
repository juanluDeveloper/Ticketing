#!/usr/bin/env bash
# Prueba de humo de la extracción en la GPU del servidor.
#
# Manda una foto de ticket real al Ollama en contenedor, con el MISMO esquema y
# los MISMOS parámetros que usará la aplicación, e imprime lo único que decide si
# el modelo vale en esta máquina: tokens consumidos frente a num_ctx, y tiempo.
#
# Se usa el Xinya por defecto porque es el peor caso de OCR (chino, punto
# decimal, dos líneas por producto). El de Mercadona es el peor caso de tamaño:
# 27 líneas y el que más se acerca al límite de contexto.
#
#   ./gpu-smoke-test.sh                  # Xinya
#   ./gpu-smoke-test.sh mercadona.jpeg   # el ticket alto
#
# No expone ningún puerto: la petición sale de un contenedor efímero conectado a
# la red interna.

set -euo pipefail

REPO_DIR="${REPO_DIR:-/srv/ticketing/app}"
NETWORK="${NETWORK:-ticketing-net}"
MODEL="${OLLAMA_MODEL:-qwen3-vl:8b}"
NUM_CTX="${OLLAMA_NUM_CTX:-8192}"
PHOTO="${1:-chino.jpeg}"

SCHEMA="${REPO_DIR}/backend/src/main/resources/extraction/anexo-b.schema.json"
IMAGE="${REPO_DIR}/Ejemplo tickets cada super/${PHOTO}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

[ -f "${SCHEMA}" ] || { echo "No encuentro el esquema en ${SCHEMA}" >&2; exit 1; }
[ -f "${IMAGE}" ]  || { echo "No encuentro la foto en ${IMAGE}" >&2; exit 1; }

echo "Modelo   : ${MODEL}"
echo "num_ctx  : ${NUM_CTX}"
echo "Foto     : ${PHOTO}"
echo

{
  printf '{"model":"%s","stream":false,"think":false,' "${MODEL}"
  printf '"options":{"num_ctx":%s,"temperature":0},' "${NUM_CTX}"
  printf '"format":%s,' "$(cat "${SCHEMA}")"
  printf '"messages":[{"role":"user","content":"Extrae este ticket al esquema.","images":["%s"]}]}' \
      "$(base64 -w0 "${IMAGE}")"
} > "${WORK}/body.json"

echo "Llamando al modelo. En la GTX 1070 esto puede tardar varios minutos."
START=$(date +%s)
docker run --rm --network "${NETWORK}" \
    -v "${WORK}/body.json:/body.json:ro" \
    curlimages/curl:latest \
    -sS --max-time 1800 -X POST http://ollama:11434/api/chat \
    -H 'Content-Type: application/json' -d @/body.json \
    > "${WORK}/out.json"
ELAPSED=$(( $(date +%s) - START ))

echo
echo "=================== RESULTADO ==================="
PROMPT_TOK=$(grep -oE '"prompt_eval_count":[0-9]+' "${WORK}/out.json" | grep -oE '[0-9]+' || echo 0)
EVAL_TOK=$(grep -oE '"eval_count":[0-9]+' "${WORK}/out.json" | grep -oE '[0-9]+' || echo 0)
TOTAL_TOK=$(( PROMPT_TOK + EVAL_TOK ))

echo "Tiempo         : ${ELAPSED}s"
echo "Tokens prompt  : ${PROMPT_TOK}"
echo "Tokens salida  : ${EVAL_TOK}"
echo "Tokens total   : ${TOTAL_TOK} de ${NUM_CTX}"

# Este es el número que decide. Si el total se acerca al contexto, el modelo se
# queda sin sitio y la respuesta sale cortada a media frase, sin JSON: es
# exactamente lo que pasó en el PC cuando el razonamiento estaba activo.
if [ "${TOTAL_TOK}" -gt 0 ]; then
  MARGEN=$(( 100 - (TOTAL_TOK * 100 / NUM_CTX) ))
  echo "Margen libre   : ${MARGEN}%"
  [ "${MARGEN}" -lt 15 ] && echo ">>> AVISO: margen escaso. Subir num_ctx o pasar a qwen3-vl:4b."
fi

# Un fichero por foto: si no, cada corrida pisa la anterior y no se pueden
# comparar dos tickets sin volver a pasar por la GPU.
SAVED="/tmp/gpu-smoke-test-${PHOTO%.*}.json"
cp "${WORK}/out.json" "${SAVED}"

echo
echo "--- Extracción devuelta ---"
# Se decodifica con python en vez de con grep: el JSON viene escapado dentro de
# otro JSON, y cualquier patrón se corta en la primera comilla escapada.
#
# Con think=false el modelo no razona y Ollama saca el JSON por "thinking"
# dejando "content" vacío. Es una rareza suya, no un fallo: el cliente Java lo
# contempla, y por eso aquí se cogen los dos campos.
if command -v python3 >/dev/null 2>&1; then
  python3 - "${SAVED}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
message = data.get("message", {})
payload = message.get("content") or message.get("thinking") or ""
if not payload.strip():
    print("(respuesta vacía)")
    sys.exit()
try:
    ticket = json.loads(payload)
except json.JSONDecodeError:
    print("No es JSON válido. Empieza por:")
    print(payload[:400])
    sys.exit()

print(f"  súper      : {ticket.get('store', {}).get('name')}")
print(f"  NIF        : {ticket.get('store', {}).get('nif')}")
print(f"  fecha      : {ticket.get('purchased_at')}")
print(f"  moneda     : {ticket.get('currency')}   separador: {ticket.get('decimal_separator')}")
print(f"  artículos  : {ticket.get('article_count')}")
print(f"  total      : {ticket.get('totals', {}).get('total')}")
lines = ticket.get("line_items", [])
print(f"  líneas     : {len(lines)}")
suma = sum(l.get("line_total") or 0 for l in lines)
print(f"  suma líneas: {round(suma, 2)}")
print("  --- líneas ---")
for i, l in enumerate(lines, 1):
    print(f"  {i:2} {str(l.get('raw_description'))[:34]:34} "
          f"q={l.get('quantity')} pu={l.get('unit_price')} "
          f"tot={l.get('line_total')} iva={l.get('tax_letter')} sold={l.get('sold_by')}")
PY
else
  echo "(sin python3; revisa el fichero a mano)"
fi

echo
echo "Salida completa en: ${SAVED}"
