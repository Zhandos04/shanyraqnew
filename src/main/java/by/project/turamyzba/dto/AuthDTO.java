package by.project.turamyzba.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthDTO {
    private String accessToken;
    private String refreshToken;
}