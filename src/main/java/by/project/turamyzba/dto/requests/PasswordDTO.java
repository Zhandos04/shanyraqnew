package by.project.turamyzba.dto.requests;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PasswordDTO {
    private String oldPassword;
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&_#])(?=.*[a-z])[A-Za-z\\d@$!%*?&_#]{8,}$",
            message = "Password должен содержать как минимум одну заглавную букву, один чисел, и один символ. И должен быть длиной как минимум 8.")
    private String newPassword;
}