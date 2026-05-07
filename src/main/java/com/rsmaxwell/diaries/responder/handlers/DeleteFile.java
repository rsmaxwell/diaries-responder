package com.rsmaxwell.diaries.responder.handlers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;

public class DeleteFile extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(DeleteFile.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteFile.handleRequest: args: {}", mapper.writeValueAsString(args));

		// --- Auth ---
		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("DeleteFile.handleRequest: Authorization.check: OK!");

		// --- Config ---
		DiariesConfig diariesConfig = context.getConfig().getDiaries();
		Path root = Path.of(diariesConfig.getRoot());
		String filesDirName = diariesConfig.getFiles();
		if (root == null || filesDirName == null) {
			throw RpcStatusException.internalError("Files directory not configured.");
		}
		final Path filesDir = root.resolve(filesDirName);
		if (filesDir == null) {
			throw RpcStatusException.internalError("Files directory is not configured on the server.");
		}
		log.info(String.format("UploadFile.handleRequest: filesDir: '%s'", filesDir));

		// --- Inputs ---
		final String name = Utilities.getString(args, "name"); // e.g. "my-photo.jpg"
		final String subdir = Utilities.getStringOrDefault(args, "subdir", ""); // optional

		// Disallow absolute or sneaky paths in subdir/name
		Path safeSubdir = Paths.get(subdir).normalize();
		if (safeSubdir.isAbsolute() || safeSubdir.toString().contains("..")) {
			throw RpcStatusException.badRequest("Invalid 'subdir'.");
		}

		// Disallow path separators in 'name' to avoid traversal (or allow and sanitize carefully)
		if (name.contains("/") || name.contains("\\") || name.contains("..") || name.isBlank()) {
			throw RpcStatusException.badRequest("Invalid 'name'.");
		}

		Path targetDir = filesDir.resolve(safeSubdir).normalize();
		if (!targetDir.startsWith(filesDir)) {
			throw RpcStatusException.badRequest("Resolved path escapes uploads root.");
		}
		Files.createDirectories(targetDir);

		Path target = targetDir.resolve(name).normalize();
		if (!target.startsWith(filesDir)) {
			throw RpcStatusException.badRequest("Resolved file path escapes uploads root.");
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
	}
}
