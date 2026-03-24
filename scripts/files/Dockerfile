FROM eclipse-temurin:21-jre-alpine

ARG VERSION
ARG ARCHIVA_BASE_URL="http://pluto.rsmaxwell.co.uk/repository/integration"

WORKDIR /opt/diaries

RUN mkdir -p \
    /opt/diaries/lib \
    /opt/diaries/bin \
    /config

RUN apk add --no-cache curl bash

# Download the Entrypoint script
COPY scripts/files/responder.sh /opt/diaries/bin/responder.sh
RUN chmod +x /opt/diaries/bin/responder.sh

ENV CONFIG_FILE=/config/responder.json
ENV JAVA_OPTS=""

ENTRYPOINT ["/opt/diaries/bin/responder.sh"]
