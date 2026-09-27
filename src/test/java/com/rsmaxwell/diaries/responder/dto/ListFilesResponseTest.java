package com.rsmaxwell.diaries.responder.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListFilesResponseTest {
    @Test void directoryUsesPortableRpcSeparators() {
        assertEquals("step9/images", new ListFilesResponse(Path.of("step9", "images"), List.of()).getSubdir());
        assertEquals("", new ListFilesResponse(Path.of(""), List.of()).getSubdir());
    }
}
