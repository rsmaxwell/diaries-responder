package com.rsmaxwell.diaries.response.handlers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

public class DeleteFile extends RequestHandler {

	private static final Logger log = LogManager.getLogger(DeleteFile.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) {
		try {
			log.info("DeleteFile.handleRequest: args: {}", mapper.writeValueAsString(args));

			// --- Auth ---
			String accessToken = Authorization.getAccessToken(userProperties);
			DiaryContext context = (DiaryContext) ctx;
			if (Authorization.checkToken(context, "access", accessToken) == null) {
				log.info("DeleteFile.handleRequest: Authorization.check: Failed!");
				throw new Unauthorised();
			}
			log.info("DeleteFile.handleRequest: Authorization.check: OK!");

			// --- Inputs ---
			final String name = Utilities.getString(args, "name"); // e.g. "my-photo.jpg"
			final String subdir = Utilities.getStringOrDefault(args, "subdir", ""); // optional

			// --- Resolve & sanitize target path ---
			// Base uploads directory from your server config

			DiariesConfig diariesConfig = context.getConfig().getDiaries();
			Path base = Path.of(diariesConfig.getOriginal());
			final Path baseUploadsDir = base.resolve("uploads");
			if (baseUploadsDir == null) {
				return Response.internalError("Uploads directory is not configured on the server.");
			}
			Files.createDirectories(baseUploadsDir);

			// Disallow absolute or sneaky paths in subdir/name
			Path safeSubdir = Paths.get(subdir).normalize();
			if (safeSubdir.isAbsolute() || safeSubdir.toString().contains("..")) {
				return Response.badRequest("Invalid 'subdir'.");
			}

			// Disallow path separators in 'name' to avoid traversal (or allow and sanitize carefully)
			if (name.contains("/") || name.contains("\\") || name.contains("..") || name.isBlank()) {
				return Response.badRequest("Invalid 'name'.");
			}

			Path targetDir = baseUploadsDir.resolve(safeSubdir).normalize();
			if (!targetDir.startsWith(baseUploadsDir)) {
				return Response.badRequest("Resolved path escapes uploads root.");
			}
			Files.createDirectories(targetDir);

			Path target = targetDir.resolve(name).normalize();
			if (!target.startsWith(baseUploadsDir)) {
				return Response.badRequest("Resolved file path escapes uploads root.");
			}

			// Delete the file
			if (Files.exists(target)) {
				log.info(String.format("DeleteFile.handleRequest: deleting: '%s' ", target));
				Files.delete(target);
			} else {
				log.info(String.format("DeleteFile.handleRequest: file not found: '%s' ", target));
			}

			// --- Success payload ---
			Map<String, Object> result = Map.of("name", name, "subdir", safeSubdir.toString(), "path", target.toString());
			return Response.success(result);

		} catch (Unauthorised u) {
			return Response.unauthorized();
		} catch (Exception e) {
			log.error("DeleteFile.handleRequest: error", e);
			return Response.internalError(e.getMessage());
		}
	}
}
