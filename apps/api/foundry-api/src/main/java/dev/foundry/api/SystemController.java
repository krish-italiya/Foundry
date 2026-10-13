package dev.foundry.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SystemController {

    @GetMapping("/api/v1/system")
    public Map<String, String> getSystemInfo() {
        return Map.of(
                "name", "Foundry",
                "status", "UP",
                "version", "0.1.0"
        );
    }
}