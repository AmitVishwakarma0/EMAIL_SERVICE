package com.hti.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.hti.database.service.DBService;
import com.hti.entity.SmtpEntry;
import com.hti.exception.InvalidRequestException;
import com.hti.exception.ProcessingException;
import com.hti.model.SandBoxEmailRequest;
import com.hti.util.EmailStatus;
import com.hti.util.GlobalVar;
import com.sun.mail.smtp.SMTPTransport;

@Service
public class SandboxServiceimpl implements SandboxService {

	private Logger logger = LoggerFactory.getLogger(SandboxServiceimpl.class);

	@Override
	public String sendEmail(SandBoxEmailRequest request, String ipAddress, String systemId) {

		logger.info(systemId + "[" + ipAddress + "] Sandbox Request: " + request.toString());
		Map<Integer, SmtpEntry> inner = GlobalVar.SmtpEntries.get(systemId);
		if (inner == null) {
			throw new InvalidRequestException("SMTP configuration missing for systemId: " + systemId);
		}
		SmtpEntry smtpEntry = inner.get(request.getSmtpId());
		if (smtpEntry == null) {
			throw new InvalidRequestException("SMTP configuration missing for smtpId: " + request.getSmtpId());
		}
		String msgId = GlobalVar.assignMessageId();
		Session mailSession = Session.getInstance(getSmtpProperties(smtpEntry), new javax.mail.Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(smtpEntry.getEmailUser(), smtpEntry.getEmailPassword());
			}
		});

		SMTPTransport transport = null;
		EmailStatus status = EmailStatus.PENDING;
		String response = null;
		logger.info(systemId + " Trying To Connect [" + smtpEntry.getEmailUser() + "] @" + smtpEntry.getHost() + ":"
				+ smtpEntry.getPort());
		try {
			transport = (SMTPTransport) mailSession.getTransport("smtp");
			transport.connect();
			logger.info(systemId + " Connected [" + smtpEntry.getEmailUser() + "] @" + smtpEntry.getHost() + ":"
					+ smtpEntry.getPort());
			Message message = prepareMessage(mailSession, request, smtpEntry, msgId);

			logger.info("[{}] Sending Email To: {}", msgId, request.getRecipient());
			transport.sendMessage(message, message.getAllRecipients());

			int statusCode = transport.getLastReturnCode();
			response = transport.getLastServerResponse();

			logger.info("[{}] SMTP Response: {} {}", msgId, statusCode, response);

			status = classifyResponse(statusCode, response);

		} catch (SendFailedException e) {
			status = EmailStatus.FAILED;
			logger.error("[{}] Send failed for recipient {}", msgId, request.getRecipient(), e);
			throw new ProcessingException(e.getLocalizedMessage());

		} catch (MessagingException e) {
			status = EmailStatus.ERROR;
			logger.error("[{}] Messaging exception {}", msgId, request.getRecipient(), e);
			throw new ProcessingException(e.getLocalizedMessage());

		} catch (Exception e) {
			status = EmailStatus.ERROR;
			logger.error("[{}] Unexpected exception {}", msgId, request.getRecipient(), e);
			throw new ProcessingException(e.getLocalizedMessage());

		} finally {
			if (transport != null) {
				try {
					transport.close();
				} catch (MessagingException ignore) {
				}
			}
		}
		if (status == EmailStatus.DELIVERED) {
			GlobalVar.eventService.setSmtpVerified(systemId, request.getSmtpId());
		}

		return status.name();
	}

	private Message prepareMessage(Session session, SandBoxEmailRequest request, SmtpEntry smtpEntry, String msgId)
			throws Exception {

		Message message = new MimeMessage(session);
		message.setFrom(new InternetAddress(smtpEntry.getEmailUser()));

		message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(request.getRecipient()));

		setRecipientGroup(message, Message.RecipientType.CC, request.getCcRecipients(), msgId);
		setRecipientGroup(message, Message.RecipientType.BCC, request.getBccRecipients(), msgId);

		message.setSubject(request.getSubject());

		Multipart multipart = new MimeMultipart();

