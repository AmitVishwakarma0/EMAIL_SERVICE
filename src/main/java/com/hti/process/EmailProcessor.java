package com.hti.process;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

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

import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hti.database.service.DBService;
import com.hti.database.service.RecipientEntryService;
import com.hti.database.service.ReportService;
import com.hti.entity.EmailEntry;
import com.hti.entity.EmailEntry.BatchStatus;
import com.hti.exception.InvalidRequestException;
import com.hti.exception.ProcessingException;
import com.hti.model.DeliverResponse;
import com.hti.service.SingletonService;
import com.hti.entity.RecipientsEntry;
import com.hti.entity.ReportEntry;
import com.hti.entity.SmtpEntry;
import com.hti.util.EmailStatus;
import com.hti.util.GlobalVar;
import com.hti.util.Queue;
import com.sun.mail.smtp.SMTPTransport;

public class EmailProcessor implements Runnable {

	private String batchId;
	private EmailEntry entry;
	private Logger logger = LoggerFactory.getLogger(EmailProcessor.class);
	private DBService service;
	private Properties smtpProps;
	private boolean stop;
	private List<RecipientsEntry> pendingRecipients;
	private String systemId;
	private ReportService reportService;
	private Queue updateQueue;
	private RecipientEntryService recipientEntryService;
	private SmtpEntry smtpEntry;
	private boolean reconnect;
	private Set<File> attachments;
	private Set<String> ccRecipients;
	private Set<String> bccRecipients;
	private DlrForwarder dlrForwarder;

	public EmailProcessor(EmailEntry entry) throws InvalidRequestException {
		logger.info(entry.getBatchId() + " Batch Initializing For " + entry.getSystemId() + " Total Recipients: "
				+ entry.getTotalRecipients() + " Pending: " + entry.getPendingRecipientList().size());
		this.systemId = entry.getSystemId();
		this.batchId = entry.getBatchId();
		this.pendingRecipients = entry.getPendingRecipientList();
		this.entry = entry;
		this.service = new DBService();
		loadSmtpConfiguration();
		this.updateQueue = new Queue();
		this.recipientEntryService = new RecipientEntryService(systemId, batchId, updateQueue);
		this.reportService = SingletonService.getUserReportService(systemId);
		this.dlrForwarder = SingletonService.getUserDlrForwarder(systemId);
		new Thread(this, "Batch_" + systemId + "_" + batchId).start();
	}

	private void loadSmtpConfiguration() throws InvalidRequestException {
		if (GlobalVar.SmtpEntries.containsKey(systemId)) {
			this.smtpEntry = GlobalVar.SmtpEntries.get(systemId).get(entry.getSmtpId());
		}
		if (smtpEntry == null) {
			throw new InvalidRequestException("Smtp Configuration missing!!");
		}
		if (!smtpEntry.isVerified()) {
			throw new InvalidRequestException("Smtp Configuration Not Verified!!");
		}
		this.smtpProps = new Properties();
		smtpProps.put("mail.smtp.host", smtpEntry.getHost());
		smtpProps.put("mail.transport.protocol", "smtp");
		smtpProps.put("mail.smtp.port", smtpEntry.getPort());
		smtpProps.put("mail.smtp.auth", "true");
		switch (smtpEntry.getEncryptionType()) {
		case STARTTLS -> smtpProps.put("mail.smtp.starttls.enable", "true");
		case SSL -> {
			smtpProps.put("mail.smtp.ssl.enable", "true");
			smtpProps.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		}
		case NONE -> {
			smtpProps.put("mail.smtp.starttls.enable", "false");
			smtpProps.put("mail.smtp.ssl.enable", "false");
		}
		}
	}

