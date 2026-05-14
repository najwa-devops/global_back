package com.invoice_reader.invoice_reader.achat.controller;

import com.invoice_reader.invoice_reader.achat.controller.AchatInvoiceController;
import com.invoice_reader.invoice_reader.vente.controller.VenteInvoiceController;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LegacyAchatVenteCompatibilityController {

    private final AchatInvoiceController dynamicInvoiceController;
    private final VenteInvoiceController salesInvoiceController;

    // =========================
    // ACHAT (legacy mini paths)
    // =========================

    @GetMapping("/api/achat/invoices")
    public ResponseEntity<?> listAchatInvoices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long templateId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        return dynamicInvoiceController.list(status, templateId, dossierId, limit, session);
    }

    @GetMapping("/api/achat/invoices/{id}")
    public ResponseEntity<?> getAchatInvoiceById(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        return dynamicInvoiceController.getById(id, dossierId, session);
    }

    @PostMapping({"/api/achat/invoices", "/api/achat/invoices/upload"})
    public ResponseEntity<?> uploadAchatInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent,
            HttpSession session) {
        return dynamicInvoiceController.uploadAndProcess(file, dossierId, engine, ocrMode, useAlphaAgent, session);
    }

    @PostMapping("/api/achat/invoices/upload/batch")
    public ResponseEntity<?> uploadAchatInvoicesBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent,
            HttpSession session) {
        return dynamicInvoiceController.uploadAndProcessBatch(files, dossierId, engine, ocrMode, useAlphaAgent, session);
    }

    // =========================
    // VENTE (legacy mini paths)
    // =========================

    @GetMapping("/api/vente/invoices")
    public ResponseEntity<?> listVenteInvoices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long templateId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        return salesInvoiceController.list(status, templateId, dossierId, limit, session);
    }

    @GetMapping("/api/vente/invoices/{id}")
    public ResponseEntity<?> getVenteInvoiceById(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        return salesInvoiceController.getById(id, dossierId, session);
    }

    @PostMapping({"/api/vente/invoices", "/api/vente/invoices/upload"})
    public ResponseEntity<?> uploadVenteInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent,
            HttpSession session) {
        return salesInvoiceController.uploadAndProcess(file, dossierId, engine, ocrMode, useAlphaAgent, session);
    }

    @PostMapping("/api/vente/invoices/upload/batch")
    public ResponseEntity<?> uploadVenteInvoicesBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent,
            HttpSession session) {
        return salesInvoiceController.uploadAndProcessBatch(files, dossierId, engine, ocrMode, useAlphaAgent, session);
    }
}
