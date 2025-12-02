package com.hti.model;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class SandBoxEmailRequest {

	@Schema(description = "SMTP Server Id", example = "1")
	private int smtpId;

	@Schema(description = "Email subject", example = "Meeting Update")
	private String subject;

	@Schema(description = "Email body content", example = "Please find the details attached")
	private String body;

	@Schema(description = "recipient email addresss", example = "test@yahoo.com")
	private String recipient;

	@Schema(description = "JSON array string of CC recipients list", example = "[\"a@gmail.com\",\"b@yahoo.com\"]")
	private String ccRecipients;

	@Schema(description = "JSON array string of BCC recipients list", example = "[\"a@gmail.com\",\"b@yahoo.com\"]")
	private String bccRecipients;
	
	@Schema(description = "Attachments (binary files)", type = "array", implementation = String.class, format = "binary")
	private List<MultipartFile> attachmentList;
}
