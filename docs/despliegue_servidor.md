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

## 3. Ollama: en contenedor

**Decidido.** Ollama va en `docker-compose.ollama.yml`, con el NVIDIA Container
Toolkit, sin publicar puerto y con el backend llegando por `http://ollama:11434`.
Nada queda accesible desde la LAN.

La GPU está virgen: no hay driver, ni toolkit, ni Ollama. Toda esa instalación es
la fase previa y está en [instalacion_gpu_servidor.md](instalacion_gpu_servidor.md),
que se ejecuta y se verifica **antes** de desplegar la aplicación.

Arranque definitivo, una vez la GPU esté probada:

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.ollama.yml up -d --build
```

El overlay pisa `OLLAMA_BASE_URL` con `http://ollama:11434`, así que la variable
del `.env` queda ignorada en esta configuración.

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

**Decidido: Cloudflare Access delante del túnel.** El código no se toca; Access
se configura en el panel y exige identidad antes de que la petición llegue a la
app, con el Basic quedando como segunda barrera.

Con esa decisión, el CSRF deja de ser explotable en la práctica: una página de
terceros no puede atravesar Access. Conviene tenerlo escrito de todas formas,
porque **la protección vive fuera de la aplicación**: el día que se acceda por
otra vía que no pase por Access —una prueba en la LAN, un puerto abierto por
error, un cambio en el túnel— la app vuelve a estar sin defensa. Si en algún
momento quieres que el propio backend se defienda solo, hay que pasar a sesión
con CSRF activado, y eso toca backend y frontend.

### La contraseña del usuario

Se hashea con BCrypt en el primer arranque, cuando el hash está en el centinela
`NEEDS_INIT`. En claro no se guarda nunca. **Antes del primer `up`:**

```bash
# Generar una de verdad y guardarla en tu gestor de contraseñas
openssl rand -base64 24

# Ponerla en el .env, que ya está en 600
$EDITOR /srv/ticketing/deploy/.env      # SEED_USER_PASSWORD=<la generada>
```

Si el stack ya arrancó con otra, el hash ya está puesto y cambiar la variable no
hace nada por sí solo. Hay que devolver el centinela y reiniciar **solo este**
contenedor:

```bash
docker exec ticketing-postgres psql -U ticketing -d ticketing \
    -c "UPDATE app_user SET password_hash='NEEDS_INIT' WHERE username='juanluidos'"

$EDITOR /srv/ticketing/deploy/.env      # la nueva contraseña
cd /srv/ticketing/deploy
docker compose -f docker-compose.prod.yml up -d --force-recreate backend

# El log lo confirma
docker logs ticketing-backend | grep "Contraseña inicializada"
```

Nunca `docker compose restart` a secas ni tocar otros servicios: solo `backend`.

---

## 5. Cloudflare Tunnel

**Confirmado: el túnel se gestiona por token desde el panel.** `cloudflared`
arranca con `--token` y no existe `/etc/cloudflared/config.yml`.

Consecuencia directa: **no se toca ningún fichero de cloudflared en el
servidor**. El Public Hostname nuevo lo añades tú en el dashboard apuntando a
`http://localhost:8081`, y Access se configura sobre ese hostname.

Del lado del servidor lo único que hace falta es que el servicio escuche donde
toca, y eso se comprueba así:

```bash
sudo ss -ltnp | grep 8081        # debe aparecer 127.0.0.1:8081, nunca 0.0.0.0
curl -sI http://127.0.0.1:8081/ | head -1
```

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

Medido en el servidor, no estimado:

| | Medido | Notas |
|---|---|---|
| RAM | ~1,5 GB el stack | JVM al 70 % del límite, Postgres, dos nginx |
| RAM libre | 21 GB de 24 | sobra de largo |
| **VRAM con el modelo cargado** | **7851 MiB de 8192** | quedan 341 MiB, un 4 % |
| Contexto, peor caso | 4036 de 8192 | Mercadona, 27 líneas |
| Tiempo, ticket alto | 97-102 s | 4× la RTX 5070 Ti, esperado en Pascal |
| Tiempo, carga del modelo | ~21 s | se paga una vez |
| Disco | 67 GB libres en raíz | modelo 6,1 GB + imágenes |

**La VRAM va al 96 %.** Los 6,09 GiB medidos en el PC se quedaban cortos: aquí
`ollama ps` reporta 6,4 GB de modelo y `nvidia-smi` 7851 MiB en total, y esa
diferencia de ~1,2 GB son los buffers de CUDA y del codificador de visión.

Tres consecuencias que conviene tener escritas:

1. **No hay sitio para subir `num_ctx`.** El 8192 está confirmado funcionando y
   sobra contexto (51 % libre en el peor caso), así que no hace falta — pero si
   algún día hiciera falta, no cabe: habría que pasar a `qwen3-vl:4b`.
2. **Nada más puede usar esa GPU.** Con 341 MiB libres, cualquier otro proceso
   que pida VRAM provoca un fallo de asignación.
3. **Una extracción cada vez.** El ejecutor de un solo hilo del backend deja de
   ser una simplificación y pasa a ser un requisito: dos extracciones en
   paralelo no caben en esta tarjeta.

Sobre el `keep_alive` por defecto de Ollama (descarga el modelo a los 5 minutos
de inactividad): **se deja como está**. Con unos pocos tickets a la semana, cada
extracción paga ~21 s de carga, que es irrelevante frente a los 100 s del propio
trabajo, y a cambio la GPU queda libre cuando no se usa — que con un 4 % de
margen es lo prudente.

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

## 9. Estado

Confirmado en el servidor por SSH:

| | |
|---|---|
| Distro | Ubuntu 22.04 — **falta confirmarlo con `lsb_release`**, se dedujo de apt |
| GPU | virgen: sin driver, sin toolkit, sin Ollama |
| Túnel | por token desde el panel; **no hay fichero local que tocar** |
| Puerto 8081 | libre (ocupados: 8080, 22, 53, 20241) |
| RAM | 21 GB libres de 24 |
| Disco | 69 GB libres en raíz; se despliega ahí |

Decidido: Cloudflare Access delante, Ollama en contenedor.

Pendiente:

- **La fase GPU**, que va primero: [instalacion_gpu_servidor.md](instalacion_gpu_servidor.md).
- **El comportamiento en la 1070 sigue sin medir.** Es lo que decide si el modelo
  se queda o hay que bajar a `qwen3-vl:4b`.
- Los ~1,7 TB sin asignar y los dos SSD sin montar **no se tocan**: es una
  operación de particiones aparte, fuera de este despliegue.
- El `mcp-server/` no está en este repositorio ni en el servidor, y no forma
  parte de esta arquitectura.
