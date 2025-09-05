package com.rsmaxwell.diaries.response.handlers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

//EXIF
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.dto.ListFilesResponse;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.ConflictException;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.diaries.response.utilities.ImageItem;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.ws.rs.BadRequestException;

//convenience DTO (or use your own class)

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

			// --- Config ---
			DiariesConfig diariesConfig = context.getConfig().getDiaries();
			Path root = Path.of(diariesConfig.getRoot());
			String filesDirName = diariesConfig.getFiles();
			if (root == null || filesDirName == null) {
				throw new Exception("Files directory not configured.");
			}
			final Path filesDir = root.resolve(filesDirName);
			if (filesDir == null) {
				throw new Exception("Files directory is not configured on the server.");
			}
			log.info(String.format("ListFiles.handleRequest: filesDir: '%s'", filesDir));
			Files.createDirectories(filesDir);

			// --- Inputs ---
			final String subdir = Utilities.getStringOrDefault(args, "subdir", ""); // optional

			// ---- Sanitize subdir ----
			Path safeSubdir = Paths.get(subdir == null ? "" : subdir).normalize();
			if (safeSubdir.isAbsolute() || safeSubdir.toString().contains("..")) {
				return Response.badRequest("Invalid 'subdir'.");
			}

			Path targetDir = filesDir.resolve(safeSubdir).normalize();
			if (!targetDir.startsWith(filesDir)) {
				return Response.badRequest("Resolved path escapes uploads root.");
			}

			// --- ListFiles ---
			final Set<String> allowedExt = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");
			final String filesContext = "/" + Objects.requireNonNull(diariesConfig.getFiles());

			//@formatter:off
			List<ImageItem> items;
			try (Stream<Path> listing = Files.list(targetDir)) {
			    List<Path> children = listing.collect(Collectors.toList());

			    // Directories first (alpha)
			    List<ImageItem> dirItems = children.stream()
			        .filter(Files::isDirectory)
			        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
			        .map(p -> {
			            long mtime = lastModifiedMillis(p);
			            String name = p.getFileName().toString();
			            return ImageItem.builder()
			                .name(name)
			                .url(null)       // no URL for folders
			                .size(0L)
			                .mtime(mtime)
			                .dateTaken(null)
			                .dir(true) 
			                .build();
			        })
			        .collect(Collectors.toList());

			    // Files next (filtered by extension, sorted by mtime desc)
			    List<ImageItem> fileItems = children.stream()
			        .filter(Files::isRegularFile)
			        .filter(p -> {
			            String fn = p.getFileName().toString().toLowerCase();
			            return allowedExt.stream().anyMatch(fn::endsWith);
			        })
			        .sorted(Comparator.comparingLong(this::lastModifiedMillis)
			        .reversed())
			        .map(p -> {
			            long mtime = lastModifiedMillis(p);
			            long size;
			            try { size = Files.size(p); } catch (IOException e) { throw new UncheckedIOException(e); }
			            String name = p.getFileName().toString();
			            String url = buildUrlPath(filesContext, subdir, name);
			            Long dateTaken = readDateTakenMillis(p);

			            return ImageItem.builder()
			                .name(name)
			                .url(url)
			                .size(size)
			                .mtime(mtime)
			                .dateTaken(dateTaken)
			                .dir(false)
			                .build();
			        })
			        .collect(Collectors.toList());

			    items = new ArrayList<>(dirItems.size() + fileItems.size());
			    items.addAll(dirItems);
			    items.addAll(fileItems);
			}
			//@formatter:on

			// --- Success payload ---
			ListFilesResponse response = new ListFilesResponse(safeSubdir, items);
			return Response.success(response);

		} catch (Unauthorised u) {
			return Response.unauthorized();
		} catch (BadRequestException e) {
			return Response.badRequest(e.getMessage());
		} catch (ConflictException e) {
			return Response.conflict(e.getMessage());
		} catch (Exception e) {
			log.error("ListFiles.handleRequest: error", e);
			return Response.internalError(e.getMessage());
		}
	}

	private static Long readDateTakenMillis(Path imagePath) {
		try {
			Metadata md = ImageMetadataReader.readMetadata(imagePath.toFile());

			// Primary: EXIF SubIFD "DateTimeOriginal" (Date Taken)
			ExifSubIFDDirectory subIfd = md.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
			if (subIfd != null) {
				Date d = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
				if (d == null) {
					d = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED);
				}
				if (d != null) {
					return d.getTime();
				}
			}

			// Fallback: EXIF IFD0 "DateTime" (often last edit in camera)
			ExifIFD0Directory ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
			if (ifd0 != null) {
				Date d = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME);
				if (d != null) {
					return d.getTime();
				}
			}

			// Optional fallbacks for HEIC/MP4/QuickTime containers
			QuickTimeDirectory qt = md.getFirstDirectoryOfType(QuickTimeDirectory.class);
			if (qt != null) {
				Date d = qt.getDate(QuickTimeDirectory.TAG_CREATION_TIME);
				if (d != null) {
					return d.getTime();
				}
			}
			Mp4Directory mp4 = md.getFirstDirectoryOfType(Mp4Directory.class);
			if (mp4 != null) {
				Date d = mp4.getDate(Mp4Directory.TAG_CREATION_TIME);
				if (d != null) {
					return d.getTime();
				}
			}

			return null;
		} catch (Exception e) {
			// swallow and fall back to mtime upstream
			return null;
		}
	}

	private long lastModifiedMillis(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Join URL path segments with a single slash, ignoring empty/null parts. */
	private static String buildUrlPath(String... parts) {
		String path = Stream.of(parts).filter(Objects::nonNull).map(s -> s.replace('\\', '/')) // defensive: never backslashes
				.map(s -> s.replaceAll("^/+", "")) // trim leading slashes
				.map(s -> s.replaceAll("/+$", "")) // trim trailing slashes
				.filter(s -> !s.isEmpty()).map(ListFiles::encodePathSegment) // encode each segment
				.collect(Collectors.joining("/"));

		return "/" + path; // always return an absolute path
	}

	/** Encode one URL path segment (space -> %20 instead of '+'). */
	private static String encodePathSegment(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
