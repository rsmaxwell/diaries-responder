package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;

public class Calculator extends RequestHandler {

	private static final Logger log = LogManager.getLogger(Calculator.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.traceEntry();

		try {
			String operation = Utilities.getString(args, "operation");
			int param1 = Utilities.getInteger(args, "param1");
			int param2 = Utilities.getInteger(args, "param2");

			int value = 0;

			switch (operation) {
			case "add":
				value = param1 + param2;
				break;
			case "mul":
				value = param1 * param2;
				break;
			case "div":
				value = param1 / param2;
				break;
			case "sub":
				value = param1 - param2;
				break;
			default:
				String text = String.format("Unexpected operation: %s", operation);
				log.info(text);
				throw new Exception(text);
			}

			return Response.success(value);
		} catch (ArithmeticException e) {
			log.debug(String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage()));
			return Response.badRequest(e.getMessage());
		} catch (Exception e) {
			log.catching(e);
			return Response.badRequest(e.getMessage());
		}
	}
}
