package com.rsmaxwell.diaries.responder.migration.migration0024;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import com.rsmaxwell.diaries.responder.config.*;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.GetEntityManager;

/** Actual PostgreSQL transaction/rollback and Unicode folding; only a disposable restored fixture. */
@EnabledIfEnvironmentVariable(named="DIARIES_IMAGE_WIRING_TEST_URL",matches=".+")
class ImageReconciliationIntegrationTest {
    @TempDir Path temp;
    @Test void realDatabaseRollbackThenApplyAndReplay() throws Exception {
        String url=System.getenv("DIARIES_IMAGE_WIRING_TEST_URL");assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/image_wiring_test"));URI uri=URI.create(url.substring(5));
        Jdbc jdbc=new Jdbc();jdbc.setDbms("postgresql");jdbc.setDriver("org.postgresql.Driver");User user=new User();user.setUsername("diaries");user.setPassword("");
        DbConfig db=new DbConfig();db.setJdbc(jdbc);db.setHost(uri.getHost());db.setPort(uri.getPort());db.setDatabase("image_wiring_test");db.setAdmin(user);db.setUsers(List.of(user));db.setAdditionalConnectionProperties(Map.of("hibernate.hbm2ddl.auto","validate"));
        Path root=Files.createDirectory(temp.resolve("files"));
        for(String path:List.of("a.png","b.png"))try(var in=getClass().getResourceAsStream("/image-inspection/sample.png")){Files.copy(in,root.resolve(path));}
        try(var factory=GetEntityManager.adminFactory(db);var em=factory.createEntityManager()) {
            CatalogueStore store=CatalogueStore.jpa(em);assertTrue(store.rows().isEmpty());assertEquals("i\u0307",store.fold("\u0130"));
            try {
                new ImageReconciler(root,store,Map.of()).run(temp.resolve("dry"),"dry-run",null,null);Path plan=temp.resolve("dry/0024-create-plan.json");
                CatalogueStore failing=new CatalogueStore(){int count;
                    public List<ImagePublishDTO> rows(){return store.rows();}public String fold(String path){return store.fold(path);}public String identity(){return store.identity();}
                    public void begin(){store.begin();}public Image insert(Image image){if(++count==2)throw new IllegalStateException("Injected second insert failure");return store.insert(image);}
                    public void commit(){store.commit();}public void rollback(){store.rollback();}
                };
                assertThrows(IllegalStateException.class,()->new ImageReconciler(root,failing,Map.of()).run(temp.resolve("failed"),"apply",plan,null));assertTrue(store.rows().isEmpty());
                new ImageReconciler(root,store,Map.of()).run(temp.resolve("apply"),"apply",plan,null);assertEquals(2,store.rows().size());var first=ImageReconciler.JSON.valueToTree(store.rows());
                new ImageReconciler(root,store,Map.of()).run(temp.resolve("again"),"apply",plan,null);assertEquals(first,ImageReconciler.JSON.valueToTree(store.rows()));
            } finally {store.rollback();em.getTransaction().begin();em.unwrap(org.hibernate.Session.class).createNativeMutationQuery("delete from public.image where relative_path in ('a.png','b.png')").executeUpdate();em.getTransaction().commit();}
        }
    }
}
