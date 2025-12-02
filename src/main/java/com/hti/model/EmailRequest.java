package com.hti.model;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class EmailRequest {
	@Schema(description = "Batch Id not required for new batch submission", example = "2511261601534601001")
	private String batchId;

	@Schema(description = "SMTP Server Id", example = "1")
	private int smtpId;

	@Schema(description = "Email subject", example = "Meeting Update")
	private String subject;

	@Schema(description = "Email body content", example = "Please find the details attached")
	private String body;

	@Schema(description = "JSON array string of recipients", example = "[\"a@gmail.com\",\"b@yahoo.com\"]")
	private String recipients;

	@Schema(description = "JSON array string of CC recipients list", example = "[\"a@gmail.com\",\"b@yahoo.com\"]")
	private String ccRecipients;

	@Schema(description = "JSON array string of BCC recipients list", example = "[\"a@gmail.com\",\"b@yahoo.com\"]")
	private String bccRecipients;
	
	@Schema(description = "Attachments (binary files)", type = "array", implementation = String.class, format = "binary")
	private List<MultipartFile> attachmentList;

	@Schema(description = "Delay between mails in seconds", example = "1.5")
	private double delay;

}
