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

echo
echo "--- JSON devuelto (primeros 800 caracteres) ---"
# Con think=false el modelo no razona y Ollama saca el JSON por "thinking"
# dejando "content" vacío. Es una rareza suya, no un fallo: el cliente Java lo
# contempla. Aquí se imprimen los dos para poder verlo.
grep -oE '"content":"[^"]{0,800}' "${WORK}/out.json" | head -c 900 || true
echo
grep -oE '"thinking":"[^"]{0,800}' "${WORK}/out.json" | head -c 900 || true
echo
echo
echo "Salida completa guardada. Para revisarla entera:"
cp "${WORK}/out.json" /tmp/gpu-smoke-test-out.json
echo "  cat /tmp/gpu-smoke-test-out.json"
