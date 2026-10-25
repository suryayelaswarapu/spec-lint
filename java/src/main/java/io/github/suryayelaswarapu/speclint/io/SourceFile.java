package io.github.suryayelaswarapu.speclint.io;

import java.nio.file.Path;

/** A file read from disk, with its text and location. */
public record SourceFile(Path path, String text) {
    public String name() {
        Path file = path.getFileName();
        return file == null ? path.toString() : file.toString();
    }
}
