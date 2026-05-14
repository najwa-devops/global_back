package com.invoice_reader.invoice_reader.banque.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class BanqueFileStorageService {

    @Value("${banking.upload.dir}")
    private String bankingBaseDir;

    public StoredBankFile storeBankStatement(MultipartFile file) {
        return storeInBase(file, bankingBaseDir);
    }

    private StoredBankFile storeInBase(MultipartFile file, String rootDir) {
        try {
            LocalDate now = LocalDate.now();

            Path uploadPath = Paths.get(
                    rootDir,
                    "statements",
                    String.valueOf(now.getYear()),
                    String.format("%02d", now.getMonthValue())
            );

            Files.createDirectories(uploadPath);

            String uniqueName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path target = uploadPath.resolve(uniqueName);

            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "statement";
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            return new StoredBankFile(
                    uniqueName,
                    originalName,
                    contentType,
                    file.getSize(),
                    target.toString());

        } catch (Exception e) {
            throw new RuntimeException("Erreur stockage fichier", e);
        }
    }

    public Path load(String fullPath) {
        return Paths.get(fullPath);
    }

    public Path getBaseDirForFiles() {
        return Paths.get(bankingBaseDir, "statements");
    }

    public Path getBankBaseDirForFiles() {
        return Paths.get(bankingBaseDir, "statements");
    }

    public record StoredBankFile(
            String filename,
            String originalName,
            String contentType,
            long size,
            String filePath) {
    }


}
