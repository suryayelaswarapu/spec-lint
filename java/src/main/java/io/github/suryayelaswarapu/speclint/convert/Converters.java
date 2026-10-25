package io.github.suryayelaswarapu.speclint.convert;

import io.github.suryayelaswarapu.speclint.SpecKind;
import io.github.suryayelaswarapu.speclint.io.SourceFile;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Detects the input format and dispatches to the right converter. */
public final class Converters {
    private Converters() {}

    public static ConversionResult convertSource(SourceFile source, boolean allowLocalImports) {
        if (SpecKind.looksLikeXml(source.text())) {
            SpecKind.detectXml(source.text());
            Path base = source.path().toAbsolutePath().getParent();
            return new WsdlConverter(base, allowLocalImports).convert(source.text());
        }
        Map<String, Object> doc = SpecIO.parseStructured(source.text(), source.name());
        return switch (SpecKind.detectDocument(doc)) {
            case SWAGGER2 -> new Swagger2Converter(doc).convert();
            case OPENAPI30 -> OpenApi30Upgrader.upgrade(doc);
            default -> new ConversionResult(doc, SpecKind.OPENAPI31, List.of());
        };
    }

    public static ConversionResult convertSource(SourceFile source) {
        return convertSource(source, true);
    }
}
