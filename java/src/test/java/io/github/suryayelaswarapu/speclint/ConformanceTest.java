package io.github.suryayelaswarapu.speclint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The shared conformance files are the contract all three implementations must meet. */
class ConformanceTest {
    static Stream<Path> cases() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(TestSupport.testdata("conformance"))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesGolden(Path golden) {
        Map<String, Object> expected = TestSupport.readJson(golden);
        Map<String, Object> actual = Conformance.buildCase(TestSupport.testdata(),
                (String) expected.get("input"));
        for (String key : List.of("input", "sourceKind", "warnings", "converted", "modernized",
                "fixes", "findings")) {
            assertThat(TestSupport.normalize(actual.get(key)))
                    .as(golden.getFileName() + " -> " + key)
                    .isEqualTo(TestSupport.normalize(expected.get(key)));
        }
    }
}
