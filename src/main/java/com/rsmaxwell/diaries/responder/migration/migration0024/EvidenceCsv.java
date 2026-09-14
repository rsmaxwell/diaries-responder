package com.rsmaxwell.diaries.responder.migration.migration0024;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** RFC 4180 evidence CSV; quoted newlines and escaped quotes are data. */
final class EvidenceCsv {
    static List<Map<String,String>> read(Path file) throws Exception {
        String text=Files.readString(file,StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder field=new StringBuilder();
        boolean quoted=false, ended=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') { if(i+1<text.length() && text.charAt(i+1)=='"'){field.append('"');i++;} else {quoted=false;ended=true;} }
                else field.append(c);
            } else if(c==',' || c=='\n' || c=='\r') {
                row.add(field.toString());field.setLength(0);ended=false;
                if(c!=','){rows.add(row);row=new ArrayList<>();if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;}
            } else if(c=='"' && field.length()==0 && !ended) quoted=true;
            else {if(ended || c=='"')throw new IllegalArgumentException("Malformed evidence CSV");field.append(c);}
        }
        if(quoted)throw new IllegalArgumentException("Unclosed CSV field");
        if(field.length()>0 || ended || !row.isEmpty()){row.add(field.toString());rows.add(row);}
        if(rows.isEmpty())throw new IllegalArgumentException("CSV header missing");
        var headers=rows.remove(0); if(new HashSet<>(headers).size()!=headers.size())throw new IllegalArgumentException("Duplicate CSV header");
        List<Map<String,String>> result=new ArrayList<>();
        for(var values:rows){if(values.size()!=headers.size())throw new IllegalArgumentException("CSV column count mismatch");
            Map<String,String> record=new LinkedHashMap<>();for(int i=0;i<headers.size();i++)record.put(headers.get(i),values.get(i));result.add(record);}
        return result;
    }
    static void write(Path file,List<String> header,List<List<?>> rows) throws Exception {
        try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {
            List<List<?>> all=new ArrayList<>();all.add(header);all.addAll(rows);
            for(var row:all){for(int i=0;i<row.size();i++){if(i>0)writer.write(',');Object value=row.get(i);writer.write('"');
                writer.write((value==null?"":value.toString()).replace("\"","\"\""));writer.write('"');}writer.write('\n');}
        }
    }
}
