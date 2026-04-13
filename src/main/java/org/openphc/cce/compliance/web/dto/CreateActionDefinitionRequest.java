package org.openphc.cce.compliance.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateActionDefinitionRequest {

    @NotBlank(message = "definitionJson must not be blank")
    private String definitionJson;
}
