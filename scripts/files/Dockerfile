FROM eclipse-temurin:21-jre-alpine

ARG ARCHIVA_BASE_URL="http://pluto.rsmaxwell.co.uk/repository/integration"
ARG DIARIES_COMMON_VERSION
ARG DIARIES_RESPONDER_VERSION

WORKDIR /opt/diaries

RUN mkdir -p \
    /opt/diaries/lib \
    /opt/diaries/bin \
    /config

RUN apk add --no-cache curl bash

# Download diaries-common and diaries-responder
RUN curl -fL \
  -o /opt/diaries/lib/diaries-common-${DIARIES_COMMON_VERSION}.jar \
  "${ARCHIVA_BASE_URL}/com/rsmaxwell/diaries/diaries-common/${DIARIES_COMMON_VERSION}/diaries-common-${DIARIES_COMMON_VERSION}.jar" \
  && curl -fL \
  -o /opt/diaries/lib/diaries-responder-${DIARIES_RESPONDER_VERSION}.jar \
  "${ARCHIVA_BASE_URL}/com/rsmaxwell/diaries/diaries-responder/${DIARIES_RESPONDER_VERSION}/diaries-responder-${DIARIES_RESPONDER_VERSION}.jar"

COPY scripts/files/responder.sh /opt/diaries/bin/responder.sh
RUN chmod +x /opt/diaries/bin/responder.sh

ENV CONFIG_FILE=/config/responder.json
ENV JAVA_OPTS=""

ENTRYPOINT ["/opt/diaries/bin/responder.sh"]
