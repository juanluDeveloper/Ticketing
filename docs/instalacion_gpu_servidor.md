# Fase GPU: drivers, toolkit y Ollama en el servidor

Comandos exactos para dejar la GTX 1070 utilizable desde Docker. **Se para aquí**:
los contenedores de la aplicación no se despliegan hasta confirmar que la
extracción funciona en esta máquina.

Todo es reversible y está marcado el único punto donde se toca infraestructura
compartida con Videogames Library.

## Riesgos de esta fase, antes de empezar

Tres, y los tres pueden dejar el otro proyecto caído si se hacen a ciegas:

1. **Secure Boot.** Si está activo, el módulo del driver no carga aunque el
   paquete instale bien, y la máquina arranca sin GPU. Se comprueba en el paso 0.
2. **Reinicio.** Instalar el driver exige reiniciar. Videogames Library se cae
   durante ese rato y solo vuelve sola si Docker arranca al inicio: se verifica
   antes, no después.
3. **`/etc/docker/daemon.json` es compartido.** Es el único fichero de este plan
   que afecta a los dos proyectos. Se copia antes y se revisa el cambio.

---

## Paso 0 — Confirmar terreno (no cambia nada)

```bash
# La distro, confirmada de verdad y no deducida de lo que ofrece apt
lsb_release -a
uname -r

# Secure Boot: si dice "SecureBoot enabled", PARA y avísame antes de seguir
mokutil --sb-state 2>/dev/null || echo "mokutil no instalado (probablemente sin Secure Boot)"

# ¿Vuelven los contenedores solos tras el reinicio?
systemctl is-enabled docker
docker inspect -f '{{.Name}} {{.HostConfig.RestartPolicy.Name}}' $(docker ps -q)

# La GPU está físicamente y el kernel la ve
lspci | grep -i nvidia

# Estado del otro proyecto ANTES de tocar nada, para poder comparar después
curl -s http://127.0.0.1:8080/actuator/health; echo
docker ps --format "table {{.Names}}\t{{.Status}}"
```

Si algún contenedor de Videogames Library NO tiene política de reinicio
`unless-stopped` o `always`, hay que arrancarlo a mano tras el reinicio. Conviene
saberlo antes.

---

## Paso 1 — Driver NVIDIA

```bash
sudo apt update
sudo apt install -y ubuntu-drivers-common

# Qué recomienda Ubuntu para esta tarjeta concreta
ubuntu-drivers devices
```

Pásame la salida antes de instalar. La 1070 es Pascal y funciona con las ramas
535, 550 y 570; **la 535 es LTS y la apuesta segura**. La instalación explícita
es preferible a `ubuntu-drivers autoinstall`, que a veces elige la rama `-open`,
incompatible con Pascal.

```bash
# Con la versión ya acordada (ejemplo con 535)
sudo apt install -y nvidia-driver-535

# El paquete pone nouveau en la lista negra solo; confirmarlo
cat /etc/modprobe.d/nvidia-graphics-drivers.conf 2>/dev/null | head

sudo reboot
```

Tras el reinicio, **primero el otro proyecto y luego la GPU**:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
curl -s http://127.0.0.1:8080/actuator/health; echo
curl -sI https://collection.videogameslibrary.com | head -1

nvidia-smi
```

`nvidia-smi` debe mostrar la GTX 1070 con sus 8192 MiB y 0 procesos.

**Marcha atrás**, si el driver da problemas:

```bash
sudo apt purge -y 'nvidia-driver-*' 'libnvidia-*'
sudo apt autoremove -y
sudo reboot
```

---

## Paso 2 — NVIDIA Container Toolkit

> **Este paso toca `/etc/docker/daemon.json`, que es compartido con Videogames
> Library.** Es el único del plan que sale del ámbito del proyecto nuevo.

```bash
# Copia del fichero compartido ANTES de nada
sudo cp /etc/docker/daemon.json /etc/docker/daemon.json.bak-$(date +%F) 2>/dev/null \
  || echo "No existía daemon.json; el toolkit lo creará"
sudo cat /etc/docker/daemon.json 2>/dev/null || true

# Repositorio de NVIDIA
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
  | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
  | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
  | sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list

sudo apt update && sudo apt install -y nvidia-container-toolkit

# Registra el runtime en daemon.json SIN borrar lo que ya hubiera
sudo nvidia-ctk runtime configure --runtime=docker

