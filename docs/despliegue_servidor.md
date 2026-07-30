# Despliegue en el servidor doméstico

Preparación del despliegue de Ticketing en el servidor que ya aloja Videogames
Library. **Nada de esto se ha ejecutado todavía.**

## Aviso previo: no he inspeccionado el servidor

No tengo acceso a esa máquina desde el entorno de desarrollo, así que **no hay
diagnóstico**: no he ejecutado `ss`, ni `docker ps`, ni `systemctl status
cloudflared`, ni he mirado la configuración de cloudflared. Todo lo que sigue
está construido sobre lo que me has contado, no sobre lo que he visto.

Eso importa para tres cosas concretas que hay que **comprobar en el servidor**
antes de dar nada por bueno:

1. Que el puerto 8081 está libre de verdad.
2. Si el túnel se gestiona por `/etc/cloudflared/config.yml` o desde el panel.
3. Si Ollama y el toolkit de NVIDIA ya están instalados, y con qué versiones.

---

## 1. Diagnóstico a ejecutar (por ti, en el servidor)

A tu lista le faltan las comprobaciones de GPU, que son las que deciden la
arquitectura de la extracción:

```bash
# --- lo tuyo, sin cambios ---
docker compose -f /srv/coleccion-videojuegos/deploy/docker-compose.prod.yml ps
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
sudo ss -ltnp
docker network ls
docker volume ls
df -h
free -h
sudo systemctl status cloudflared
curl http://127.0.0.1:8080/actuator/health
sudo systemctl cat cloudflared
sudo test -f /etc/cloudflared/config.yml && sudo cat /etc/cloudflared/config.yml

# --- lo que falta, y condiciona el diseño ---
lsb_release -a                          # distribución y versión exactas
nvidia-smi                              # driver y VRAM libre de la 1070
which ollama && ollama --version        # ¿ya está instalado en el host?
systemctl is-active ollama              # ¿corre como servicio?
ss -ltnp | grep 11434                   # ¿en qué interfaz escucha?
dpkg -l | grep nvidia-container-toolkit # ¿se puede dar GPU a un contenedor?
sudo ss -ltnp | grep -E ':(8081|5433|11434)'   # los puertos que quiero usar
```

---

## 2. Arquitectura propuesta

```
Navegador
  → Cloudflare (termina HTTPS)
  → Cloudflare Tunnel
  → cloudflared
  → http://127.0.0.1:8081          ← puerto NUEVO, el 8080 no se toca
  → ticketing-nginx
       ├── /       → ticketing-frontend
       ├── /api/   → ticketing-backend
       └── /actuator/health → ticketing-backend
                              ├── ticketing-postgres  (sin puerto publicado)
                              └── Ollama + GTX 1070
```

Aislamiento, punto por punto de tus reglas:

| Regla | Cómo queda |
|---|---|
| Otra carpeta | `/srv/ticketing` |
| Otro compose | `deploy/docker-compose.prod.yml` |
| Otro `COMPOSE_PROJECT_NAME` | `name: ticketing` en el propio fichero |
| Otros contenedores | `ticketing-postgres`, `-backend`, `-frontend`, `-nginx` |
| Otra red | `ticketing-net` |
| Otro volumen | `ticketing-postgres-data` |
| Otra base | `ticketing`, usuario propio |
| `.env` propios | `deploy/.env`, plantilla en `.env.example` |
| Otros backups | `/srv/ticketing/backups` |
| PostgreSQL sin publicar | sin sección `ports` |
| No usar el 8080 | `127.0.0.1:8081` |
| Ligado a loopback | `127.0.0.1:${HOST_PORT}:80` |

**Nada de esto toca el otro proyecto.** No hay cambios en su compose, sus `.env`,
su base ni su puerto. El único fichero compartido que habría que tocar es el de
cloudflared, y solo si el túnel se gestiona en local (ver §5).

