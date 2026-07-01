package com.pally.shared.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A minimal in-memory {@link MultipartFile} backed by a byte array. Lets a
 * server-side caller (e.g. the marking-ingest orchestrator) feed already-read
 * bytes into the same upload+extract pipeline that HTTP multipart uploads use,
 * without a real HTTP request.
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public InMemoryMultipartFile(String name, String originalFilename,
                                 String contentType, byte[] content) {
        this.name = name != null ? name : "file";
        this.originalFilename = originalFilename != null ? originalFilename : "file";
        this.contentType = contentType;
        this.content = content != null ? content : new byte[0];
    }

    @Override public String getName() { return name; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        try (OutputStream out = Files.newOutputStream(dest.toPath())) {
            out.write(content);
        }
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        Files.write(dest, content);
    }
}