# Revisar el cambio antes de reiniciar el demonio
sudo cat /etc/docker/daemon.json
```

Ese fichero debe conservar lo que tuviera y haber ganado solo el bloque
`runtimes.nvidia`. Si ha desaparecido algo, restaurar el `.bak` y avisarme.

```bash
# Reiniciar Docker rebota los contenedores del OTRO proyecto unos segundos.
# Si prefieres evitarlo, hazlo en un momento en que no estés usando la web.
sudo systemctl restart docker

# Verificación inmediata del otro proyecto
sleep 15
docker ps --format "table {{.Names}}\t{{.Status}}"
curl -s http://127.0.0.1:8080/actuator/health; echo

# Y la prueba de que un contenedor ve la GPU
sudo docker run --rm --gpus all nvidia/cuda:12.4.0-base-ubuntu22.04 nvidia-smi
```

Esa última orden es la que decide: si imprime la tabla con la 1070, el toolkit
funciona.

**Marcha atrás:**

```bash
sudo cp /etc/docker/daemon.json.bak-$(date +%F) /etc/docker/daemon.json
sudo apt purge -y nvidia-container-toolkit
sudo systemctl restart docker
```

---

## Paso 3 — Estructura y código

Solo el disco raíz. **No se toca el espacio sin asignar ni los SSD sin montar**:
eso es una operación de particiones aparte.

```bash
sudo mkdir -p /srv/ticketing/{app,deploy,storage/ticket-images,backups}
sudo chown -R "$USER":"$USER" /srv/ticketing
df -h /srv

git clone <URL-del-repo> /srv/ticketing/app
cp /srv/ticketing/app/deploy/docker-compose.gpu-test.yml /srv/ticketing/deploy/
cp /srv/ticketing/app/deploy/gpu-smoke-test.sh /srv/ticketing/deploy/
chmod +x /srv/ticketing/deploy/gpu-smoke-test.sh
```

---

## Paso 4 — Ollama en contenedor y descarga del modelo

```bash
cd /srv/ticketing/deploy
docker compose -f docker-compose.gpu-test.yml up -d

docker ps --filter name=ticketing-ollama
docker logs ticketing-ollama | tail -20

# Que el contenedor vea la tarjeta
docker exec ticketing-ollama nvidia-smi

# El modelo, unos 6 GB. Tarda.
docker exec ticketing-ollama ollama pull qwen3-vl:8b
docker exec ticketing-ollama ollama list
```

El contenedor **no publica ningún puerto**: Ollama no queda accesible ni desde
la LAN ni desde el host.

---

## Paso 5 — La prueba que de verdad decide

```bash
cd /srv/ticketing/deploy

# Xinya: el peor caso de OCR
./gpu-smoke-test.sh

# Mercadona: el peor caso de tamaño, 27 líneas
./gpu-smoke-test.sh mercadona.jpeg
```

Los dos números que necesito:

- **Tokens total frente a `num_ctx`.** En la RTX 5070 Ti el Mercadona gastó 5038
  de 8192, un 38 % libre. Si aquí el margen baja del 15 %, hay que subir
  `num_ctx` — y eso come VRAM de los 8 GB, que ya van justos con 6,09 del modelo.
- **Tiempo por ticket.** En la 5070 Ti fueron 25 s el Mercadona y 10 s el Xinya.
  Aquí serán bastante más: Pascal tiene el FP16 muy penalizado y el codificador
  de visión es la parte cara. Varios minutos es aceptable — la extracción es
  asíncrona —; lo que no es aceptable es que no quepa.

Mientras corre, en otra sesión:

```bash
watch -n 2 nvidia-smi
```

Si la VRAM se llena y el modelo empieza a descargarse y recargarse entre
peticiones, se verá ahí.

**Y hay que mirar la calidad, no solo que responda.** Del Xinya conocemos la
verdad: total 9,90, dos líneas de 3 × 1,65 = 4,95, y `article_count` 6. Si eso
sale bien, el OCR chino está funcionando.

---

## Al terminar el paso 5

Pásame la salida de los dos `gpu-smoke-test.sh` y confirmamos si el modelo se
queda o hay que bajar a `qwen3-vl:4b`. **Hasta entonces no despliego nada más.**

Ollama puede quedarse levantado: no molesta a nadie y el modelo ya descargado se
reutiliza en el despliegue definitivo.