Sobre PostgreSQL: el otro proyecto usa la 16 y este la 17. No hay conflicto
porque son contenedores y volúmenes distintos; no se reutiliza nada.

---

## 3. La decisión abierta: dónde corre Ollama

Es lo único de la arquitectura que no puedo cerrar sin ver el servidor.

**Opción A — Ollama en contenedor** (`docker-compose.ollama.yml`, recomendada).
No expone nada en el host, el backend llega por `http://ollama:11434` y todo se
queda dentro de `ticketing-net`. Encaja con tus reglas de aislamiento sin
excepciones. Requiere `nvidia-container-toolkit`.

**Opción B — Ollama en el host** como servicio systemd. Más simple si ya está
instalado. Tiene una trampa que conviene saber antes: **por defecto escucha en
`127.0.0.1:11434`, y eso un contenedor no lo alcanza**, ni siquiera con
`host.docker.internal`. Habría que ponerlo a escuchar en `0.0.0.0`, y entonces
queda visible para toda la LAN salvo que añadas una regla de firewall — que es
justo el tipo de cambio que pediste no hacer a la ligera.

El `docker-compose.prod.yml` está escrito ahora mismo para la **opción B**
(`host.docker.internal` + `extra_hosts`). Para pasar a la A basta con añadir el
overlay y cambiar una variable; está documentado en la cabecera del overlay.

Lo que decide: si `nvidia-container-toolkit` está instalado y si Ollama ya corre
en el host para otra cosa.

---

## 4. Antes de exponerlo: la seguridad no está lista

Esto es lo más importante de todo el documento.

La autenticación actual se escribió para uso en LAN, y hay un comentario en el
código que lo dice: `csrf.disable()` con la nota «uso personal en LAN, se
revisará al exponer la app fuera de casa». **Ese momento es ahora**, y la
justificación ya no vale.

El problema concreto: la app usa **HTTP Basic**. Los navegadores cachean esas
credenciales y las reenvían solas a cualquier petición al mismo origen,
incluidas las que dispare una página de terceros. Con CSRF desactivado, eso es
un CSRF real, no teórico. En una LAN con un usuario da igual; en una URL pública
no.

Además, `SEED_USER_PASSWORD` tiene por defecto `cambiame`.

Tres salidas, y hay que elegir **antes** de crear el hostname público:

1. **Cloudflare Access delante** (lo más rápido y lo que mejor encaja con lo que
   ya tienes). El túnel exige identidad antes de que la petición llegue a la
   app, así que el Basic queda como segunda barrera. Cero cambios de código.
2. **Cambiar a sesión con CSRF activado.** Correcto de libro, pero toca el
   backend y el frontend.
3. Dejarlo como está. **No lo recomiendo** y no lo doy por bueno.

No he tocado `SecurityConfig`: cambiar el modelo de autenticación por mi cuenta,
justo antes de un despliegue, es de las cosas que conviene decidir y no
descubrir. Dime cuál quieres y lo monto.

---

## 5. Cloudflare Tunnel

**Antes de tocar nada, comprobar cómo se gestiona.** Si es por panel, se añade
allí el Public Hostname nuevo apuntando a `http://localhost:8081` y no se edita
ningún fichero.

Si es por `/etc/cloudflared/config.yml`, la regla nueva va **antes** del
`http_status:404`, sin tocar la existente:

```yaml
ingress:
  - hostname: collection.videogameslibrary.com
    service: http://localhost:8080          # NO SE TOCA

  - hostname: tickets.videogameslibrary.com # nueva
    service: http://localhost:8081

  - service: http_status:404
```

Con copia previa y validación antes de recargar:

```bash
sudo cp /etc/cloudflared/config.yml /etc/cloudflared/config.yml.bak-$(date +%F)
sudo cloudflared tunnel ingress validate
sudo systemctl reload cloudflared    # reload, no restart: no corta el túnel del otro proyecto
```

