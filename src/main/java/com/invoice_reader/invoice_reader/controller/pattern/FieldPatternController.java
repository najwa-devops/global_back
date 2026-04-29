package com.invoice_reader.invoice_reader.controller.pattern;

import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.entity.dynamic.FieldPattern;
import com.invoice_reader.invoice_reader.servises.patterns.FieldPatternService;
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
