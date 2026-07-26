package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.responder.buildinfo.BuildInfo;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

/** Returns responder build information without requiring authentication. */
public class GetVersion extends RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(GetVersion.class);

    @Override
    public Response handleRequest(
            Object ctx,
            Map<String, Object> args,
            List<UserProperty> userProperties) throws Exception {

        BuildInfo buildInfo = new BuildInfo();
        log.info("GetVersion.handleRequest: version={}", buildInfo.getVersion());
        return Response.success(buildInfo);
    }
}
