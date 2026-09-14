package com.rsmaxwell.diaries.responder.migration.migration0024;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;

class ImageReconcilerTest {
    @TempDir Path temp;
    static class MemoryStore implements CatalogueStore {
        List<ImagePublishDTO> data=new ArrayList<>(), snapshot;
        int inserts,failAt=Integer.MAX_VALUE;boolean failCommit;Runnable onInsert=()->{};
        public List<ImagePublishDTO> rows(){return List.copyOf(data);}
        public String fold(String path){return path.toLowerCase(Locale.ROOT);}
        public String identity(){return "test-database";}
        public void begin(){snapshot=new ArrayList<>(data);}
        public Image insert(Image image){if(++inserts==failAt)throw new IllegalStateException("Injected insert failure");image.setId(100L+inserts);data.add(new ImagePublishDTO(image));onInsert.run();return image;}
        public void commit(){if(failCommit)throw new IllegalStateException("Injected uncertain commit");snapshot=null;}
        public void rollback(){if(snapshot!=null){data=snapshot;snapshot=null;}}
    }
    Path root() throws Exception{return Files.createDirectories(temp.resolve("files"));}
    Path png(String name) throws Exception {
        Path file=root().resolve(name);Files.createDirectories(file.getParent());
        try(var in=getClass().getResourceAsStream("/image-inspection/sample.png")){assertNotNull(in);Files.copy(in,file,StandardCopyOption.REPLACE_EXISTING);}return file;
    }
    ImageReconciler tool(MemoryStore store) throws Exception{return new ImageReconciler(root(),store,Map.of());}
    Path dry(MemoryStore store,String output) throws Exception{Path dir=temp.resolve(output);tool(store).run(dir,"dry-run",null,null);return dir.resolve("0024-create-plan.json");}
    List<Map<String,String>> csv(String dir,String name) throws Exception{return EvidenceCsv.read(temp.resolve(dir).resolve(name));}
    String outcome(String dir) throws Exception{return ImageReconciler.JSON.readTree(temp.resolve(dir).resolve("0024-summary.json").toFile()).path("outcome").asText();}

