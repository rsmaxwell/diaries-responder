package com.rsmaxwell.diaries.responder.migration.migration0024;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy;

final class CandidateCrossReference {
    static List<Map<String,String>> read(Path file) throws Exception {
        if(file==null)return List.of();var rows=EvidenceCsv.read(file);
        if(!rows.isEmpty() && !(rows.get(0).containsKey("legacy_image_reference") || rows.get(0).containsKey("embedded_image_src_values")))throw new IllegalArgumentException("Unsupported 0022 candidate schema");
        return rows;
    }
    static void write(Path output,List<Map<String,String>> candidates,ImagePathPolicy policy,List<ImageReconciler.Entry> inventory,List<ImagePublishDTO> images,Function<String,String> fold) throws Exception {
        inventory=inventory.stream().filter(e->!e.status().equals("INVALID_PATH")).toList();
        List<List<?>> lines=new ArrayList<>();int inputRow=0;
        for(var candidate:candidates){inputRow++;
            String identity=candidate.getOrDefault("database_fragment_id",candidate.getOrDefault("fragment_id",""));
            String references=candidate.getOrDefault("legacy_image_reference",candidate.getOrDefault("embedded_image_src_values",""));int index=0;
            for(String source:references.split(" \\| ",-1)){index++;String status,relative="",method="";Long imageId=null;
                try {
                    String value=source.trim();
                    if(value.startsWith("//") || value.matches("(?i)https?://.*") || value.matches("(?i)(data|ftp):.*")) status="EXTERNAL_URL";
                    else {
                        if(value.matches("(?i)^[a-z]:.*") || value.startsWith("\\\\"))throw new IllegalArgumentException("Absolute path");
                        URI uri=URI.create(value.replace(" ","%20"));
                        if(uri.isAbsolute() || uri.getRawAuthority()!=null)throw new IllegalArgumentException("Nonlocal URI");
                        String local=URLDecoder.decode(uri.getRawPath().replace("+","%2B"),StandardCharsets.UTF_8);
                        if(local.startsWith("/files/"))local=local.substring(7);
                        if(local.startsWith("files/"))local=local.substring(6);
                        relative=policy.canonicalPath(local);String folded=fold.apply(relative);
                        Set<String> matches=new TreeSet<>();
                        for(var entry:inventory)if(entry.kind().equals("file") || entry.kind().equals("database")) {
                            String path=entry.relativePath();String key=fold.apply(path);
                            if(key.equals(folded))matches.add(path);
                        }
                        method="EXACT";
                        if(matches.isEmpty()) {method="UNIQUE_LOCAL_SUFFIX";
                            for(var entry:inventory)if(entry.kind().equals("file") || entry.kind().equals("database"))if(fold.apply(entry.relativePath()).endsWith("/"+folded))matches.add(entry.relativePath());}
                        if(matches.size()>1)status="AMBIGUOUS_PATH";
                        else if(matches.isEmpty())status="MISSING_FILE_OR_IMAGE";
                        else {
                            relative=matches.iterator().next();String chosen=relative;
                            boolean unsafe=inventory.stream().filter(e->e.relativePath().equals(chosen)).anyMatch(e->!Set.of("CATALOGUED_MATCH","CREATE_MISSING").contains(e.status()));
                            var matching=images.stream().filter(i->i.getRelativePath().equals(chosen)).toList();
                            if(unsafe)status=inventory.stream().anyMatch(e->e.relativePath().equals(chosen)&&e.status().equals("DATABASE_ROW_MISSING_FILE"))?"MISSING_FILE_OR_IMAGE":"AMBIGUOUS_PATH";
                            else if(matching.size()!=1)status=matching.size()>1?"AMBIGUOUS_PATH":"MISSING_FILE_OR_IMAGE";
                            else {status="MATCHED_ONE_IMAGE";imageId=matching.get(0).getId();}
                        }
                    }
                } catch(IllegalArgumentException invalid){status="INVALID_PATH";relative="";}
                // Original CSV row/index + source hash identifies evidence without echoing absolute host paths or HTML.
                String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
                lines.add(Arrays.asList(relative,identity,inputRow,index,status,imageId,method,hash));
            }
        }
        lines.sort(Comparator.comparing((List<?> r)->r.get(0).toString()).thenComparing(r->r.get(5)==null?0L:(Long)r.get(5)).thenComparing(r->r.get(1).toString()).thenComparingInt(r->(Integer)r.get(2)).thenComparingInt(r->(Integer)r.get(3)));
        EvidenceCsv.write(output,List.of("relativePath","fragmentId","inputRow","candidateIndex","status","imageId","matchMethod","sourceSha256"),lines);
    }
}
