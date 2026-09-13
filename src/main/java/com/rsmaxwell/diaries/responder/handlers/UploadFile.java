package com.rsmaxwell.diaries.responder.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService;
import com.rsmaxwell.diaries.responder.utilities.ImageMetadataInspector;
import com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy;
import com.rsmaxwell.diaries.responder.utilities.ResolvedUpload;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

public class UploadFile extends RequestHandler {
    private static final long MAX_BYTES = ImageMetadataInspector.DEFAULT_MAX_BYTES;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "application/octet-stream");

    @Override
    public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> properties) throws Exception {
        DiaryContext context = (DiaryContext) ctx;
        var claims = Authorization.checkToken(context, "access", Authorization.getAccessToken(properties));
        Authorization.checkActive(claims);
        Authorization.checkRoleAtLeast(claims, Role.EDITOR);

        String name = Utilities.getString(args, "name");
        String subdir = Utilities.getStringOrDefault(args, "subdir", "");
        String type = Utilities.getString(args, "contentType");
        String bytes = Utilities.getString(args, "bytes");
        Long size = Utilities.getLong(args, "size");
        boolean overwrite = Utilities.getBooleanOrDefault(args, "overwrite", false);
        String checksum = Utilities.getStringOrDefault(args, "sha256", null);
        if (checksum != null && checksum.isBlank()) checksum = null;
        if (size == null || size < 0 || size > MAX_BYTES) throw RpcStatusException.badRequest("Invalid file size.");
        if (type == null || !TYPES.contains(type.toLowerCase(Locale.ROOT))) throw RpcStatusException.badRequest("Unsupported contentType.");
        // Bound the encoded input before allocating another copy. Whitespace is not valid basic base64.
        if (bytes == null || bytes.length() > 4 * ((MAX_BYTES + 2) / 3)
                || bytes.chars().anyMatch(c -> c > 127)) throw RpcStatusException.badRequest("Invalid encoded file size or characters.");

        var config = context.getConfig().getDiaries();
        if (config == null || config.getRoot() == null || config.getFiles() == null)
            throw RpcStatusException.internalError("Files directory not configured.");
        ImagePathPolicy paths;
        try { paths = new ImagePathPolicy(Path.of(config.getRoot()).resolve(config.getFiles())); }
        catch (IOException | IllegalArgumentException failure) { throw RpcStatusException.internalError("Files directory unavailable."); }
        String canonical;
        try { canonical = paths.uploadPath(subdir, name); }
        catch (IllegalArgumentException failure) { throw RpcStatusException.badRequest("Invalid upload path."); }
        var catalogue = uploadCatalogue(context);
        // Check database identity before filesystem resolution can reject a case alias.
        if (catalogue.owns(canonical)) throw RpcStatusException.conflict("Path belongs to the Image catalogue.");
        var staging = new ImageCatalogueService(paths, new ImageMetadataInspector(), catalogue, uploadPublication(context));
        try { staging.verifyStorageCapabilities(); }
        catch (IOException failure) { throw RpcStatusException.internalError("Upload storage capabilities or permissions unavailable."); }
        ResolvedUpload upload;
        try {
            var encoded = new ByteArrayInputStream(bytes.getBytes(StandardCharsets.US_ASCII));
            var decoded = Base64.getDecoder().wrap(encoded);
            upload = staging.stage(decoded, subdir, name, type, size, checksum);
            if (encoded.available() != 0) {
                staging.discard(upload);
                throw new IOException("Trailing base64 data");
            }
        } catch (IOException | IllegalArgumentException failure) {
            // Do not expose bytes, tokens or absolute filesystem paths through error/log messages.
            throw RpcStatusException.badRequest("Upload rejected: invalid path, encoding, size, checksum or image content.");
        }
        try {
            // Recheck ownership under the shared file lock, then preserve any overwrite backup.
            var saved = staging.complete(upload, overwrite);
            Path target = upload.target();
            int slash = upload.relativePath().lastIndexOf('/');
            String directory = slash < 0 ? "" : upload.relativePath().substring(0, slash);
            var image = saved.map(com.rsmaxwell.diaries.responder.dto.ImagePublishDTO::new).orElse(null);
            return Response.success(new com.rsmaxwell.diaries.responder.dto.UploadFileResponse(
                    name, directory, upload.inspection().size(), target.toString(),
                    "/" + config.getFiles() + "/" + upload.relativePath(), image == null ? null : image.getId(), image));
        } catch (java.nio.file.FileAlreadyExistsException failure) {
            throw RpcStatusException.conflict("File already exists.");
        } catch (ImageCatalogueService.PublicationFailedException failure) {
            throw RpcStatusException.internalError("Image " + failure.committedImage().getId()
                    + " committed; retained publication failed. Database replay is required; retry will conflict.");
        } catch (ImageCatalogueService.RecoveryRequiredException failure) {
            org.slf4j.LoggerFactory.getLogger(UploadFile.class).error(
                    "Image upload requires recovery: target={}, backup={}", failure.target(), failure.backup());
            throw RpcStatusException.internalError("Image upload requires administrator recovery; files preserved.");
        } catch (ImageCatalogueService.WriteFailedException failure) {
            throw RpcStatusException.internalError("Image registration failed; file changes rolled back.");
        }
    }
    protected ImageCatalogueService.Publication uploadPublication(DiaryContext context) {
        return image -> {
            var publisher = context.getPublisherClient();
            if (publisher == null) throw new IOException("Image publisher unavailable");
            var delivery = publisher.publish("diaries/images/" + image.getId(), image.toJsonAsBytes(), 1, true);
            delivery.waitForCompletion(10_000);
            var reasons = delivery.getReasonCodes();
            if (reasons != null) for (int reason : reasons) {
                if (reason >= 128) throw new IOException("Image publication rejected by broker");
            }
        };
    }

    protected ImageCatalogueService.Catalogue uploadCatalogue(DiaryContext context) throws RpcStatusException {
        if (context.getEntityManagerFactory() == null)
            throw RpcStatusException.internalError("Image catalogue unavailable.");
        return ImageCatalogueService.jpaCatalogue(context.getEntityManagerFactory());
    }

}
