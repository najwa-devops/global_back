package com.invoice_reader.invoice_reader.dto.auth;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserActiveRequest {
    @NotNull
    private Boolean active;
}
