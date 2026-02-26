package com.rsmaxwell.diaries.responder.dto;

import java.nio.file.Path;
import java.util.List;

import com.rsmaxwell.diaries.responder.utilities.ImageItem;

import lombok.Data;

// Minimal RPC payload for ListFiles

@Data
public class ListFilesResponse {

	private String subdir;
	private List<ImageItem> items;

	public ListFilesResponse(String subDir, List<ImageItem> items) {
		this.subdir = subDir;
		this.items = items;
	}

	public ListFilesResponse(Path subDirPath, List<ImageItem> items) {
		this.subdir = subDirPath.toString();
		this.items = items;
	}
}