// body
		MimeBodyPart textPart = new MimeBodyPart();
		textPart.setText(request.getBody(), "utf-8");
		multipart.addBodyPart(textPart);

// attachments
		if (request.getAttachmentList() != null) {
			for (MultipartFile file : request.getAttachmentList()) {
				if (!file.isEmpty()) {
					multipart.addBodyPart(createAttachmentPart(file, msgId));
				}
			}
		}

		message.setContent(multipart);
		return message;
	}

	private Properties getSmtpProperties(SmtpEntry smtpEntry) {
		Properties props = new Properties();
		props.put("mail.smtp.host", smtpEntry.getHost());
		props.put("mail.smtp.port", smtpEntry.getPort());
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.auth", "true");
		props.put("mail.debug", "true");
		switch (smtpEntry.getEncryptionType()) {
		case STARTTLS -> props.put("mail.smtp.starttls.enable", "true");
		case SSL -> {
			props.put("mail.smtp.ssl.enable", "true");
			props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		}
		case NONE -> {
			props.put("mail.smtp.starttls.enable", "false");
			props.put("mail.smtp.ssl.enable", "false");
		}
		}
		return props;
	}

	private void setRecipientGroup(Message msg, Message.RecipientType type, String jsonRecipients, String msgId)
			throws MessagingException {
		if (jsonRecipients == null || jsonRecipients.isBlank())
			return;

		Set<String> emails = listRecipients(msgId, jsonRecipients);
		if (!emails.isEmpty()) {
			InternetAddress[] arr = emails.stream().map(e -> {
				try {
					return new InternetAddress(e);
				} catch (Exception ex) {
					return null;
				}
			}).filter(Objects::nonNull).toArray(InternetAddress[]::new);

			msg.setRecipients(type, arr);
		}
	}

	private Set<String> listRecipients(String msgId, String jsonRecipients) {
		Set<String> result = new HashSet<>();
		if (jsonRecipients == null || jsonRecipients.isBlank()) {
			return result; // empty set
		}
		try {
			JSONArray ccArray = new JSONArray(jsonRecipients);
			for (int i = 0; i < ccArray.length(); i++) {
				String cc = ccArray.getString(i).trim();
				// validate cc email
				if (isValidEmail(cc)) {
					result.add(cc);
				} else {
					logger.info(msgId + "Invalid Recipients Found: " + cc);
				}
			}

		} catch (Exception e) {
			logger.error("[{}] Error parsing recipient list: {}", msgId, jsonRecipients, e);
		}
		return result;
	}

	private boolean isValidEmail(String email) {
		try {
			InternetAddress address = new InternetAddress(email);
			address.validate(); // throws exception if invalid
			return true;
		} catch (AddressException e) {
			return false;
		}
	}

	private MimeBodyPart createAttachmentPart(MultipartFile file, String msgId) throws Exception {

		MimeBodyPart attachment = new MimeBodyPart();

		DataSource source = new ByteArrayDataSource(file.getBytes(),
				file.getContentType() != null ? file.getContentType() : "application/octet-stream");

		attachment.setDataHandler(new DataHandler(source));
		attachment.setFileName(file.getOriginalFilename());

		logger.info("[{}] Attached file: {}", msgId, file.getOriginalFilename());

		return attachment;
	}

	public EmailStatus classifyResponse(int code, String response) {
		String lower = response.toLowerCase();

		if (lower.contains("limit") || lower.contains("rate") || lower.contains("too many")) {
			return EmailStatus.BLOCKED;
		}
		if (lower.contains("auth") || lower.contains("login")) {
			return EmailStatus.AUTH_ERROR;
		}
		if (lower.contains("reject") || lower.contains("denied") || lower.contains("spam")) {
			return EmailStatus.REJECTED;
		}

		if (code >= 200 && code < 300)
			return EmailStatus.DELIVERED;
		if (code >= 300 && code < 400)
			return EmailStatus.PENDING; // <-- 3xx are intermediate responses
		if (code >= 400 && code < 500)
			return EmailStatus.TEMP_FAILURE;
		if (code >= 500 && code < 600)
			return EmailStatus.FAILED;

		return EmailStatus.UNKNOWN;
	}

}
