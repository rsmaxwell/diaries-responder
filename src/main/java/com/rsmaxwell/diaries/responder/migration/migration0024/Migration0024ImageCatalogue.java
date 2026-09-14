package com.rsmaxwell.diaries.responder.migration.migration0024;

import java.nio.file.Path;
import java.util.*;
import org.apache.commons.cli.*;
import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.utilities.GetEntityManager;

/** Explicit administrative command; never registered as an RPC handler. */
public final class Migration0024ImageCatalogue {
    public static void main(String[] args) throws Exception {
        Options options=new Options();
        for(String name:List.of("config","output","mode","plan","0022-candidates"))options.addOption(Option.builder().longOpt(name).hasArg().required(name.equals("config") || name.equals("output")).build());
        var command=new DefaultParser().parse(options,args);
        String mode=command.getOptionValue("mode","dry-run");
        Path configFile=Path.of(command.getOptionValue("config")).toRealPath();
        Path candidates=command.hasOption("0022-candidates")?Path.of(command.getOptionValue("0022-candidates")).toRealPath():null;
        Path plan=command.hasOption("plan")?Path.of(command.getOptionValue("plan")).toRealPath():null;
        if(!Set.of("dry-run","apply").contains(mode) || (mode.equals("apply") && plan==null))throw new IllegalArgumentException("Use dry-run, or apply with --plan=<reviewed-dry-run-plan>");
        byte[] configBytes=java.nio.file.Files.readAllBytes(configFile);
        Config config=ImageReconciler.JSON.readValue(configBytes,Config.class);
        // Migration must never auto-create/update schema through runtime configuration.
        var properties=new HashMap<String,String>();
        if(config.getDb().getAdditionalConnectionProperties()!=null)properties.putAll(config.getDb().getAdditionalConnectionProperties());
        properties.put("hibernate.hbm2ddl.auto","validate");properties.put("jakarta.persistence.schema-generation.database.action","none");
        config.getDb().setAdditionalConnectionProperties(properties);
        Map<String,String> inputs=new TreeMap<>();inputs.put("configSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(configBytes)));inputs.put("candidatesSha256",candidates==null?"":ImageReconciler.sha(candidates));
        inputs.put("configFilename",configFile.getFileName().toString());inputs.put("candidatesFilename",candidates==null?"":candidates.getFileName().toString());
        try(var factory=GetEntityManager.adminFactory(config.getDb());var em=factory.createEntityManager()) {
            var reconciler=new ImageReconciler(Path.of(config.getDiaries().getRoot()).resolve(config.getDiaries().getFiles()),CatalogueStore.jpa(em),inputs);
            reconciler.run(Path.of(command.getOptionValue("output")).toAbsolutePath().normalize(),mode,plan,candidates);
        }
        System.out.println("0024 reconciliation completed; inspect the evidence summary.");
    }
}
