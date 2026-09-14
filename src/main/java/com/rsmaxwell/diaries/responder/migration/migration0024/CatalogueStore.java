package com.rsmaxwell.diaries.responder.migration.migration0024;

import java.util.*;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.repositoryImpl.ImageRepositoryImpl;
import jakarta.persistence.EntityManager;

/** Database operations are isolated for failure-injection tests. Production folding is PostgreSQL's index expression. */
interface CatalogueStore {
    List<ImagePublishDTO> rows();
    String fold(String path);
    String identity();
    void begin();
    Image insert(Image image);
    void commit();
    void rollback();
    static CatalogueStore jpa(EntityManager em) {
        return new CatalogueStore() {
            public List<ImagePublishDTO> rows(){return new ImageRepositoryImpl(em).findAllOrderedByRelativePath().stream().map(ImagePublishDTO::new).toList();}
            public String fold(String path){return em.createNativeQuery("select lower(cast(:path as text) COLLATE pg_catalog.pg_unicode_fast)",String.class).setParameter("path",path).getSingleResult();}
            public String identity(){return em.createNativeQuery("select current_database() || ':' || (select oid::text from pg_database where datname=current_database()) || ':' || coalesce(inet_server_addr()::text,'local') || ':' || coalesce(inet_server_port()::text,'local') || ':' || current_setting('server_version')",String.class).getSingleResult();}
            public void begin(){em.getTransaction().begin();em.unwrap(org.hibernate.Session.class).createNativeMutationQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();em.unwrap(org.hibernate.Session.class).createNativeMutationQuery("LOCK TABLE public.image IN SHARE ROW EXCLUSIVE MODE").executeUpdate();}
            public Image insert(Image image){new ImageRepositoryImpl(em).save(image);return image;}
            public void commit(){em.getTransaction().commit();}
            public void rollback(){if(em.getTransaction().isActive())em.getTransaction().rollback();em.clear();}
        };
    }
}
