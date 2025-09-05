package com.rsmaxwell.diaries.response.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.ConflictException;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.ws.rs.BadRequestException;

public class UploadFile extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UploadFile.class);
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final Set<String> AllowedContentType = Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "application/octet-stream");
	private static final long MAX_BYTES = 20L * 1024 * 1024;

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) {
		try {
			log.info("UploadFile.handleRequest");

			// --- Auth ---
			String accessToken = Authorization.getAccessToken(userProperties);
			DiaryContext context = (DiaryContext) ctx;
			if (Authorization.checkToken(context, "access", accessToken) == null) {
				log.info("UploadFile.handleRequest: Authorization.check: Failed!");
				throw new Unauthorised();
			}
			log.info("UploadFile.handleRequest: Authorization.check: OK!");

			// --- Config ---
			DiariesConfig diariesConfig = context.getConfig().getDiaries();
			Path root = Path.of(diariesConfig.getRoot());
			String filesDirName = diariesConfig.getFiles();
			if (root == null || filesDirName == null) {
				return Response.internalError("Files directory not configured.");
			}
			final Path filesDir = root.resolve(filesDirName);
			if (filesDir == null) {
				return Response.internalError("Files directory is not configured on the server.");
			}
			log.info(String.format("UploadFile.handleRequest: filesDir: '%s'", filesDir));
			Files.createDirectories(filesDir);

			// --- Inputs ---
			final String contentType = Utilities.getString(args, "contentType"); // e.g. "application/octet-stream"
			final String name = Utilities.getString(args, "name"); // e.g. "my-photo.jpg"
			final String subdir = Utilities.getStringOrDefault(args, "subdir", ""); // optional
			final Boolean overwrite = Utilities.getBooleanOrDefault(args, "overwrite", false);
			final String sha256HexExpected = Utilities.getStringOrDefault(args, "sha256", null);
			final String dataB64 = Utilities.getString(args, "bytes");
			final Long size = Utilities.getLong(args, "size");

			log.info("UploadFile: name='{}', contentType='{}', subdir='{}', size={}, sha256?={}", name, contentType, subdir, size, sha256HexExpected != null);

			if (size > MAX_BYTES) {
				throw new BadRequestException("File size too large.");
			}

			String clientType = (contentType == null) ? "" : contentType.toLowerCase();
			if (!AllowedContentType.contains(clientType)) {
				throw new BadRequestException("Unsupported contentType: " + contentType);
			}

			// --- UploadFile ---
			Path tmp = createUploadTemp(filesDir);
			long written = 0L;
			String sha256Pre = null;
			var md = java.security.MessageDigest.getInstance("SHA-256");

			try (var ascii = new java.io.ByteArrayInputStream(dataB64.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
					var b64 = java.util.Base64.getDecoder().wrap(ascii);
					var din = new java.security.DigestInputStream(b64, md);
					var out = java.nio.file.Files.newOutputStream(tmp, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = din.read(buf)) != -1) {
					out.write(buf, 0, n);
					written += n;

					if (written > MAX_BYTES) {
						throw new BadRequestException("File bytes too large.");
					}
				}
			} catch (Throwable t) {
				try {
					Files.deleteIfExists(tmp);
				} catch (Exception ignore) {
				}
				throw t;
			}
			// Pre-commit hash of exactly what you wrote
			sha256Pre = java.util.HexFormat.of().formatHex(md.digest());

			// 2) Size check
			if (written != size) {
				Files.deleteIfExists(tmp);
				throw new BadRequestException("Length mismatch: expected " + size + " but decoded " + written);
			}

			// 3) If client provided a hash, **verify before moving**
			if (sha256HexExpected != null && !sha256HexExpected.isBlank() && !sha256HexExpected.equalsIgnoreCase(sha256Pre)) {
				Files.deleteIfExists(tmp);
				throw new BadRequestException("SHA-256 mismatch (pre-commit).");
			}

			// --- Resolve & sanitize target path ---
			// Base uploads directory from your server config

			// Disallow absolute or sneaky paths in subdir/name
			Path safeSubdir = Paths.get(subdir).normalize();
			if (safeSubdir.isAbsolute() || safeSubdir.toString().contains("..")) {
				Files.deleteIfExists(tmp);
				throw new BadRequestException("Invalid 'subdir'.");
			}

			// Disallow path separators in 'name' to avoid traversal (or allow and sanitize carefully)
			if (name.contains("/") || name.contains("\\") || name.contains("..") || name.isBlank()) {
				Files.deleteIfExists(tmp);
				throw new BadRequestException("Invalid 'name'.");
			}

			Path targetDir = filesDir.resolve(safeSubdir).normalize();
			if (!targetDir.startsWith(filesDir)) {
				Files.deleteIfExists(tmp);
				throw new BadRequestException("Resolved path escapes uploads root.");
			}
			Files.createDirectories(targetDir);

			Path target = targetDir.resolve(name).normalize();
			if (!target.startsWith(filesDir)) {
				Files.deleteIfExists(tmp);
				throw new BadRequestException("Resolved file path escapes uploads root.");
			}

			if (!overwrite && Files.exists(target)) {
				Files.deleteIfExists(tmp);
				throw new ConflictException("File already exists: " + target.getFileName());
			}

			// 4) Promote temp into place (atomic when supported)
			try {
				if (overwrite && Files.exists(target)) {
					Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				} else {
					Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				}
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				// Fallback (still safe, just not atomic)
				if (overwrite && Files.exists(target)) {
					Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} else {
					Files.move(tmp, target);
				}
			}

			// --- Post-write checksum (optional, for extra safety) ---
			if (sha256HexExpected != null && !sha256HexExpected.isBlank()) {
				try (var in = Files.newInputStream(target)) {
					String actual = sha256Hex(in);
					if (!sha256HexExpected.equalsIgnoreCase(actual)) {
						// integrity fail: remove the file
						try {
							Files.deleteIfExists(target);
						} catch (Exception ignored) {
						}

						return Response.internalError("Post-write SHA-256 mismatch (file removed).");
					}
				}
			}

			// --- Success payload ---
			//@formatter:off
			Map<String, Object> result = Map.of(
					"name", name, 
					"subdir", safeSubdir.toString(), 
					"size", size, 
					"path", target.toString(), 
					"url",	"/" + filesDirName + "/" + (safeSubdir.toString().isEmpty() ? "" : safeSubdir + "/") + name
			);
			//@formatter:on		

			return Response.success(result);

		} catch (Unauthorised u) {
			return Response.unauthorized();
		} catch (BadRequestException e) {
			return Response.badRequest(e.getMessage());
		} catch (ConflictException e) {
			return Response.conflict(e.getMessage());
		} catch (Exception e) {
			log.error("UploadFile.handleRequest: error", e);
			return Response.internalError(e.getMessage());
		}
	}

	private static String sha256Hex(InputStream in) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] chunk = new byte[8192];
		int n;
		while ((n = in.read(chunk)) > 0) {
			md.update(chunk, 0, n);
		}
		return toHex(md.digest());
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private static Path createUploadTemp(Path targetDir) throws IOException {
		Files.createDirectories(targetDir);

		// Best effort: private perms on POSIX filesystems
		try {
			if (Files.getFileStore(targetDir).supportsFileAttributeView(PosixFileAttributeView.class)) {
				FileAttribute<Set<PosixFilePermission>> attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
				return Files.createTempFile(targetDir, ".upload-", ".part", attrs);
			}
		} catch (IOException ignore) {
		}
		// Fallback (Windows / non-POSIX): just create the temp file
		return Files.createTempFile(targetDir, ".upload-", ".part");
	}
}
