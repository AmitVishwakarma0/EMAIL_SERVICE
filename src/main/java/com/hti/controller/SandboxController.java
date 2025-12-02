/**
 * TelegramSandboxController.java
 *
 * @author  Vinod Yogi
 * @since   2025-09-12
 * @version 1.1
 *
 * Description:
 * This controller handles all sandbox-based Telegram messaging operations —
 * including connection management and message/media dispatching for testing or
 * simulation environments.
 */

package com.hti.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hti.model.EmailRequest;
import com.hti.model.SandBoxEmailRequest;
import com.hti.service.SandboxService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@Tag(name = "Email Sandbox API", description = "Endpoints to connect, disconnect, and send Email messages via Email Sandbox mode.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/email-sandbox")
public class SandboxController {

	private final SandboxService sandboxService;

	/**
	 * Sends a text or media message using the sandbox Telegram client.
	 *
	 * @param sandBoxEmailRequest The unified message request payload.
	 * @param ipAddress    IP address of the client making the request.
	 * @return ResponseEntity containing the Status.
	 */
	@Operation(summary = "Send Email Messages (Sandbox)", description = """
			Sends a Email message via the sandbox email client.
			""")
	@PostMapping(value = "/sendTestEmail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = SandBoxEmailRequest.class)))
	public ResponseEntity<?> sendSandboxMessage(
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader(required = true) @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Username of the requester or application user", example = "super", required = true) @RequestHeader(required = true) @NotBlank(message = "Username header is required") String username,
			@Valid @ModelAttribute SandBoxEmailRequest sandBoxEmailRequest) {
		String status = sandboxService.sendEmail(sandBoxEmailRequest, ipAddress, username);
		return ResponseEntity.ok(Map.of("status", status));
	}
}
