package io.github.suryayelaswarapu.speclint.convert;

import io.github.suryayelaswarapu.speclint.SpecKind;
import java.util.List;
import java.util.Map;

/** Output of a conversion: the new OpenAPI 3.1 document plus notes for the user. */
public record ConversionResult(
        Map<String, Object> document, SpecKind sourceKind, List<String> warnings) {}
