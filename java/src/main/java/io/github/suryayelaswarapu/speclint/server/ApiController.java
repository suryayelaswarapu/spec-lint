package io.github.suryayelaswarapu.speclint.server;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints. The contract is docs/api/openapi.yaml (served at /api/v1/openapi.yaml). */
@RestController
public class ApiController {
    private final SpecService service;

    public ApiController(SpecService service) {
        this.service = service;
    }

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return service.health();
    }

    @GetMapping(path = "/api/v1/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> rules() {
        return service.rules();
    }

    @PostMapping(path = "/api/v1/convert", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> convert(@RequestBody Map<String, Object> body) {
        return service.convert(body);
    }

    @PostMapping(path = "/api/v1/lint", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> lint(@RequestBody Map<String, Object> body) {
        return service.lint(body);
    }

    @PostMapping(path = "/api/v1/fix", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fix(@RequestBody Map<String, Object> body) {
        return service.fix(body);
    }

    @PostMapping(path = "/api/v1/modernize", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> modernize(@RequestBody Map<String, Object> body) {
        return service.modernize(body);
    }
}
