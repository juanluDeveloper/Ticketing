#!/usr/bin/env bash
# Copia de seguridad del proyecto Ticketing.
#
# Dos cosas, no una: el volcado de la base Y las imágenes originales de los
# tickets. Sin las imágenes no se puede reextraer cuando mejore el modelo ni
# auditar una línea dudosa, así que una copia que solo lleve la base está
# incompleta aunque lo parezca.
#
# No toca nada de Videogames Library: su copia va por su lado.
#
# Uso:  ./backup.sh
# Cron: 0 4 * * *  /srv/ticketing/deploy/backup.sh >> /srv/ticketing/backups/backup.log 2>&1

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="/srv/ticketing/backups"
RETENTION_DAYS=30
STAMP="$(date +%Y%m%d-%H%M%S)"

# shellcheck disable=SC1091
source "${DEPLOY_DIR}/.env"

mkdir -p "${BACKUP_DIR}"

echo "[$(date -Is)] Volcando la base ${POSTGRES_DB}"
# --clean --if-exists para que el volcado se pueda restaurar sobre una base ya
# existente sin tener que borrarla a mano antes.
docker exec ticketing-postgres pg_dump \
    -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --clean --if-exists \
    | gzip > "${BACKUP_DIR}/db-${STAMP}.sql.gz"

echo "[$(date -Is)] Copiando las imágenes de tickets"
tar -czf "${BACKUP_DIR}/images-${STAMP}.tar.gz" \
    -C "${TICKETING_STORAGE}" ticket-images

echo "[$(date -Is)] Retirando copias de más de ${RETENTION_DAYS} días"
find "${BACKUP_DIR}" -name 'db-*.sql.gz'      -mtime "+${RETENTION_DAYS}" -delete
find "${BACKUP_DIR}" -name 'images-*.tar.gz'  -mtime "+${RETENTION_DAYS}" -delete

echo "[$(date -Is)] Hecho:"
ls -lh "${BACKUP_DIR}/db-${STAMP}.sql.gz" "${BACKUP_DIR}/images-${STAMP}.tar.gz"

# Recordatorio incómodo pero necesario: una copia que nunca se ha restaurado no
# se sabe si sirve. Probar la restauración en local al menos una vez.
