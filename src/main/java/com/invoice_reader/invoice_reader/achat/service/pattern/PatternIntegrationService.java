package com.invoice_reader.invoice_reader.achat.service.pattern;

import com.invoice_reader.invoice_reader.achat.dto.CreateAchatTemplateRequest;
import com.invoice_reader.invoice_reader.achat.entity.AchatInvoice;
import com.invoice_reader.invoice_reader.achat.entity.AchatTemplate;
import com.invoice_reader.invoice_reader.achat.service.pattern.RegexResult;
import com.invoice_reader.invoice_reader.achat.dao.AchatInvoiceDao;
import com.invoice_reader.invoice_reader.achat.dao.AchatTemplateDao;
import com.invoice_reader.invoice_reader.achat.service.AchatTemplateService;
import com.invoice_reader.invoice_reader.achat.service.FieldLearningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PatternIntegrationService {

    private final AchatInvoiceDao dynamicInvoiceDao;
    private final AchatTemplateService templateService;
    private final AchatTemplateDao dynamicTemplateDao;
    private final FieldLearningService fieldLearningService;
    private final RegexEnrichmentService regexEnrichmentService;

    @Transactional
    public Map<String, Object> saveFieldsAndIntegratePatterns(
            Long invoiceId,
            Map<String, Object> fieldsData,
            Map<String, String> newPatterns
    ) {
        log.info("=== SAVE + IMMEDIATE PATTERN INTEGRATION ===");
        log.info("Invoice ID: {}", invoiceId);

        AchatInvoice invoice = dynamicInvoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        invoice.setFieldsData(fieldsData);
        dynamicInvoiceDao.save(invoice);
        log.info("fieldsData updated in AchatInvoice");

        fieldLearningService.savePatterns(invoiceId, newPatterns);
        log.info("Patterns saved in FieldLearningData");

        Map<String, RegexResult> learnedRegex = regexEnrichmentService.generateAndStoreRegex(
                invoice,
                fieldsData,
                newPatterns != null ? newPatterns.keySet() : List.of()
        );
        log.info("Learned regex fields: {}", learnedRegex.keySet());

        Long templateId = invoice.getTemplateId();
        if (templateId == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Fields saved, patterns saved (no template to update)");
            response.put("templateUpdated", null);
            response.put("patternsCount", newPatterns != null ? newPatterns.size() : 0);
            response.put("savedPatterns", newPatterns != null ? newPatterns.keySet() : List.of());
            response.put("learnedRegexFields", learnedRegex.keySet());
            return response;
        }

        AchatTemplate template = dynamicTemplateDao.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        int addedCount = templateService.addPatternsToTemplate(templateId, newPatterns);
        int regexAppliedCount = applyLearnedRegexToTemplate(template, learnedRegex);
        updateTemplateFixedData(template, fieldsData);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Fields and patterns saved successfully");
        response.put("templateUpdated", true);
        response.put("templateId", templateId);
        response.put("patternsAdded", addedCount);
        response.put("savedPatterns", newPatterns != null ? newPatterns.keySet() : List.of());
        response.put("learnedRegexFields", learnedRegex.keySet());
        response.put("regexAppliedToTemplate", regexAppliedCount);
        return response;
    }

    @Transactional
    public void integratePatternsintoTemplate(Long templateId, Map<String, String> fieldPatterns) {
        if (fieldPatterns == null || fieldPatterns.isEmpty()) {
            log.info("No patterns to integrate");
            return;
        }

        AchatTemplate template = templateService.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        boolean modified = false;

        for (Map.Entry<String, String> entry : fieldPatterns.entrySet()) {
            String fieldName = entry.getKey();
            String pattern = entry.getValue();

            if (pattern == null || pattern.isBlank()) {
                continue;
            }

            AchatTemplate.AchatFieldDefinitionJson existingField = template.getFieldDefinition(fieldName);

            if (existingField != null) {
                List<String> labels = new ArrayList<>(existingField.getLabels() != null
                        ? existingField.getLabels()
                        : new ArrayList<>());

                if (!labels.contains(pattern)) {
                    labels.add(pattern);
                    existingField.setLabels(labels);
                    modified = true;
                }
            } else {
                AchatTemplate.AchatFieldDefinitionJson newField =
                        AchatTemplate.AchatFieldDefinitionJson.builder()
                                .fieldName(fieldName)
                                .labels(List.of(pattern))
                                .regexPattern(null)
                                .fieldType("TEXT")
                                .detectionMethod("LABEL_BASED")
                                .required(false)
                                .confidenceThreshold(0.7)
                                .searchZone("ALL")
                                .extractionOrder(100)
                                .description("Added by user")
                                .build();

                List<AchatTemplate.AchatFieldDefinitionJson> fieldDefs =
                        new ArrayList<>(template.getFieldDefinitions());
                fieldDefs.add(newField);
                template.setFieldDefinitions(fieldDefs);
                modified = true;
            }
        }

        if (modified) {
            templateService.patchTemplate(templateId, createUpdateRequestFromTemplate(template));
        }
    }

    private com.invoice_reader.invoice_reader.achat.dto.UpdateAchatTemplateRequest
    createUpdateRequestFromTemplate(AchatTemplate template) {

        return com.invoice_reader.invoice_reader.achat.dto.UpdateAchatTemplateRequest.builder()
                .templateName(template.getTemplateName())
                .supplierType(template.getSupplierType())
                .description(template.getDescription())
                .fieldDefinitions(convertFieldDefinitions(template.getFieldDefinitions()))
                .build();
    }

    private List<CreateAchatTemplateRequest.FieldDefinitionRequest>
    convertFieldDefinitions(List<AchatTemplate.AchatFieldDefinitionJson> fields) {

        if (fields == null) return new ArrayList<>();

        return fields.stream()
                .map(f -> com.invoice_reader.invoice_reader.achat.dto.CreateAchatTemplateRequest
                        .FieldDefinitionRequest.builder()
                        .fieldName(f.getFieldName())
                        .labels(f.getLabels())
                        .regexPattern(f.getRegexPattern())
                        .fieldType(f.getFieldType())
                        .detectionMethod(f.getDetectionMethod())
                        .required(f.getRequired())
                        .confidenceThreshold(f.getConfidenceThreshold())
                        .defaultValue(f.getDefaultValue())
                        .searchZone(f.getSearchZone())
                        .extractionOrder(f.getExtractionOrder())
                        .description(f.getDescription())
                        .build())
                .toList();
    }

    private void updateTemplateFixedData(AchatTemplate template, Map<String, Object> fieldsData) {
        AchatTemplate.FixedSupplierData fixedData = template.getFixedSupplierData();

        if (fixedData == null) {
            fixedData = new AchatTemplate.FixedSupplierData();
        }

        boolean updated = false;

        String ice = getStringValue(fieldsData, "ice");
        if (ice != null && !ice.isBlank() && !ice.equals(fixedData.getIce())) {
            fixedData.setIce(ice);
            updated = true;
        }

        String ifNumber = getStringValue(fieldsData, "ifNumber");
        if (ifNumber != null && !ifNumber.isBlank() && !ifNumber.equals(fixedData.getIfNumber())) {
            fixedData.setIfNumber(ifNumber);
            updated = true;
        }

        String rcNumber = getStringValue(fieldsData, "rcNumber");
        if (rcNumber != null && !rcNumber.isBlank() && !rcNumber.equals(fixedData.getRcNumber())) {
            fixedData.setRcNumber(rcNumber);
            updated = true;
        }

        String supplier = getStringValue(fieldsData, "supplier");
        if (supplier != null && !supplier.isBlank() && !supplier.equals(fixedData.getSupplier())) {
            fixedData.setSupplier(supplier);
            updated = true;
        }

        if (updated) {
            template.setFixedSupplierData(fixedData);
            dynamicTemplateDao.save(template);
        }
    }

    private int applyLearnedRegexToTemplate(AchatTemplate template, Map<String, RegexResult> learnedRegex) {
        if (template == null || learnedRegex == null || learnedRegex.isEmpty()) {
            return 0;
        }
        if (template.getFieldDefinitions() == null || template.getFieldDefinitions().isEmpty()) {
            return 0;
        }

        int updated = 0;
        for (AchatTemplate.AchatFieldDefinitionJson definition : template.getFieldDefinitions()) {
            RegexResult result = learnedRegex.get(definition.getFieldName());
            if (result == null || result.regex() == null || result.regex().isBlank()) {
                continue;
            }
            if (definition.getRegexPattern() == null || definition.getRegexPattern().isBlank()) {
                definition.setRegexPattern(result.regex());
                updated++;
            }
        }

        if (updated > 0) {
            dynamicTemplateDao.save(template);
        }
        return updated;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return value.toString().trim();
    }
}
