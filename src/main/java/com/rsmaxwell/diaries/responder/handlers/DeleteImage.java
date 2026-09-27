package com.rsmaxwell.diaries.responder.handlers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService;
import com.rsmaxwell.diaries.responder.utilities.ImageMetadataInspector;
import com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

/** Explicit Image lifecycle operation; generic DeleteFile remains protected. */
public class DeleteImage extends RequestHandler {
    @Override
    public Response handleRequest(Object ctx, Map<String,Object> args, List<UserProperty> properties) throws Exception {
        DiaryContext context = (DiaryContext) ctx;
        try {
            var claims = Authorization.checkToken(context, "access", Authorization.getAccessToken(properties == null ? List.of() : properties));
            Authorization.checkActive(claims);
            Authorization.checkRoleAtLeast(claims, Role.EDITOR);
        } catch (RpcStatusException | io.jsonwebtoken.JwtException | IllegalArgumentException | ClassCastException failure) {
            throw RpcStatusException.unauthorized("Active editor authorization required.");
        }
        if (args == null || !(args.get("name") instanceof String name) || name.isBlank())
            throw RpcStatusException.badRequest("Invalid 'name'.");
        Object directory = args.containsKey("subdir") ? args.get("subdir") : "";
        if (!(directory instanceof String subdir)) throw RpcStatusException.badRequest("Invalid 'subdir'.");
        ImagePathPolicy paths;
        try {
            var config = context.getConfig().getDiaries();
            paths = new ImagePathPolicy(Path.of(config.getRoot()).resolve(config.getFiles()));
        } catch (Exception failure) { throw RpcStatusException.internalError("Files directory unavailable."); }
        String canonical;
        try { canonical = paths.uploadPath(subdir, name); }
        catch (IllegalArgumentException failure) { throw RpcStatusException.badRequest("Invalid image path."); }
        try {
            var service = new ImageCatalogueService(paths, new ImageMetadataInspector(), deletionCatalogue(context));
            var deleted = service.delete(canonical, deletionPublication(context));
            return Response.success(Map.of("id", deleted.id(), "relativePath", deleted.relativePath(), "deleted", true));
        } catch (ImageCatalogueService.ImageNotFoundException failure) {
            throw RpcStatusException.notFound("Image not found.");
        } catch (ImageCatalogueService.ImageFileConflictException failure) {
            throw RpcStatusException.conflict("Image file is missing or is not a regular file.");
        } catch (ImageCatalogueService.InvalidImagePathException failure) {
            throw RpcStatusException.badRequest("Invalid or inaccessible image path.");
        } catch (ImageCatalogueService.DeletionRecoveryRequiredException failure) {
            throw RpcStatusException.internalError("Image deletion requires administrator recovery; completion could not be confirmed.");
        } catch (ImageCatalogueService.DeleteFailedException failure) {
            throw RpcStatusException.internalError("Image deletion failed; file changes rolled back.");
        } catch (Exception failure) {
            // Never let provider/JPA exception text expose internal paths through the RPC library.
            org.slf4j.LoggerFactory.getLogger(DeleteImage.class).error("Image deletion failed", failure);
            throw RpcStatusException.internalError("Image deletion failed.");
        }
    }

    protected ImageCatalogueService.Catalogue deletionCatalogue(DiaryContext context) throws RpcStatusException {
        if (context.getEntityManagerFactory() == null) throw RpcStatusException.internalError("Image catalogue unavailable.");
        return ImageCatalogueService.jpaCatalogue(context.getEntityManagerFactory());
    }

    protected ImageCatalogueService.TombstonePublication deletionPublication(DiaryContext context) {
        return image -> {
            if (context.getPublisherClient() == null) throw new IOException("Image publisher unavailable");
            ImagePublishDTO.builder().id(image.id()).build().removeAndAwait(context.getPublisherClient());
        };
    }
}