Si `validate` falla, se restaura el `.bak` y no se recarga.

---

## 6. Orden de despliegue

Cada paso es reversible y ninguno toca el proyecto existente.

```bash
# 1. Estructura
sudo mkdir -p /srv/ticketing/{app,deploy,storage/ticket-images,backups}
sudo chown -R "$USER":"$USER" /srv/ticketing

# 2. Código
git clone <repo> /srv/ticketing/app

# 3. Configuración
cp /srv/ticketing/app/deploy/.env.example /srv/ticketing/deploy/.env
chmod 600 /srv/ticketing/deploy/.env
$EDITOR /srv/ticketing/deploy/.env          # contraseñas de verdad
cp /srv/ticketing/app/deploy/{docker-compose.prod.yml,nginx.conf,backup.sh} /srv/ticketing/deploy/

# 4. Confirmar que el puerto está libre ANTES de levantar
sudo ss -ltnp | grep 8081                    # no debe devolver nada

# 5. Levantar solo este stack
cd /srv/ticketing/deploy
docker compose -f docker-compose.prod.yml up -d --build

# 6. Modelo (si Ollama va en contenedor)
docker exec ticketing-ollama ollama pull qwen3-vl:8b
```

**Verificación, en este orden:**

```bash
# el otro proyecto sigue en pie — esto primero, no al final
curl http://127.0.0.1:8080/actuator/health
curl -I https://collection.videogameslibrary.com

# el nuevo, en local antes que por Cloudflare
curl http://127.0.0.1:8081/actuator/health
curl -I http://127.0.0.1:8081/

docker ps
sudo systemctl status cloudflared

# y por último, la prueba que de verdad importa: subir un ticket real y
# mirar el log de tokens y el tiempo por ticket en la 1070
docker logs -f ticketing-backend | grep "Ollama qwen3-vl"
```

Marcha atrás, si algo va mal:

```bash
cd /srv/ticketing/deploy
docker compose -f docker-compose.prod.yml down   # SIN -v: el volumen se queda
```

---

## 7. Recursos

Con lo que ya corre en la máquina:

| | Estimación | Notas |
|---|---|---|
| RAM | ~1,5 GB el stack | JVM al 70 % del límite, Postgres, dos nginx |
| RAM Ollama | 1-2 GB de host | los pesos van a VRAM |
| VRAM | **6,09 GiB de 8** | medido en el PC con `num_ctx=8192` |
| Disco | ~7 GB | modelo 6,1 GB + imágenes, que crecen despacio |

De 24 GB de RAM sobra de largo. **El número apretado es la VRAM**: 6,09 de 8 GiB
deja poco margen, y si hay que bajar el troceado de imagen o la cuantización, lo
que se degrada es justo el OCR del ticket alto y denso. Por eso la prueba en la
1070 real sigue siendo el paso que falta antes de fijar el modelo.

---

## 8. Copias de seguridad

`deploy/backup.sh` guarda **dos** cosas: el volcado de la base y las imágenes
originales. Una copia que solo lleve la base parece completa y no lo es: sin las
imágenes no se puede reextraer ni auditar nada.

```bash
crontab -e
0 4 * * * /srv/ticketing/deploy/backup.sh >> /srv/ticketing/backups/backup.log 2>&1
```

Retención de 30 días. Y una copia que nunca se ha restaurado no se sabe si
sirve: conviene probar la restauración en local una vez.

---

## 9. Lo que NO está resuelto

- Sin diagnóstico del servidor: los puertos, el estado de cloudflared y la
  presencia de Ollama están **asumidos**, no comprobados.
- La seguridad, §4. Es bloqueante para exponerlo al público.
- Dónde corre Ollama, §3.
- El comportamiento en la 1070 sigue sin medir.
- El `mcp-server/` que revisamos no está en este repositorio ni desplegado en
  ningún sitio, y no forma parte de esta arquitectura.