    @Test void dryRunIsReadOnlySortedAndUsesContentType() throws Exception {
        png("z.png");png("a.jpg");Files.writeString(root().resolve("notes.txt"),"ordinary file");MemoryStore store=new MemoryStore();
        String before=ImageReconciler.sha(root().resolve("a.jpg"));dry(store,"dry");
        assertTrue(store.data.isEmpty());assertFalse(Files.exists(root().resolve(".image-staging")));
        var rows=csv("dry","0024-file-inventory.csv");assertEquals(List.of("a.jpg","notes.txt","z.png"),rows.stream().map(r->r.get("relativePath")).toList());
        assertEquals("image/png",rows.get(0).get("mimeType"));assertEquals("UNSUPPORTED",rows.get(1).get("status"));assertEquals(before,ImageReconciler.sha(root().resolve("a.jpg")));
        assertFalse(Files.exists(temp.resolve("dry/0024-apply-results.csv")));assertTrue(Files.size(temp.resolve("dry/SHA256SUMS.txt"))>0);
    }
    @Test void applyIsAtomicAndIdempotentIncludingSameReviewedPlan() throws Exception {
        png("b.png");png("a.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");
        tool(store).run(temp.resolve("apply"),"apply",plan,null);assertEquals(2,store.data.size());var metadata=ImageReconciler.JSON.valueToTree(store.data);
        tool(store).run(temp.resolve("again"),"apply",plan,null);assertEquals(metadata,ImageReconciler.JSON.valueToTree(store.data));assertTrue(csv("again","0024-apply-results.csv").stream().allMatch(r->r.get("status").equals("ALREADY_MATCHED")));
        Path post=dry(store,"post");assertTrue(ImageReconciler.JSON.readValue(post.toFile(),ImageReconciler.Plan.class).creates().isEmpty());
        tool(store).run(temp.resolve("empty"),"apply",post,null);assertTrue(csv("empty","0024-apply-results.csv").isEmpty());
    }
    @Test void twoWayInventoryReportsDriftAndMissingRows() throws Exception {
        png("match.png");png("drift.png");MemoryStore store=new MemoryStore();var entries=tool(store).scan(List.of());long id=1;
        for(var entry:entries){Image image=entry.image();image.setId(id++);if(image.getRelativePath().equals("drift.png"))image.setChecksum("0".repeat(64));store.data.add(new ImagePublishDTO(image));}
        Image missing=entries.get(0).image();missing.setId(3L);missing.setRelativePath("missing.png");store.data.add(new ImagePublishDTO(missing));
        dry(store,"dry");var statuses=csv("dry","0024-file-inventory.csv").stream().map(r->r.get("status")).toList();
        assertEquals(List.of("CATALOGUED_METADATA_CONFLICT","CATALOGUED_MATCH","DATABASE_ROW_MISSING_FILE"),statuses);assertEquals(2,csv("dry","0024-conflicts.csv").size());
    }
    @Test void changedFileRejectsReviewedPlan() throws Exception {
        Path file=png("a.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");Files.writeString(file,"changed");
        assertThrows(IllegalStateException.class,()->tool(store).run(temp.resolve("apply"),"apply",plan,null));assertTrue(store.data.isEmpty());assertEquals("ROLLED_BACK_OR_NOT_APPLIED",outcome("apply"));
    }
    @Test void mutationDuringBatchRollsBackAllInserts() throws Exception {
        Path file=png("a.png");png("b.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");
        store.onInsert=()->{try{Files.writeString(file,"concurrent external mutation");}catch(Exception e){throw new RuntimeException(e);}};
        assertThrows(IllegalStateException.class,()->tool(store).run(temp.resolve("apply"),"apply",plan,null));assertTrue(store.data.isEmpty());assertEquals(2,csv("apply","0024-apply-results.csv").size());assertTrue(csv("apply","0024-apply-results.csv").stream().allMatch(r->r.get("status").equals("ROLLED_BACK")));
    }
    @Test void insertFailureRollsBackEarlierInsert() throws Exception {
        png("a.png");png("b.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");store.failAt=2;
        assertThrows(IllegalStateException.class,()->tool(store).run(temp.resolve("apply"),"apply",plan,null));assertTrue(store.data.isEmpty());assertEquals("ROLLED_BACK",csv("apply","0024-apply-results.csv").get(0).get("status"));
    }
    @Test void uncertainCommitRequiresReview() throws Exception {
        png("a.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");store.failCommit=true;
        assertThrows(IllegalStateException.class,()->tool(store).run(temp.resolve("apply"),"apply",plan,null));assertEquals("COMMIT_OUTCOME_UNKNOWN",outcome("apply"));assertEquals("OUTCOME_UNKNOWN",csv("apply","0024-apply-results.csv").get(0).get("status"));
    }
    @Test void existingEvidenceAndUnsafeOutputsAreRejected() throws Exception {
        png("a.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");String hash=ImageReconciler.sha(plan);
        assertThrows(IllegalArgumentException.class,()->dry(store,"dry"));assertEquals(hash,ImageReconciler.sha(plan));
        assertThrows(IllegalArgumentException.class,()->tool(store).run(root().resolve("evidence"),"dry-run",null,null));
        assertThrows(IllegalArgumentException.class,()->tool(store).run(temp.resolve("apply"),"apply",null,null));
    }
    @Test void caseAliasAgainstDatabaseCannotCreateOrMatch() throws Exception {
        png("folder/image.png");MemoryStore store=new MemoryStore();Image image=tool(store).scan(List.of()).stream().filter(e->e.status().equals("CREATE_MISSING")).findFirst().orElseThrow().image();image.setId(1L);image.setRelativePath("Folder/image.png");store.data.add(new ImagePublishDTO(image));
        dry(store,"dry");assertTrue(csv("dry","0024-file-inventory.csv").stream().allMatch(r->r.get("status").equals("CASE_COLLISION")));
    }
    @Test void corruptImageIsReportedAndNeverPlanned() throws Exception {
        Files.write(root().resolve("corrupt.png"),new byte[]{(byte)137,80,78,71,13,10,26,10});MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");
        assertEquals("UNREADABLE",csv("dry","0024-file-inventory.csv").get(0).get("status"));assertTrue(ImageReconciler.JSON.readValue(plan.toFile(),ImageReconciler.Plan.class).creates().isEmpty());
    }
    @Test void candidateReferencesPreserveIdentityAndDoNotChooseConversions() throws Exception {
        png("one/images/a.png");png("two/images/a.png");png("one/images/unique.png");MemoryStore store=new MemoryStore();Path plan=dry(store,"dry");tool(store).run(temp.resolve("apply"),"apply",plan,null);
        Path candidates=temp.resolve("candidates.csv");EvidenceCsv.write(candidates,List.of("fragment_id","embedded_image_src_values"),List.of(List.of("42","/files/one/images/unique.png | images/unique.png | images/a.png | https://example.com/a.png | ../escape.png | missing.png")));
        tool(store).run(temp.resolve("xref"),"dry-run",null,candidates);var rows=csv("xref","0024-candidate-cross-reference.csv");
        assertEquals(6,rows.size());assertEquals(2,rows.stream().filter(r->r.get("status").equals("MATCHED_ONE_IMAGE")).count());assertEquals(Set.of("MATCHED_ONE_IMAGE","AMBIGUOUS_PATH","EXTERNAL_URL","INVALID_PATH","MISSING_FILE_OR_IMAGE"),new HashSet<>(rows.stream().map(r->r.get("status")).toList()));assertTrue(rows.stream().allMatch(r->r.get("fragmentId").equals("42") && r.get("sourceSha256").length()==64));
    }
    @Test void changedCandidatesAndDatabaseInputsAreRejected() throws Exception {
        png("a.png");MemoryStore store=new MemoryStore();Path candidate=temp.resolve("c.csv");EvidenceCsv.write(candidate,List.of("database_fragment_id","legacy_image_reference"),List.of(List.of("1","a.png")));
        Map<String,String> inputs=Map.of("candidatesSha256",ImageReconciler.sha(candidate));new ImageReconciler(root(),store,inputs).run(temp.resolve("dry"),"dry-run",null,candidate);
        Files.writeString(candidate,"changed");assertThrows(IllegalStateException.class,()->new ImageReconciler(root(),store,inputs).run(temp.resolve("changed"),"apply",temp.resolve("dry/0024-create-plan.json"),candidate));assertTrue(store.data.isEmpty());
        assertThrows(IllegalStateException.class,()->new ImageReconciler(root(),store,Map.of("other","identity")).run(temp.resolve("identity"),"apply",temp.resolve("dry/0024-create-plan.json"),null));
    }
    @Test void csvRoundTripsQuotedMultilineSourcesAndRejectsMalformedInput() throws Exception {
        Path file=temp.resolve("csv");EvidenceCsv.write(file,List.of("fragment_id","embedded_image_src_values"),List.of(List.of("5","images/a, \"quoted\"\nname.png")));
        assertEquals("images/a, \"quoted\"\nname.png",EvidenceCsv.read(file).get(0).get("embedded_image_src_values"));
        Files.writeString(file,"a,b\n1,\"unfinished");assertThrows(IllegalArgumentException.class,()->EvidenceCsv.read(file));
    }
    @Test void linksAreReportedWithoutFollowingThem() throws Exception {
        Path outside=Files.createDirectories(temp.resolve("outside"));Files.writeString(outside.resolve("sentinel.txt"),"unchanged");Path link=root().resolve("escape");
        try {
            try {Files.createSymbolicLink(link,outside);}catch(java.io.IOException unavailable){
                if(!System.getProperty("os.name").startsWith("Windows"))throw unavailable;
                var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
                String output=new String(process.getInputStream().readAllBytes());assertEquals(0,process.waitFor(),output);
            }
            MemoryStore store=new MemoryStore();dry(store,"dry");var inventory=csv("dry","0024-file-inventory.csv");
            assertEquals(1,inventory.size());assertEquals("SYMLINK_OR_ESCAPE",inventory.get(0).get("status"));assertEquals("unchanged",Files.readString(outside.resolve("sentinel.txt")));
        } finally {Files.deleteIfExists(link);}
    }
    @Test void candidateReportFailureAfterCommitIsNotReportedAsSuccess() throws Exception {
        png("a.png");boolean[] fail={false};MemoryStore store=new MemoryStore(){@Override public String fold(String path){if(fail[0] && path.equals("absent.png"))throw new IllegalStateException("Injected query failure");return super.fold(path);}};
        Path candidate=temp.resolve("candidate.csv");EvidenceCsv.write(candidate,List.of("fragment_id","embedded_image_src_values"),List.of(List.of("1","absent.png")));
        tool(store).run(temp.resolve("dry"),"dry-run",null,candidate);fail[0]=true;
        assertThrows(IllegalStateException.class,()->tool(store).run(temp.resolve("apply"),"apply",temp.resolve("dry/0024-create-plan.json"),candidate));
        assertEquals(1,store.data.size());assertEquals("COMMITTED_VERIFICATION_FAILED",outcome("apply"));assertTrue(Files.exists(temp.resolve("apply/SHA256SUMS.txt")));
    }
}
