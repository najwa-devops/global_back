package com.invoice_reader.invoice_reader.achat.controller;

import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.achat.entity.FieldPattern;
import com.invoice_reader.invoice_reader.achat.service.pattern.FieldPatternService;
import com.invoice_reader.invoice_reader.security.RequireRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/field-patterns")
@CrossOrigin("*")
@RequiredArgsConstructor
@RequireRole({UserRole.ADMIN})
public class FieldPatternController {

    private final FieldPatternService fieldPatternService;

    @GetMapping
    public List<FieldPattern> getAllPatterns() {
        return fieldPatternService.getAllActivePatterns();
    }

    @PostMapping
    public FieldPattern addPattern(@RequestBody FieldPattern pattern) {
        return fieldPatternService.addPattern(pattern);
    }

    @PutMapping("/{id}")
    public FieldPattern updatePattern(@PathVariable Long id, @RequestBody FieldPattern pattern) {
        return fieldPatternService.updatePattern(id, pattern);
    }

    @DeleteMapping("/{id}")
    public void deletePattern(@PathVariable Long id) {
        fieldPatternService.deletePattern(id);
    }
}
