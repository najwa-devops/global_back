package com.invoice_reader.invoice_reader.servises.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
@Slf4j
public class OlmocrFallbackService {

    private final boolean enabled;
    private final String mode;
    private final String workspaceDir;
    private final String dockerImage;
    private final boolean dockerGpu;
    private final String cliCommand;
    private final String server;
    private final String model;
    private final String apiKey;
    private final long timeoutSeconds;

    public OlmocrFallbackService(
            @Value("${olmocr.enabled:false}") boolean enabled,
            @Value("${olmocr.mode:docker}") String mode,
            @Value("${olmocr.workspace-dir:${java.io.tmpdir}/olmocr-workspace}") String workspaceDir,
            @Value("${olmocr.docker-image:alleninstituteforai/olmocr:latest-with-model}") String dockerImage,
            @Value("${olmocr.docker-gpu:false}") boolean dockerGpu,
            @Value("${olmocr.command:olmocr}") String cliCommand,
            @Value("${olmocr.server:}") String server,
            @Value("${olmocr.model:allenai/olmOCR-2-7B-1025-FP8}") String model,
            @Value("${olmocr.api-key:}") String apiKey,
            @Value("${olmocr.timeout-seconds:600}") long timeoutSeconds
    ) {
        this.enabled = enabled;
        this.mode = mode;
        this.workspaceDir = workspaceDir;
        this.dockerImage = dockerImage;
        this.dockerGpu = dockerGpu;
        this.cliCommand = cliCommand;
        this.server = server;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<OlmocrResult> runIfNeeded(Path inputFile, Map<String, Object> extractedData) {
        if (!enabled || inputFile == null) {
            return Optional.empty();
        }

        int qualityScore = asInt(extractedData.get("qualityScore"));
        String difficultyClass = asString(extractedData.get("difficultyClass"));
        boolean reviewRequired = asBoolean(extractedData.get("reviewRequired"));

        boolean difficult = qualityScore > 0 && qualityScore < 75;
        boolean flaggedDifficult = "DIFFICILE".equalsIgnoreCase(difficultyClass)
                || "TRES_DIFFICILE".equalsIgnoreCase(difficultyClass);

        if (!difficult && !flaggedDifficult && !reviewRequired) {
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        if (difficult) reasons.add("qualityScore=" + qualityScore);
        if (flaggedDifficult) reasons.add("difficultyClass=" + difficultyClass);
        if (reviewRequired) reasons.add("reviewRequired=true");

        try {
            return run(inputFile, reasons);
        } catch (Exception e) {
            log.warn("olmOCR fallback ignoré: {}", e.getMessage());
            extractedData.put("olmocrError", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<OlmocrResult> runAsPrimary(Path inputFile, Map<String, Object> extractedData) {
        if (!enabled || inputFile == null) {
            return Optional.empty();
        }
        List<String> reasons = List.of("primaryMode=true");
        try {
            return run(inputFile, reasons);
        } catch (Exception e) {
            log.warn("olmOCR primary ignoré: {}", e.getMessage());
            if (extractedData != null) {
                extractedData.put("olmocrError", e.getMessage());
            }
            return Optional.empty();
        }
    }

    private Optional<OlmocrResult> run(Path inputFile, List<String> reasons) throws IOException, InterruptedException {
        Path baseDir = Paths.get(workspaceDir).toAbsolutePath();
        Files.createDirectories(baseDir);

        String runId = UUID.randomUUID().toString();
        Path runDir = baseDir.resolve(runId);
        Path inputDir = runDir.resolve("input");
        Path outputDir = runDir.resolve("output");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);

        Path copiedInput = inputDir.resolve(inputFile.getFileName().toString());
        Files.copy(inputFile, copiedInput, StandardCopyOption.REPLACE_EXISTING);

        List<String> command = buildCommand(inputDir, outputDir, copiedInput.getFileName().toString());
        long startedAt = System.nanoTime();

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        if (!apiKey.isBlank()) {
            processBuilder.environment().put("OPENAI_API_KEY", apiKey);
        }

        Process process = processBuilder.start();
        String logs = readProcessOutput(process);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("olmOCR timeout after " + timeoutSeconds + "s");
        }
        if (process.exitValue() != 0) {
            throw new IOException("olmOCR exit code " + process.exitValue() + ": " + trimLogs(logs));
        }

        Path markdownFile = findMarkdownFile(outputDir)
                .orElseThrow(() -> new IOException("Aucun markdown olmOCR produit"));
        String markdown = Files.readString(markdownFile, StandardCharsets.UTF_8);
        if (markdown.isBlank()) {
            throw new IOException("Sortie olmOCR vide");
        }

        long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return Optional.of(new OlmocrResult(
                markdown,
                durationMs,
                markdownFile.toString(),
                reasons,
                mode
        ));
    }

    private List<String> buildCommand(Path inputDir, Path outputDir, String filename) {
        if ("cli".equalsIgnoreCase(mode)) {
            List<String> command = new ArrayList<>();
            command.add(cliCommand);
            command.add(outputDir.toString());
            command.add("--markdown");
            command.add("--pdfs");
            command.add(inputDir.resolve(filename).toString());
            if (!server.isBlank()) {
                command.add("--server");
                command.add(server);
            }
            if (!model.isBlank()) {
                command.add("--model");
                command.add(model);
            }
            if (!apiKey.isBlank()) {
                command.add("--api_key");
                command.add(apiKey);
            }
            return command;
        }

        String script = buildDockerScript(filename);
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        if (dockerGpu) {
            command.add("--gpus");
            command.add("all");
        }
        command.add("-v");
        command.add(inputDir.toAbsolutePath() + ":/workspace/input");
        command.add("-v");
        command.add(outputDir.toAbsolutePath() + ":/workspace/output");
        command.add(dockerImage);
        command.add("bash");
        command.add("-lc");
        command.add(script);
        return command;
    }

    private String buildDockerScript(String filename) {
        StringBuilder script = new StringBuilder();
        script.append("olmocr /workspace/output --markdown --pdfs /workspace/input/").append(filename);
        if (!server.isBlank()) {
            script.append(" --server ").append(server);
        }
        if (!model.isBlank()) {
            script.append(" --model ").append(model);
        }
        if (!apiKey.isBlank()) {
            script.append(" --api_key ").append(apiKey);
        }
        return script.toString();
    }

    private Optional<Path> findMarkdownFile(Path outputDir) throws IOException {
        Path markdownDir = outputDir.resolve("markdown");
        if (!Files.exists(markdownDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.walk(markdownDir)) {
            return stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .findFirst();
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String trimLogs(String logs) {
        if (logs == null) {
            return "";
        }
        return logs.length() > 1200 ? logs.substring(logs.length() - 1200) : logs;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    public record OlmocrResult(
            String markdownText,
            long durationMs,
            String markdownPath,
            List<String> reasons,
            String mode
    ) {
    }
}
