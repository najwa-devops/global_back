package com.invoice_reader.invoice_reader.controller;

import com.invoice_reader.invoice_reader.servises.compat.MiniCompatibilityScanService;
import com.invoice_reader.invoice_reader.utils.InvoiceOcrMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LegacyMiniCompatibilityController {

    private final MiniCompatibilityScanService miniCompatibilityScanService;

    @PostMapping("/scan")
    public ResponseEntity<?> scanInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent) {
        try {
            InvoiceOcrMode requestedMode = InvoiceOcrMode.resolve(ocrMode, useAlphaAgent);
            log.info("Legacy /scan request: file={}, mode={}", file.getOriginalFilename(), requestedMode);
            if (requestedMode == InvoiceOcrMode.EVOLEO_AI) {
                return ResponseEntity.ok(miniCompatibilityScanService.scanPurchaseAlpha(file));
            }
            return ResponseEntity.ok(miniCompatibilityScanService.scanPurchase(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Legacy /scan failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "type", e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/scan/extract-field")
    public ResponseEntity<?> extractPurchaseField(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fieldName") String fieldName) {
        try {
            return ResponseEntity.ok(miniCompatibilityScanService.extractPurchaseField(file, fieldName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Legacy /scan/extract-field failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "type", e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/alpha")
    public ResponseEntity<?> alpha(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent) {
        try {
            InvoiceOcrMode requestedMode = InvoiceOcrMode.resolve(ocrMode, useAlphaAgent);
            log.info("Legacy /alpha request: file={}, mode={}", file.getOriginalFilename(), requestedMode);
            return ResponseEntity.ok(miniCompatibilityScanService.alphaPureOcr(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Legacy /alpha failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "type", e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/vente")
    public ResponseEntity<?> vente(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent) {
        try {
            InvoiceOcrMode requestedMode = InvoiceOcrMode.resolve(ocrMode, useAlphaAgent);
            log.info("Legacy /vente request: file={}, mode={}", file.getOriginalFilename(), requestedMode);
            if (requestedMode == InvoiceOcrMode.EVOLEO_AI) {
                return ResponseEntity.ok(miniCompatibilityScanService.scanSalesAlpha(file));
            }
            return ResponseEntity.ok(miniCompatibilityScanService.scanSales(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Legacy /vente failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "type", e.getClass().getSimpleName()));
        }
    }

    @PostMapping("/vente/extract-field")
    public ResponseEntity<?> extractVenteField(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fieldName") String fieldName) {
        try {
            return ResponseEntity.ok(miniCompatibilityScanService.extractSalesField(file, fieldName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Legacy /vente/extract-field failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "type", e.getClass().getSimpleName()));
        }
    }
}
