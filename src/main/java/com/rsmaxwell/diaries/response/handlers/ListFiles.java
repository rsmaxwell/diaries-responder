package com.rsmaxwell.diaries.response.handlers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

//convenience DTO (or use your own class)
record ImageItem(String name, String url, long size, long mtime) {
}

public class ListFiles extends RequestHandler {

	private static final Logger log = LogManager.getLogger(ListFiles.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) {

		try {
			log.info("ListFiles.handleRequest: args: {}", mapper.writeValueAsString(args));

			// --- Auth ---
			String accessToken = Authorization.getAccessToken(userProperties);
			DiaryContext context = (DiaryContext) ctx;
			if (Authorization.checkToken(context, "access", accessToken) == null) {
				log.info("ListFiles.handleRequest: Authorization.check: Failed!");
				throw new Unauthorised();
			}
			log.info("ListFiles.handleRequest: Authorization.check: OK!");

			DiariesConfig diariesConfig = context.getConfig().getDiaries();
			Path base = Path.of(diariesConfig.getOriginal());
			String uploadsDir = diariesConfig.getUploadsDir();
			final Path baseUploadsDir = base.resolve(uploadsDir);
			if (baseUploadsDir == null) {
				return Response.internalError("Uploads directory is not configured on the server.");
			}
			log.info(String.format("ListFiles.handleRequest: baseUploadsDir: '%s'", baseUploadsDir));
			Files.createDirectories(baseUploadsDir);

			final List<ImageItem> list;
			try (Stream<Path> s = Files.list(baseUploadsDir)) {
				list = s.filter(Files::isRegularFile).filter(p -> {
					String fn = p.getFileName().toString().toLowerCase();
					return fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".gif") || fn.endsWith(".webp");
				}).sorted(Comparator.comparingLong((Path p) -> {
					try {
						return Files.getLastModifiedTime(p).toMillis();
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}).reversed()).limit(500).map(p -> {
					try {
						return new ImageItem(p.getFileName().toString(), "/" + uploadsDir + "/" + p.getFileName().toString(), // public URL
								Files.size(p), Files.getLastModifiedTime(p).toMillis());
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}).collect(Collectors.toList());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			// --- Success payload ---
			return Response.success(list);

		} catch (Unauthorised u) {
			return Response.unauthorized();
		} catch (Exception e) {
			log.error("ListFiles.handleRequest: error", e);
			return Response.internalError(e.getMessage());
		}
	}
}
