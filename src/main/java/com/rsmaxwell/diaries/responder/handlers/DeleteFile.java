package com.rsmaxwell.diaries.responder.handlers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService;
import com.rsmaxwell.diaries.responder.utilities.ImageMetadataInspector;
import com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

public class DeleteFile extends RequestHandler {
    @Override
    public Response handleRequest(Object ctx, Map<String,Object> args, List<UserProperty> properties) throws Exception {
        DiaryContext context = (DiaryContext)ctx;
        var claims = Authorization.checkToken(context, "access", Authorization.getAccessToken(properties));
        Authorization.checkActive(claims);
        Authorization.checkRoleAtLeast(claims, Role.EDITOR);
        var config = context.getConfig().getDiaries();
        if (config == null || config.getRoot() == null || config.getFiles() == null)
            throw RpcStatusException.internalError("Files directory not configured.");
        ImagePathPolicy paths;
        try { paths = new ImagePathPolicy(Path.of(config.getRoot()).resolve(config.getFiles())); }
        catch (IOException | IllegalArgumentException failure) { throw RpcStatusException.internalError("Files directory unavailable."); }
        String name = Utilities.getString(args, "name");
        String subdir = Utilities.getStringOrDefault(args, "subdir", "");
        String canonical;
        try { canonical = paths.uploadPath(subdir, name); }
        catch (IllegalArgumentException failure) { throw RpcStatusException.badRequest("Invalid file path."); }
        var service = new ImageCatalogueService(paths, new ImageMetadataInspector(), deletionCatalogue(context));
        Path target;
        try { target = service.deleteUncatalogued(canonical); }
        catch (java.nio.file.FileAlreadyExistsException protectedPath) {
            throw RpcStatusException.conflict("Image catalogue protects path: " + canonical);
        } catch (java.nio.file.DirectoryNotEmptyException notEmpty) {
            throw RpcStatusException.conflict("Directory is not empty: " + canonical);
        } catch (IOException invalidPath) {
            throw RpcStatusException.badRequest("Invalid or inaccessible file path: " + canonical);
        }
        int slash = canonical.lastIndexOf('/');
        return Response.success(Map.of("name", name, "subdir", slash < 0 ? "" : canonical.substring(0, slash), "path", target.toString()));
    }

    protected ImageCatalogueService.Catalogue deletionCatalogue(DiaryContext context) throws RpcStatusException {
        if (context.getEntityManagerFactory() == null) throw RpcStatusException.internalError("Image catalogue unavailable.");
        return ImageCatalogueService.jpaCatalogue(context.getEntityManagerFactory());
    }
}
