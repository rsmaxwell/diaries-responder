package com.rsmaxwell.diaries.responder.utilities;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageItem(
        String name,
        String url,
        long size,
        long mtime,
        Long dateTaken,
        boolean dir) {

    private static final ZoneId LOG_ZONE = ZoneId.systemDefault();

    private static final DateTimeFormatter LOG_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(LOG_ZONE);

    /**
     * Factory for a directory item.
     */
    public static ImageItem directory(String name, long mtime) {
        return new ImageItem(
                name,
                null,
                0L,
                mtime,
                null,
                true);
    }

    /**
     * Factory for a file item.
     */
    public static ImageItem file(
            String name,
            String url,
            long size,
            long mtime,
            Long dateTaken) {

        return new ImageItem(
                name,
                url,
                size,
                mtime,
                dateTaken,
                false);
    }

    public String mtimeStr() {
        return formatTime(mtime);
    }

    /**
     * Formats dateTaken when present.
     *
     * Negative values, representing dates before 1970, and zero are valid.
     * When dateTaken is null, an em dash is returned.
     */
    public String dateTakenStr() {
        if (dateTaken == null) {
            return "—";
        }

        return formatTime(dateTaken);
    }

    private static String formatTime(long millis) {
        try {
            return LOG_FMT.format(Instant.ofEpochMilli(millis));
        } catch (RuntimeException exception) {
            return Long.toString(millis);
        }
    }
}
