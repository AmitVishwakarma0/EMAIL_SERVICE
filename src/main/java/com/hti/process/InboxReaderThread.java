package com.hti.process;

import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeMessage;
import javax.mail.search.FlagTerm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.hti.database.service.InboxService;
import com.hti.entity.ImapEntry;
import com.hti.entity.SmtpEntry;
import com.hti.service.SingletonService;
import com.hti.util.GlobalVar;
import com.hti.util.ScheduledUtility;

import lombok.Data;

public class InboxReaderThread implements Runnable {
	private Logger logger = LoggerFactory.getLogger(InboxReaderThread.class);
	private final ImapEntry entry;
	private volatile boolean running = true;
	private String keyName;
	private InboxService insertService;

	public InboxReaderThread(ImapEntry entry) {
		this.entry = entry;
		this.keyName = entry.getSystemId() + "_" + entry.getId();
	}

	public void stop() {
		running = false;
	}

	@Override
	public void run() {
		try {
			while (running) {
				processInbox();
				break;
			}
		} catch (Exception e) {
			logger.error(keyName, e);
		}
		running = false;
	}

	private void processInbox() {
		Store store = null;
		Folder inbox = null;

		try {
			Properties props = new Properties();
			props.put("mail.store.protocol", "imap");
			props.put("mail.imap.host", entry.getHost());
			props.put("mail.imap.port", entry.getPort());

			switch (entry.getEncryptionType()) {
			    case STARTTLS -> {
			        props.put("mail.imap.starttls.enable", "true");
			        props.put("mail.imap.ssl.enable", "false");
			    }
			    case SSL -> {
			        props.put("mail.store.protocol", "imaps");
			        props.put("mail.imap.ssl.enable", "true");
			    }
			    case NONE -> {
			        props.put("mail.imap.starttls.enable", "false");
			        props.put("mail.imap.ssl.enable", "false");
			    }
			}

			logger.info(keyName + " Trying To Connect SMTP Server < " + entry.getHost() + ":" + entry.getPort() + " > "
					+ entry.getEmailUser());
			Session session = Session.getInstance(props);
			store = session.getStore("imap");
			store.connect(entry.getEmailUser(), entry.getEmailPassword());

			inbox = store.getFolder("INBOX");
			inbox.open(Folder.READ_WRITE);
			logger.info(keyName + " Folder Opened < " + entry.getHost() + ":" + entry.getPort() + " > "
					+ entry.getEmailUser());
			// Only unread messages
			Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
			logger.info(keyName + " Total Unread Messages Found: " + messages.length);
			if (messages.length == 0) {
				return; // nothing to process
			}
			insertService = SingletonService.getUserInboxService(entry.getSystemId());
			for (Message msg : messages) {

				MimeMessage mime = (MimeMessage) msg;
				String messageId = mime.getMessageID();
				logger.info(keyName + " Processing Email : " + messageId);
				String from = mime.getFrom()[0].toString();
				String subject = mime.getSubject();
				Date receivedDate = mime.getReceivedDate();

				// Extract full body (text OR html)
				String body = getTextFromMessage(mime);

				// Extract attachments
				List<EmailAttachment> attachments = getAttachments(mime);

				// ---- Save email + attachments in DB ----
				saveInboxEmail(entry.getSystemId(), messageId, from, subject, body, receivedDate, attachments);

				// Mark as read
				msg.setFlag(Flags.Flag.SEEN, true);
			}

		} catch (Exception e) {
			logger.error(keyName, e);
		} finally {
			try {
				if (inbox != null && inbox.isOpen())
					inbox.close(true);
				if (store != null)
					store.close();
			} catch (Exception ignored) {
			}
		}
	}

	private void saveInboxEmail(String systemId, String messageId, String from, String subject, String body,
			Date received, List<EmailAttachment> atts) {

		// First save files to disk
		List<String> fileNames = saveAttachmentsToDisk(systemId, messageId, atts);
		// Convert list of names to JSON
		String jsonFileNames = new Gson().toJson(fileNames);
		insertService.insertEmail(entry.getId(), entry.getEmailUser(), messageId, from, subject, body,
				new Timestamp(received.getTime()), jsonFileNames);
	}

	private List<String> saveAttachmentsToDisk(String systemId, String messageId, List<EmailAttachment> attachments) {
		List<String> fileNames = new ArrayList<>();

		try {

			String baseDir = GlobalVar.ATTACHMENT_DIR + File.separator + systemId.toLowerCase() + File.separator
					+ "inbox" + File.separator + entry.getId() + File.separator + messageId + File.separator;
			File dir = new File(baseDir);
			if (!dir.exists())
				dir.mkdirs();

			for (EmailAttachment att : attachments) {
				String sanitizedName = att.getFileName().replaceAll("[^a-zA-Z0-9\\.\\-_]", "_");

				File file = new File(baseDir + sanitizedName);
				Files.write(file.toPath(), att.getBytes());
				fileNames.add(baseDir + sanitizedName);
			}

		} catch (Exception e) {
			logger.error(keyName, e);
		}

		return fileNames;
	}

	private String getTextFromMessage(Part p) throws Exception {

		if (p.isMimeType("text/*")) {
			return p.getContent().toString();
		}

		if (p.isMimeType("multipart/alternative")) {
			Multipart mp = (Multipart) p.getContent();
			String text = null;

			for (int i = 0; i < mp.getCount(); i++) {
				BodyPart bp = mp.getBodyPart(i);
				if (bp.isMimeType("text/plain")) {
					if (text == null)
						text = getTextFromMessage(bp);
					continue;
				} else if (bp.isMimeType("text/html")) {
					String s = getTextFromMessage(bp);
					if (s != null)
						return s;
				} else {
					return getTextFromMessage(bp);
				}
			}
			return text;
		}

		if (p.isMimeType("multipart/*")) {
			Multipart mp = (Multipart) p.getContent();
			for (int i = 0; i < mp.getCount(); i++) {
				String s = getTextFromMessage(mp.getBodyPart(i));
				if (s != null)
					return s;
			}
		}

		return "";
	}

	private List<EmailAttachment> getAttachments(Part part) throws Exception {
		List<EmailAttachment> list = new ArrayList<>();

		if (part.isMimeType("multipart/*")) {
			Multipart mp = (Multipart) part.getContent();

			for (int i = 0; i < mp.getCount(); i++) {
				BodyPart bodyPart = mp.getBodyPart(i);

				if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())
						|| bodyPart.isMimeType("application/*")) {

					EmailAttachment att = new EmailAttachment();
					att.setFileName(bodyPart.getFileName());
					att.setContentType(bodyPart.getContentType());
					att.setBytes(bodyPart.getInputStream().readAllBytes());
					list.add(att);
				}
			}
		}

		return list;
	}

	@Data
	private class EmailAttachment {
		private String fileName;
		private String contentType;
		private byte[] bytes;

	}

}
