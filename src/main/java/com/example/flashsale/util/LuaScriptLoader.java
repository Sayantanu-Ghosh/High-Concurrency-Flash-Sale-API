package com.example.flashsale.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class LuaScriptLoader {

    public static String load(String scriptPath) {
        try {
            Resource resource = new ClassPathResource(scriptPath);
            if (!resource.exists()) {
                throw new IOException("Script not found: " + scriptPath);
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load Lua script: " + scriptPath, e);
        }
    }
}
