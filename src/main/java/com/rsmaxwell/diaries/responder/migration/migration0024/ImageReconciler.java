package com.rsmaxwell.diaries.responder.migration.migration0024;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.*;

/** Offline, two-way reconciliation. Only Image rows may be inserted; files are never rewritten. */
public final class ImageReconciler {
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final Comparator<Entry> ORDER=Comparator.comparing(Entry::relativePath).thenComparing(e->e.databaseId()==null?0L:e.databaseId());
    public record Entry(String relativePath,Long databaseId,String status,String kind,long size,String modified,String fileKey,
            String checksum,String mimeType,int width,int height,String detail) {
        Entry status(String value){return new Entry(relativePath,databaseId,value,kind,size,modified,fileKey,checksum,mimeType,width,height,detail);}
        Image image(){return Image.builder().relativePath(relativePath).originalFilename(relativePath.substring(relativePath.lastIndexOf('/')+1))
            .mimeType(mimeType).width(width).height(height).checksum(checksum).build();}
        List<?> fingerprint(){if(kind.equals("directory"))return List.of(relativePath,kind,fileKey);return Arrays.asList(relativePath,kind,size,modified,fileKey,checksum,mimeType,width,height,detail);}
    }
    public record Plan(int schemaVersion,String mode,String filesRoot,String rootKey,String databaseIdentity,Map<String,String> inputs,
            List<ImagePublishDTO> baselineRows,Map<String,List<?>> files,List<Entry> creates) { }
    record Observed(String raw,String canonical,Path path,BasicFileAttributes attributes,String error) { }
    record ApplyResult(String relativePath,Long databaseId,String status) { }
    private final ImagePathPolicy paths;
    private final ImageMetadataInspector inspector=new ImageMetadataInspector();
    private final CatalogueStore store;
    private final Map<String,String> folds=new HashMap<>();
    private final Map<String,String> inputs;
    private List<Entry> inventory=new ArrayList<>();
    private final List<ApplyResult> results=new ArrayList<>();
    private boolean commitAttempted,committed;
    private Plan generated;
    public ImageReconciler(Path root,CatalogueStore store,Map<String,String> inputs) throws IOException {
        this.paths=new ImagePathPolicy(root);this.store=store;this.inputs=Map.copyOf(inputs);
    }
    String fold(String value){return folds.computeIfAbsent(value,store::fold);}
    static String sha(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static String key(BasicFileAttributes attrs){return String.valueOf(attrs.fileKey());}
    static BasicFileAttributes attrs(Path path) throws IOException{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    static void prepareOutput(Path output,Path root) throws Exception {
        Path absolute=output.toAbsolutePath().normalize();Path ancestor=absolute;
        while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        Path resolved=ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
        if(resolved.startsWith(root))throw new IllegalArgumentException("Evidence output must be outside Files root");
        Files.createDirectories(resolved);
        if(!resolved.toRealPath().equals(resolved))throw new IllegalArgumentException("Output path alias");
        try(var stream=Files.list(resolved)){if(stream.findAny().isPresent())throw new IllegalArgumentException("Evidence directory must be empty");}
    }
    List<Entry> scan(List<ImagePublishDTO> rows) throws Exception {
        List<Observed> observed=new ArrayList<>();
        Files.walkFileTree(paths.root(),new SimpleFileVisitor<>() {
            private void add(Path p,BasicFileAttributes a,String error){
                String raw=paths.root().relativize(p).toString().replace('\\','/');String canonical=raw;
                try{canonical=paths.canonicalPath(raw);}catch(IllegalArgumentException bad){error="INVALID_PATH";}
                observed.add(new Observed(raw,canonical,p,a,error));
            }
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) throws IOException {
                if(dir.equals(paths.root()))return FileVisitResult.CONTINUE;
                if(dir.getFileName().toString().equalsIgnoreCase(".image-staging"))return FileVisitResult.SKIP_SUBTREE;
                if(a.isSymbolicLink() || a.isOther() || !dir.toRealPath().equals(dir.toAbsolutePath().normalize())){add(dir,a,"SYMLINK_OR_ESCAPE");return FileVisitResult.SKIP_SUBTREE;}
                add(dir,a,"");return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p,BasicFileAttributes a){add(p,a,a.isSymbolicLink() || a.isOther()?"SYMLINK_OR_ESCAPE":"");return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFileFailed(Path p,IOException failure){add(p,null,"UNREADABLE");return FileVisitResult.CONTINUE;}
        });
        Map<String,Set<String>> aliases=new HashMap<>();
        for(var item:observed)if(item.error.isEmpty())aliases.computeIfAbsent(fold(item.canonical),k->new HashSet<>()).add(item.raw);
        Map<String,ImagePublishDTO> byPath=new HashMap<>();
        for(var row:rows){row.validate();byPath.put(fold(row.getRelativePath()),row);
            for(String prefix:prefixes(row.getRelativePath()))aliases.computeIfAbsent(fold(prefix),k->new HashSet<>()).add(prefix);}
        Set<Long> seen=new HashSet<>();List<Entry> entries=new ArrayList<>();
        for(var item:observed){
            ImagePublishDTO row=item.error.equals("INVALID_PATH")?null:byPath.get(fold(item.canonical));
            if(row!=null)seen.add(row.getId());
            String status=item.error;
            if(status.isEmpty() && prefixes(item.canonical).stream().anyMatch(p->aliases.getOrDefault(fold(p),Set.of()).size()>1))status="CASE_COLLISION";
            if(status.isEmpty() && !item.raw.equals(item.canonical))status="PATH_NORMALIZATION_REQUIRED";
            var a=item.attributes;String kind=a!=null && a.isDirectory()?"directory":"file";
            Entry entry=new Entry(item.canonical,row==null?null:row.getId(),status,kind,a==null?0:a.size(),a==null?"":a.lastModifiedTime().toString(),a==null?"":key(a),"","",0,0,"");
            if(status.isEmpty()) {
                if(kind.equals("directory"))entry=entry.status(row==null?"DIRECTORY":"CATALOGUED_METADATA_CONFLICT");
                else entry=inspect(item.path,entry,row);
            }
            entries.add(entry);
        }
        for(var row:rows)if(!seen.contains(row.getId()))entries.add(new Entry(row.getRelativePath(),row.getId(),"DATABASE_ROW_MISSING_FILE","database",0,"","",row.getChecksum(),row.getMimeType(),row.getWidth(),row.getHeight(),""));
        entries.sort(ORDER);return entries;
    }
    private Entry inspect(Path file,Entry base,ImagePublishDTO row) {
        try {
            Path resolved=paths.resolve(base.relativePath);if(!Files.isSameFile(resolved,file))return base.status("CASE_COLLISION");
            var before=attrs(file);var result=inspector.inspect(file,"application/octet-stream",null);var after=attrs(file);
            if(before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !key(before).equals(key(after)))return base.status("CHANGED_DURING_SCAN");
            var image=result.image().orElse(null);
            String status=image==null?(row==null?"UNSUPPORTED":"CATALOGUED_METADATA_CONFLICT"):
                row==null?"CREATE_MISSING":matches(row,image.mimeType(),image.width(),image.height(),image.checksum())?"CATALOGUED_MATCH":"CATALOGUED_METADATA_CONFLICT";
            return new Entry(base.relativePath,base.databaseId,status,"file",result.size(),after.lastModifiedTime().toString(),key(after),result.checksum(),image==null?"":image.mimeType(),image==null?0:image.width(),image==null?0:image.height(),"");
        } catch(Exception failure){
            // Keep readable bytes fingerprinted even when full image decoding rejects them.
            String hash="";String status="UNREADABLE";
            try {hash=sha(file);var after=attrs(file);if(after.size()!=base.size || !after.lastModifiedTime().toString().equals(base.modified) || !key(after).equals(base.fileKey))status="CHANGED_DURING_SCAN";}catch(Exception unreadable){ }
            Set<String> safeMessages=Set.of("File exceeds inspection limit or is not regular","File exceeds inspection limit","Supported image decoder unavailable or image is corrupt","Invalid image dimensions or frame count","Decoded image exceeds pixel budget","Image decoding reported corrupt content","Image content cannot be decoded","JPEG end marker missing","GIF trailer missing","Truncated or inconsistent WebP container","Invalid PNG signature","Truncated PNG chunk","PNG chunk checksum mismatch","Invalid PNG end chunk","PNG end chunk missing");
            String detail=failure.getMessage()!=null && safeMessages.contains(failure.getMessage())?failure.getMessage():failure.getClass().getSimpleName();
            return new Entry(base.relativePath,base.databaseId,status,base.kind,base.size,base.modified,base.fileKey,hash,"",0,0,detail);
        }
    }
    static boolean matches(ImagePublishDTO row,String mime,int width,int height,String hash){return Objects.equals(row.getMimeType(),mime)&&row.getWidth()==width&&row.getHeight()==height&&Objects.equals(row.getChecksum(),hash);}
    static List<String> prefixes(String path){var values=new ArrayList<String>();for(int i=path.indexOf('/');i>=0;i=path.indexOf('/',i+1))values.add(path.substring(0,i));values.add(path);return values;}
    static Map<String,List<?>> manifest(List<Entry> entries){Map<String,List<?>> result=new TreeMap<>();for(var e:entries)if(!e.kind.equals("database"))result.put(e.relativePath+"\u0000"+e.fileKey,e.fingerprint());return result;}
    Plan plan(String mode,List<ImagePublishDTO> rows) throws Exception {
        return new Plan(1,mode,paths.root().toString(),key(attrs(paths.root())),store.identity(),inputs,rows,manifest(inventory),inventory.stream().filter(e->e.status.equals("CREATE_MISSING")).toList());
    }
    public void run(Path output,String mode,Path reviewedPlan,Path candidates) throws Exception {
        if(!Set.of("dry-run","apply").contains(mode))throw new IllegalArgumentException("Mode must be dry-run or apply");
        if(mode.equals("apply") && reviewedPlan==null)throw new IllegalArgumentException("Apply requires a reviewed dry-run plan");
        prepareOutput(output,paths.root());Instant start=Instant.now();String outcome="FAILED";String failureType="";
        String databaseIdentity=store.identity();String rootKey=key(attrs(paths.root()));
        String reviewedHash=reviewedPlan==null?null:sha(reviewedPlan);
        Map<String,Object> verification=new LinkedHashMap<>();
        List<Map<String,String>> candidateRows=new ArrayList<>();
        try {
            verifyCandidates(candidates);
            candidateRows=CandidateCrossReference.read(candidates);
            verifyCandidates(candidates);
            if(mode.equals("apply")) {
                Plan reviewed=JSON.readValue(reviewedPlan.toFile(),Plan.class);
                if(!Objects.equals(reviewedHash,sha(reviewedPlan)))throw new IllegalArgumentException("Reviewed plan changed while reading");
                new ImageCatalogueService(paths,inspector).withCatalogueLock(()->{apply(reviewed,candidates);return null;});
                outcome="COMMITTED";
            } else {var rows=store.rows();inventory=scan(rows);generated=plan(mode,rows);outcome="DRY_RUN_COMPLETE";}
            var after=store.rows();var post=scan(after);
            verification.put("imageCount",after.size());verification.put("remainingCreates",post.stream().filter(e->e.status.equals("CREATE_MISSING")).count());
            verification.put("metadataConflicts",post.stream().filter(e->e.status.equals("CATALOGUED_METADATA_CONFLICT")).count());
            if(mode.equals("apply")) {
                for(var result:results)if(post.stream().noneMatch(e->e.relativePath.equals(result.relativePath) && Objects.equals(e.databaseId,result.databaseId) && e.status.equals("CATALOGUED_MATCH")))throw new IllegalStateException("Committed image failed verification");
            } else if(!JSON.writeValueAsString(manifest(inventory)).equals(JSON.writeValueAsString(manifest(post))) || !JSON.writeValueAsString(generated.baselineRows).equals(JSON.writeValueAsString(after)))throw new IllegalStateException("Inputs changed during dry-run");
            CandidateCrossReference.write(output.resolve("0024-candidate-cross-reference.csv"),candidateRows,paths,inventory,after,this::fold);
            verification.put("candidateCrossReferenceComplete",true);
            verification.put("verified",true);
        } catch(Exception failure) {
            failureType=failure.getClass().getSimpleName();outcome=committed?"COMMITTED_VERIFICATION_FAILED":commitAttempted?"COMMIT_OUTCOME_UNKNOWN":"ROLLED_BACK_OR_NOT_APPLIED";
            if(!committed)try{store.rollback();}catch(Exception rollback){outcome="RECOVERY_REQUIRED";}
            verification.put("verified",false);verification.put("failureType",failureType);
            throw new IllegalStateException("Reconciliation failed; inspect the new evidence directory ("+outcome+")",failure);
        } finally {
            if(generated!=null)JSON.writeValue(output.resolve("0024-create-plan.json").toFile(),generated);
            else JSON.writeValue(output.resolve("0024-create-plan.json").toFile(),Map.of("schemaVersion",1,"mode",mode,"valid",false));
            writeInventory(output.resolve("0024-file-inventory.csv"),inventory);
            writeInventory(output.resolve("0024-conflicts.csv"),inventory.stream().filter(e->!Set.of("DIRECTORY","UNSUPPORTED","CATALOGUED_MATCH","CREATE_MISSING").contains(e.status)).toList());
            if(mode.equals("apply")){List<List<?>> lines=new ArrayList<>();for(var r:results)lines.add(Arrays.asList(r.relativePath,r.databaseId,committed?r.status:commitAttempted?"OUTCOME_UNKNOWN":"ROLLED_BACK"));
                EvidenceCsv.write(output.resolve("0024-apply-results.csv"),List.of("relativePath","imageId","status"),lines);}
            if(!Files.exists(output.resolve("0024-candidate-cross-reference.csv"))) {
                verification.put("candidateCrossReferenceComplete",false);
                CandidateCrossReference.write(output.resolve("0024-candidate-cross-reference.csv"),List.of(),paths,List.of(),List.of(),this::fold);
            }
            JSON.writeValue(output.resolve("0024-post-apply-verification.json").toFile(),verification);
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("schemaVersion",1);summary.put("toolVersion",toolVersion());summary.put("startedAt",start.toString());summary.put("endedAt",Instant.now().toString());summary.put("mode",mode);summary.put("outcome",outcome);summary.put("failureType",failureType);
            summary.put("filesRoot",paths.root().toString());summary.put("rootKey",rootKey);summary.put("databaseIdentity",databaseIdentity);summary.put("inputs",inputs);summary.put("reviewedPlanSha256",reviewedHash);
            Map<String,Long> counts=new TreeMap<>();for(var e:inventory)counts.merge(e.status,1L,Long::sum);summary.put("counts",counts);summary.put("insertedRows",committed?results.stream().filter(r->r.status.equals("CREATED")).count():0);
            summary.put("reservedDirectoryExcluded",".image-staging (inspect recovery material separately)");JSON.writeValue(output.resolve("0024-summary.json").toFile(),summary);
            hashEvidence(output);
        }
    }
    private void verifyCandidates(Path candidates) throws Exception {
        if(inputs.containsKey("candidatesSha256") && !Objects.equals(inputs.get("candidatesSha256"),candidates==null?"":sha(candidates)))throw new IllegalArgumentException("Candidate input changed");
    }
    private void apply(Plan reviewed,Path candidates) throws Exception {
        if(reviewed.schemaVersion!=1 || !reviewed.mode.equals("dry-run") || !reviewed.filesRoot.equals(paths.root().toString()) || !reviewed.rootKey.equals(key(attrs(paths.root()))) || !reviewed.databaseIdentity.equals(store.identity()) || !reviewed.inputs.equals(inputs))throw new IllegalArgumentException("Reviewed input identity mismatch");
        store.begin();var rows=store.rows();inventory=scan(rows);generated=plan("apply",rows);
        if(!JSON.writeValueAsString(reviewed.files).equals(JSON.writeValueAsString(manifest(inventory)))) {
            inventory=inventory.stream().map(e->e.kind.equals("file")?e.status("CHANGED_DURING_SCAN"):e).toList();throw new IllegalStateException("Files changed since dry-run");
        }
        Map<Long,ImagePublishDTO> current=new HashMap<>();for(var row:rows)current.put(row.getId(),row);
        for(var prior:reviewed.baselineRows){var now=current.remove(prior.getId());if(now==null || !prior.toJson().equals(now.toJson()))throw new IllegalStateException("Baseline catalogue changed");}
        Map<String,Entry> planned=new TreeMap<>();for(var e:reviewed.creates){if(!e.status.equals("CREATE_MISSING") || planned.put(e.relativePath,e)!=null)throw new IllegalArgumentException("Invalid create plan");}
        for(var row:current.values()){var e=planned.get(row.getRelativePath());if(e==null || !matches(row,e.mimeType,e.width,e.height,e.checksum) || row.getVersion()!=0 || !Objects.equals(row.getOriginalFilename(),e.image().getOriginalFilename()) || !row.getCaption().isEmpty() || !row.getAltText().isEmpty())throw new IllegalStateException("Unreviewed catalogue changes");}
        Map<String,Entry> scanned=new HashMap<>();for(var e:inventory)scanned.put(e.relativePath,e);
        for(var e:planned.values()) {
            Entry now=scanned.get(e.relativePath);
            if(now==null || !JSON.writeValueAsString(e.fingerprint()).equals(JSON.writeValueAsString(now.fingerprint())))throw new IllegalStateException("Planned fingerprint changed");
            if(now.status.equals("CATALOGUED_MATCH")){results.add(new ApplyResult(e.relativePath,now.databaseId,"ALREADY_MATCHED"));continue;}
            if(!now.status.equals("CREATE_MISSING"))throw new IllegalStateException("Planned path is no longer safe");
            Image image=store.insert(e.image());results.add(new ApplyResult(e.relativePath,image.getId(),"CREATED"));
        }
        // Re-read every planned image immediately before commit while application file operations are locked out.
        for(var e:planned.values()){var checked=inspect(paths.resolve(e.relativePath),e,null);if(!JSON.writeValueAsString(e.fingerprint()).equals(JSON.writeValueAsString(checked.fingerprint())) || !checked.status.equals("CREATE_MISSING"))throw new IllegalStateException("File changed during apply");}
        verifyCandidates(candidates);
        commitAttempted=true;store.commit();committed=true;
    }
    static void writeInventory(Path file,List<Entry> entries) throws Exception {
        List<List<?>> lines=new ArrayList<>();for(var e:entries)lines.add(Arrays.asList(e.relativePath,e.databaseId,e.status,e.kind,e.size,e.modified,e.checksum,e.mimeType,e.width,e.height,e.detail));
        EvidenceCsv.write(file,List.of("relativePath","imageId","status","kind","size","lastModified","sha256","mimeType","width","height","detail"),lines);
    }
    static String toolVersion(){try(var in=ImageReconciler.class.getResourceAsStream("/build-info.properties")){var props=new Properties();if(in!=null)props.load(in);return props.getProperty("version","development")+":"+props.getProperty("gitCommit","unknown");}catch(Exception failure){return "development";}}
    static void hashEvidence(Path directory) throws Exception {
        List<String> lines=new ArrayList<>();try(var files=Files.list(directory)){for(Path file:files.filter(Files::isRegularFile).sorted().toList())if(!file.getFileName().toString().equals("SHA256SUMS.txt"))lines.add(sha(file)+"  "+file.getFileName());}
        Files.write(directory.resolve("SHA256SUMS.txt"),lines,java.nio.charset.StandardCharsets.UTF_8);
    }
}
