# Build en dos etapas para no arrastrar Maven ni el .m2 a la imagen final.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Las dependencias primero: cambian mucho menos que el código, así que esta capa
# se reutiliza entre despliegues y el build en el servidor no vuelve a bajarlas.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Los tests de integración necesitan Postgres y Ollama, que aquí no existen: se
# ejecutan en el sitio donde sí están, no dentro del build de la imagen.
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl para el healthcheck; la imagen JRE alpine no lo trae.
#
# El uid/gid van FIJOS a 1000, no con el -S de usuario de sistema. Con -S alpine
# asigna un uid del rango bajo (100), y el directorio de imágenes que se monta
# desde el host pertenece al usuario normal (1000) con permisos 755: el
# contenedor no podía escribir y toda subida de foto moría con un 500.
# Dejarlo al azar del sistema es lo que rompió esto una vez.
RUN apk add --no-cache curl \
    && addgroup -g 1000 ticketing \
    && adduser -u 1000 -G ticketing -S ticketing

COPY --from=build /build/target/*.jar app.jar

# Las imágenes de los tickets van en un volumen montado aquí. Se conservan
# siempre: son lo que permite reextraer cuando mejore el modelo y auditar una
# línea dudosa meses después.
RUN mkdir -p /data/ticket-images && chown -R ticketing:ticketing /data
USER ticketing

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