	@Override
	public void run() {
		listAttachments();
		listCcRecipients();
		listBccRecipients();
		while (!stop) {
			try {
				logger.info(systemId + "_" + batchId + " Trying to Connect SMTP Server <" + smtpEntry.getHost() + " "
						+ smtpEntry.getPort() + ">");
				Session mailSession = Session.getInstance(smtpProps, new javax.mail.Authenticator() {
					@Override
					protected PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(smtpEntry.getEmailUser(), smtpEntry.getEmailPassword());
					}
				});
				if (reconnect) {
					reconnect = false;
				}
				try (SMTPTransport transport = (SMTPTransport) mailSession.getTransport("smtp")) {
					transport.connect(); // connect once
					java.util.Iterator<RecipientsEntry> itr = pendingRecipients.iterator();
					while (itr.hasNext()) {
						RecipientsEntry recipientsEntry = itr.next();
						EmailStatus status = EmailStatus.PENDING;
						int statusCode = 400;
						String response = null;
						try {
							Message message = new MimeMessage(mailSession);
							message.setFrom(new InternetAddress(smtpEntry.getEmailUser()));
							message.setRecipients(Message.RecipientType.TO,
									InternetAddress.parse(recipientsEntry.getRecipient()));
							if (!ccRecipients.isEmpty()) {
								InternetAddress[] ccaddress = new InternetAddress[ccRecipients.size()];
								int i = 0;
								for (String cc : ccRecipients) {
									ccaddress[i] = new InternetAddress(cc);
									i++;
								}
								message.setRecipients(Message.RecipientType.CC, ccaddress);
							}
							if (!bccRecipients.isEmpty()) {
								InternetAddress[] bccaddress = new InternetAddress[bccRecipients.size()];
								int i = 0;
								for (String bcc : bccRecipients) {
									bccaddress[i] = new InternetAddress(bcc);
									i++;
								}
								message.setRecipients(Message.RecipientType.BCC, bccaddress);
							}
							message.setSubject(entry.getSubject());
							message.setText(entry.getBody());
							Multipart multipart = new MimeMultipart();

							MimeBodyPart textBodyPart = new MimeBodyPart();
							textBodyPart.setText(entry.getBody(), "utf-8");
							multipart.addBodyPart(textBodyPart);

							for (File file : attachments) {
								MimeBodyPart attachmentPart = new MimeBodyPart();
								try {
									attachmentPart.attachFile(file);
									multipart.addBodyPart(attachmentPart);
									logger.info(batchId + ": attached file " + file.getName());
								} catch (IOException e) {
									logger.error(batchId + ": attachement file Error " + file.getName(),
											e.getMessage());
								}

							}
							message.setContent(multipart);
							logger.info(batchId + ": Sending Email To: " + recipientsEntry.getRecipient());
							transport.sendMessage(message, message.getAllRecipients());
							statusCode = transport.getLastReturnCode();
							response = transport.getLastServerResponse();
							System.out.println(batchId + " [" + recipientsEntry.getRecipient() + "]"
									+ " SMTP Response Code: " + statusCode + " Text:" + response);
							status = classifyResponse(statusCode, response);
							recipientsEntry.setFlag("T");
							// tracker.setStatus(..., DELIVERED);
						} catch (SendFailedException e) {
							status = EmailStatus.FAILED;
							response = e.getMessage();
							recipientsEntry.setFlag("E");
							logger.error(systemId + "[" + batchId + "][" + recipientsEntry.getRecipient() + "]", e);
						} catch (MessagingException e) {
							status = EmailStatus.ERROR;
							response = e.getMessage();
							recipientsEntry.setFlag("E");
							logger.error(systemId + "[" + batchId + "][" + recipientsEntry.getRecipient() + "]", e);
						}
						// put to delete queue
						updateQueue.enqueue(recipientsEntry);
						// put to report queue
						reportService.submit(new ReportEntry(recipientsEntry.getMsgId(), batchId,
								recipientsEntry.getRecipient(), status.toString(), statusCode, response,
								entry.getCreatedOn(), new Timestamp(System.currentTimeMillis())));
						if (smtpEntry.getWebhookUrl() != null) {
							dlrForwarder.submit(new DeliverResponse(batchId, recipientsEntry.getMsgId(),
									smtpEntry.getId(), entry.getSubject(), recipientsEntry.getRecipient(),
									status.toString(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
									smtpEntry.getWebhookUrl()));
						}

						itr.remove(); // remove the local entry
						if (stop || reconnect) {
							break;
						}
						if (entry.getDelay() > 0) {
							try {
								Thread.sleep((long) (entry.getDelay() * 1000));
							} catch (InterruptedException ie) {
								logger.warn(batchId + " Processing Thread Interrupted");
							}
						}
					}
					stop = true;
				} catch (MessagingException e) {
					logger.error(systemId + "[" + batchId + "] MessagingException: " + e.getMessage());
					try {
						Thread.sleep(10 * 1000); // wait for 10 seconds
					} catch (InterruptedException e1) {
					}
				}
			} catch (Exception e) {
				logger.error(systemId + "[" + batchId + "] Exception: " + e.getMessage());
				try {
					Thread.sleep(10 * 1000); // wait for 10 seconds
				} catch (InterruptedException e1) {
				}
			}

		}
		clear();
		logger.info(batchId + " Batch Process Stopped For " + systemId);

	}

