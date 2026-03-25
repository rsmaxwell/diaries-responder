FROM eclipse-temurin:21-jre-alpine

ARG REPOSITORY
ARG VERSION
ARG ARCHIVA_BASE_URL="https://pluto.rsmaxwell.co.uk/archiva/repository"


WORKDIR /opt/diaries

RUN mkdir -p \
    /opt/diaries/lib \
    /opt/diaries/bin \
    /config

RUN apk add --no-cache curl bash

# Download the Entrypoint script
COPY scripts/files/responder.sh /opt/diaries/bin/responder.sh
RUN chmod +x /opt/diaries/bin/responder.sh

RUN curl -fL \
    "${ARCHIVA_BASE_URL}/${REPOSITORY}/com/rsmaxwell/diaries/diaries-responder/${VERSION}/diaries-responder-${VERSION}-fat.jar" \
    -o /opt/diaries/lib/diaries-responder.jar

ENV APP_JAR=/opt/diaries/lib/diaries-responder.jar
ENV CONFIG_FILE=/etc/diaries_responder/config/responder.json
ENV JAVA_OPTS=""

ENTRYPOINT ["/opt/diaries/bin/responder.sh"]
