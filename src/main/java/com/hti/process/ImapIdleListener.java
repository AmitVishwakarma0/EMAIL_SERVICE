package com.hti.process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.mail.BodyPart;
import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.StoreClosedException;
import javax.mail.UIDFolder;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import javax.mail.internet.MimeMessage;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.hti.database.service.InboxService;
import com.hti.entity.ImapEntry;
import com.hti.service.SingletonService;
import com.hti.util.GlobalVar;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import lombok.Data;

/**
 * IMAP IDLE listener for a single mailbox/systemId. - Uses UID tracking in
 * inbox_sync_status table. - On startup: fetches any messages with UID >
 * lastUID. - Then enters IDLE loop and processes new messages.
 */
public class ImapIdleListener implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(ImapIdleListener.class);
	private ImapEntry smtpEntry;
	private volatile boolean running = true;
	private final InboxService insertService; // existing service to enqueue inserts
	private String keyName;
	private IMAPFolder inbox = null;

	public ImapIdleListener(ImapEntry smtpEntry) {
		this.smtpEntry = smtpEntry;
		this.keyName = smtpEntry.getSystemId() + "_" + smtpEntry.getId();
		this.insertService = SingletonService.getUserInboxService(smtpEntry.getSystemId());
		new Thread(this, keyName + "_ImapIdleListener").start();
	}

	@Override
	public void run() {
		long backoffMillis = 10000;
		while (running) {
			IMAPStore store = null;

			try {
				Properties props = new Properties();
				props.put("mail.store.protocol", "imap");
				props.put("mail.imap.connectiontimeout", "10000");
				props.put("mail.imap.timeout", "10000");
				props.put("mail.imap.auth", "true");
				props.put("mail.debug", "true");

				switch (smtpEntry.getEncryptionType()) {
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
				logger.info(keyName + " Trying To Open Inbox[" + smtpEntry.getEmailUser() + "] @" + smtpEntry.getHost()
						+ ":" + smtpEntry.getPort());
				Session session = Session.getInstance(props);
				store = (IMAPStore) session.getStore("imap");
				store.connect(smtpEntry.getHost(), smtpEntry.getPort(), smtpEntry.getEmailUser(),
						smtpEntry.getEmailPassword());
				logger.info(keyName + " Connected Inbox[" + smtpEntry.getEmailUser() + "] @" + smtpEntry.getHost() + ":"
						+ smtpEntry.getPort());
				inbox = (IMAPFolder) store.getFolder("INBOX");
				if (!inbox.isOpen()) {
					inbox.open(Folder.READ_WRITE); // READ_WRITE to allow setting flags if needed
					logger.info(keyName + " Folder Opened[" + smtpEntry.getEmailUser() + "] @" + smtpEntry.getHost()
							+ ":" + smtpEntry.getPort());
				}

				// 1) On startup, fetch messages with UID > lastUID
				long lastUid = insertService.getLastUid(smtpEntry.getId());
				logger.info(keyName + " lastUid=" + lastUid + " - checking for missed messages");
				if (lastUid == 0) {
					Message[] recent = fetchLast3Days(inbox);
					if (recent != null && recent.length > 0) {
						processMessages(inbox, recent);
					}
				} else {
					Message[] missed = fetchMessagesSinceUID(inbox, lastUid);
					if (missed != null && missed.length > 0) {
						processMessages(inbox, missed);
					}
				}

				// Add listener for new messages
				inbox.addMessageCountListener(new MessageCountAdapter() {
					@Override
					public void messagesAdded(MessageCountEvent ev) {
						Message[] msgs = ev.getMessages();
						logger.info(keyName + " IMAP event: messagesAdded count=" + msgs.length);
						try {
							processMessages(inbox, msgs);
						} catch (Exception e) {
							logger.error(keyName + " error processing added messages", e);
						}
					}
				});

				// IDLE loop — many servers allow indefinite IDLE; some require a periodic
				// NOOP/refresh
				while (running && store.isConnected()) {
					try {
						logger.info(keyName + " entering IDLE");
						inbox.idle(); // blocks until server notifies or timeout
						// After idle returns, loop continues and listener handles messages
					} catch (FolderClosedException fce) {
						logger.warn(keyName + " folder closed, will reconnect", fce);
						break;
					} catch (StoreClosedException sce) {
						logger.warn(keyName + " store closed, will reconnect", sce);
						break;
					} catch (Exception ex) {
						logger.error(keyName + " IDLE unexpected error", ex);
						// continue to reconnect
						break;
					}
				}
			} catch (Exception e) {
				logger.error(keyName + " IMAP connection/listen error", e);
			} finally {
				// cleanup
				try {
					if (inbox != null && inbox.isOpen())
						inbox.close(false);
					if (store != null && store.isConnected())
						store.close();
				} catch (Exception ignore) {
				}
			}

			// reconnect/backoff
			if (!running)
				break;
			try {
				logger.info(keyName + " reconnecting after backoff " + backoffMillis + "ms");
				Thread.sleep(backoffMillis);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		logger.info(keyName + " ImapIdleListener stopped.");
	}

	public void stop() {
		logger.info(keyName + " ImapIdleListener stopping.");
		running = false;
	}

	private void processMessages(IMAPFolder folder, Message[] messages) {
		try {
			// Fetch UIDs efficiently
			folder.fetch(messages, new FetchProfile()); // basic fetch
			for (Message m : messages) {
				long uid = folder.getUID(m);
				if (uid <= 0) {
					logger.warn(keyName + " couldn't get UID for message; skipping");
					continue;
				}

				// Avoid processing duplicates: check lastUid
				long lastUid = insertService.getLastUid(smtpEntry.getId());
				if (uid <= lastUid) {
					logger.debug(keyName + " message uid " + uid + " <= lastUid " + lastUid + " -> skip");
					continue;
				}

				MimeMessage mime = (MimeMessage) m;
				String messageId = mime.getMessageID();
				String from = mime.getFrom()[0].toString();
				logger.info(keyName + " Processing Email : " + uid + " from: " + from);

				String subject = mime.getSubject();
				Date receivedDate = mime.getReceivedDate();

				// Extract full body (text OR html)
				String body = getTextFromMessage(mime);

				// Extract attachments
				List<EmailAttachment> attachments = getAttachments(mime);

				// ---- Save email + attachments in DB ----
				saveInboxEmail(smtpEntry.getSystemId(), uid, messageId, from, subject, body, receivedDate, attachments);

				// updateLastUid(systemId, uid);
				logger.info(keyName + " enqueued uid=" + uid + " msgId=" + messageId);
			}
		} catch (Exception e) {
			logger.error(keyName + " error processing messages", e);
		}
	}

	private void saveInboxEmail(String systemId, long uid, String messageId, String from, String subject, String body,
			Date received, List<EmailAttachment> atts) {
		String jsonFileNames = null;
		if (!atts.isEmpty()) {
			// First save files to disk
			List<String> fileNames = saveAttachmentsToDisk(systemId, uid, atts);
			// Convert list of names to JSON
			jsonFileNames = new Gson().toJson(fileNames);
		}

		insertService.insertEmail(smtpEntry.getId(), uid, smtpEntry.getEmailUser(), messageId, from, subject, body,
				new Timestamp(received.getTime()), jsonFileNames);
	}

	private List<String> saveAttachmentsToDisk(String systemId, long uid, List<EmailAttachment> attachments) {
		List<String> fileNames = new ArrayList<>();

		try {

			String baseDir = GlobalVar.ATTACHMENT_DIR + File.separator + systemId.toLowerCase() + File.separator
					+ "inbox" + File.separator + smtpEntry.getId() + File.separator + uid + File.separator;
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
				BodyPart bp = mp.getBodyPart(i);

				String disp = bp.getDisposition();
				if ((disp != null && disp.equalsIgnoreCase(Part.ATTACHMENT)) || bp.isMimeType("application/*")) {

					EmailAttachment att = new EmailAttachment();

					String name = bp.getFileName();
					name = name == null ? "unknown" : javax.mail.internet.MimeUtility.decodeText(name);
					name = name.replaceAll("[\\\\/:*?\"<>|]", "_");

					att.setFileName(name);
					att.setContentType(bp.getContentType());

					// 🔥 IMPORTANT: TAKE RAW STREAM ONLY
					try (InputStream is = bp.getInputStream();
							ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
						is.transferTo(bos);
						att.setBytes(bos.toByteArray());
					}

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

	private Message[] fetchLast3Days(IMAPFolder folder) {
		try {
			SearchTerm term = new ReceivedDateTerm(ComparisonTerm.GE,
					Date.from(LocalDate.now().minusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant()));

			Message[] msgs = folder.search(term);

			return msgs != null ? msgs : new Message[0];

		} catch (Exception e) {
			logger.error("Error fetching last 3 days", e);
			return new Message[0];
		}
	}

	private Message[] fetchMessagesSinceUID(IMAPFolder folder, long lastUid) {
		try {
			Message[] msgs;

			if (lastUid <= 0) {
				// First time → fetch ALL messages
				msgs = folder.getMessagesByUID(1, UIDFolder.LASTUID);
			} else {
				// Normal flow → fetch new messages only
				msgs = folder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
			}

			return (msgs == null) ? new Message[0] : msgs;

		} catch (Exception e) {
			logger.error(keyName + " error fetching messages by UID", e);
			return new Message[0];
		}
	}

	public void updateEntry(ImapEntry imapEntry) {
		this.smtpEntry = imapEntry;

	}

}