	private void listBccRecipients() {
		bccRecipients = new HashSet<>();
		if (entry.getBccRecipients() != null && !entry.getBccRecipients().isEmpty()) {
			try {
				JSONArray bccArray = new JSONArray(entry.getBccRecipients());
				for (int i = 0; i < bccArray.length(); i++) {
					String bcc = bccArray.getString(i).trim();
					// validate bcc email
					if (isValidEmail(bcc)) {
						bccRecipients.add(bcc);
					} else {
						logger.info(batchId + "Invalid BCC Recipients Found: " + bcc);
					}

				}
			} catch (JSONException e) {
				logger.warn(systemId + " " + entry.getBccRecipients(), e.getMessage());
			}
			logger.info(batchId + " Total BCC Recipients Found: " + bccRecipients.size());
		}
	}

	private void listCcRecipients() {
		ccRecipients = new HashSet<>();
		if (entry.getCcRecipients() != null && !entry.getCcRecipients().isEmpty()) {
			try {
				JSONArray ccArray = new JSONArray(entry.getCcRecipients());
				for (int i = 0; i < ccArray.length(); i++) {
					String cc = ccArray.getString(i).trim();
					// validate cc email
					if (isValidEmail(cc)) {
						ccRecipients.add(cc);
					} else {
						logger.info(batchId + "Invalid CC Recipients Found: " + cc);
					}
				}
			} catch (JSONException e) {
				logger.warn(systemId + " " + entry.getCcRecipients(), e.getMessage());
			}
			logger.info(batchId + " Total CC Recipients Found: " + ccRecipients.size());
		}
	}

	private void listAttachments() {
		attachments = new HashSet<>();
		if (entry.getAttachments() != null && !entry.getAttachments().isEmpty()) {
			JSONArray attachmentArray = new JSONArray(entry.getAttachments());
			for (int i = 0; i < attachmentArray.length(); i++) {
				String filepath = attachmentArray.getString(i).trim();
				File file = new File(filepath);
				if (file.exists()) {
					attachments.add(file);
					logger.info(batchId + ": " + filepath + " added as attachment.");
				} else {
					logger.warn(batchId + ": " + filepath + " does not exist.");
				}
			}
		}
	}

	private void clear() {
		boolean drop = false;
		if (pendingRecipients.isEmpty()) {
			entry.setBatchStatus(BatchStatus.FINISHED);
			logger.info(systemId + "[" + batchId + "] Batch Finished.");
			drop = true;
		} else if (entry.getBatchStatus() == BatchStatus.ABORTED) {
			logger.info(systemId + "[" + batchId + "] Batch Aborted.");
			drop = true;
		}
		service.updateBatchStatus(systemId, batchId, entry.getBatchStatus().toString());
		recipientEntryService.stop(drop);
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

	public EmailEntry getEntry() {
		return entry;
	}

	public SmtpEntry getSmtpEntry() {
		return smtpEntry;
	}

	public String getBatchId() {
		return batchId;
	}

	public void reloadSmtpConfiguration() throws InvalidRequestException {
		logger.info(systemId + "_" + batchId + " Reload Smtp Configuration Requested.");
		loadSmtpConfiguration();
		logger.info(systemId + "_" + batchId + " Reloaded Smtp Configuration.");
		reconnect = true;
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

	public void stop(BatchStatus status) {
		logger.info(batchId + " Batch Process Stopping For " + systemId);
		entry.setBatchStatus(status);
		stop = true;
	}

	public void stop() {
		logger.info(batchId + " Batch Process Stopping For " + systemId);
		stop = true;
	}

}
