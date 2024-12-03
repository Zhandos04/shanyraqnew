package by.project.turamyzba.controllers;

import by.project.turamyzba.config.CustomAuthenticationProvider;
import by.project.turamyzba.dto.requests.*;
import by.project.turamyzba.dto.responses.AuthDTO;
import by.project.turamyzba.jwt.JwtService;
import by.project.turamyzba.entities.User;
import by.project.turamyzba.services.EmailService;
import by.project.turamyzba.services.TokenBlacklistService;
import by.project.turamyzba.services.UserService;
import by.project.turamyzba.exceptions.UserAlreadyExistsException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/auth")
@Tag(name="Auth", description="Взаймодействие с пользователями")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final JwtService jwtService;
    private final CustomAuthenticationProvider authenticationProvider;
    private final ModelMapper modelMapper;
    private final EmailService emailService;
    private final TokenBlacklistService tokenBlacklistService;


    @PostMapping( "/signup")
    @Operation(summary = "Register a new user", description = "Registers a new user by checking for existing IDs and phone numbers.")
    @ApiResponse(responseCode = "202", description = "Code sent successfully!")
    @ApiResponse(responseCode = "400", description = "Invalid user data provided", content = @Content)
    public ResponseEntity<String> register(@RequestBody @Valid UserDTO userDTO, BindingResult bindingResult) throws UserAlreadyExistsException {
        if (bindingResult.hasErrors()) {
            String errorMessages = bindingResult.getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessages);
        }
        userService.registerNewUser(userDTO);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("Code sent successfully!");
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Verify Email", description = "Verifies the reset code entered by the user.")
    @ApiResponse(responseCode = "200", description = "User registered successfully")
    @ApiResponse(responseCode = "400", description = "Incorrect reset code")
    @ApiResponse(responseCode = "404", description = "User not found")
    public ResponseEntity<?> verifyEmail(@RequestBody CodeDTO codeDTO) {
        Optional<User> userOptional = userService.getUserByEmail(codeDTO.getEmail());

        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        User user = userOptional.get();
        if (!user.getConfirmationCode().equals(codeDTO.getCode())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Incorrect Code");
        }

        user.setIsVerified(true);
        userService.update(user);
        return ResponseEntity.status(HttpStatus.CREATED).body("User registered successfully!");
    }

    @PostMapping("/resendCode")
    @Operation(summary = "Resend code")
    public ResponseEntity<?> resendCode(@RequestBody ResentCodeDTO resentCodeDTO) {
        userService.resentCode(resentCodeDTO.getEmail());
        return ResponseEntity.ok("Code resent successfully!");
    }


    @PostMapping("/login")
    @Operation(summary = "User login", description = "Authenticates a user and returns an Auth token.")
    @ApiResponse(responseCode = "200", description = "User logged in successfully", content = @Content(schema = @Schema(implementation = AuthDTO.class)))
    @ApiResponse(responseCode = "401", description = "Incorrect ID or password")
    public ResponseEntity<?> login(@RequestBody LoginDTO loginDTO) {
        Optional<User> userOptional = userService.getUserByEmail(loginDTO.getEmail());
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Incorrect email or password");
        }
        authenticationProvider.authenticate(new UsernamePasswordAuthenticationToken(userOptional.get().getEmail(), loginDTO.getPassword()));
        if (!userOptional.get().getIsVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This user is not verified yet");
        }
        Map<String, String> tokens = jwtService.generateTokens(loginDTO.getEmail());

        AuthDTO authDTO = modelMapper.map(userOptional.get(), AuthDTO.class);
        authDTO.setAccessToken(tokens.get("accessToken"));
        return ResponseEntity.ok(authDTO);
    }
    @PostMapping("/logout")
    @Operation(summary = "User logout.")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            // Извлекаем дату истечения токена
            Date expirationTime = jwtService.extractExpiration(token);

            // Добавляем токен в черный список
            tokenBlacklistService.addTokenToBlacklist(token, expirationTime);

            return ResponseEntity.ok("Logged out successfully");
        }
        return ResponseEntity.badRequest().body("Invalid token");
    }
    @PostMapping("/refresh-token")
    @Operation(summary = "Refresh Access Token", description = "Refreshes the access token using a valid refresh token.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Access token refreshed successfully"),
                    @ApiResponse(responseCode = "403", description = "Invalid or expired refresh token"),
                    @ApiResponse(responseCode = "401", description = "Invalid refresh token")
            },
            security = @SecurityRequirement(name = "bearerToken"))

    public ResponseEntity<?> refreshAccessToken(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (headerAuth != null && headerAuth.startsWith("Bearer ")) {
            String refreshToken = headerAuth.substring(7);
            try {
                if (jwtService.validateRefreshToken(refreshToken)) { // Проверка валидности рефреш токена
                    String userName = jwtService.extractUsername(refreshToken);
                    // Проверка, что пользователь существует и активен
                    UserDetails userDetails = userService.loadUserByUsername(userName);
                    if (userDetails != null && !jwtService.isTokenExpired(refreshToken)) {
                        String newAccessToken = jwtService.generateTokens(userName).get("accessToken");
                        Map<String, String> tokens = new HashMap<>();
                        tokens.put("accessToken", newAccessToken);
                        tokens.put("refreshToken", refreshToken); // Отправляем тот же рефреш токен обратно
                        return ResponseEntity.ok(tokens);
                    }
                }
            } catch (Exception e) {
                throw new BadCredentialsException("Invalid refresh token");
            }
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid or expired refresh token");
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Password recovery", description = "Initiates a password recovery process by sending a reset code to the user's email.")
    @ApiResponse(responseCode = "200", description = "Reset code sent successfully")
    @ApiResponse(responseCode = "404", description = "Email not found")
    public ResponseEntity<?> forgotPassword(@RequestBody EmailDTO emailDTO) {
        Optional<User> user = userService.getUserByEmail(emailDTO.getEmail());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Email not found");
        }
        if(!user.get().getIsVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This user is not verified yet");
        }

        String code = generateCode();
        userService.saveUserConfirmationCode(user.get().getId(), code);

        emailService.sendEmail(emailDTO.getEmail(), "Shanyraq Reset Password", "Your code is: " + code);

        return ResponseEntity.ok("Reset password instructions have been sent to your email.");
    }
    @PostMapping("/verify-code")
    @Operation(summary = "Verify reset code", description = "Verifies the reset code entered by the user.")
    @ApiResponse(responseCode = "200", description = "Reset code verified successfully")
    @ApiResponse(responseCode = "401", description = "Incorrect reset code")
    public ResponseEntity<?> verifyPassword(@RequestBody CodeDTO codeDTO) {
        Optional<User> user = userService.getUserByEmail(codeDTO.getEmail());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Email not found");
        }
        if(!user.get().getConfirmationCode().equals(codeDTO.getCode())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Incorrect Code");
        }
        return ResponseEntity.ok("Code is verified!");
    }

    @PostMapping("/update-password")
    @Operation(summary = "Update user password", description = "Updates the user's password after verification.")
    @ApiResponse(responseCode = "200", description = "Password updated successfully")
    public ResponseEntity<?> updatePassword(@RequestBody @Valid UpdatePasswordDTO updatePasswordDTO, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            String errorMessages = bindingResult.getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessages);
        }
        Optional<User> user = userService.getUserByEmail(updatePasswordDTO.getEmail());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Email not found");
        }
        user.get().setPassword(updatePasswordDTO.getPassword());
        userService.updatePassword(user.get());
        return ResponseEntity.ok("Password is updated!");
    }
    private String generateCode() {
        return Integer.toString((int)(Math.random() * 900000) + 100000);
    }
}