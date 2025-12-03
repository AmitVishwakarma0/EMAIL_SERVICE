package com.hti.service;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.hazelcast.internal.json.JsonArray;
import com.hti.database.service.DBService;
import com.hti.entity.EmailEntry;
import com.hti.entity.EmailEntry.BatchStatus;
import com.hti.entity.EmailEntry.BatchType;
import com.hti.entity.RecipientsEntry;
import com.hti.exception.InvalidRequestException;
import com.hti.exception.ProcessingException;
import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.model.EmailRequest;
import com.hti.model.EmailScheduleResponse;
import com.hti.model.ScheduleEmailRequest;
import com.hti.process.EmailProcessor;
import com.hti.util.DiskMultipartFile;
import com.hti.util.GlobalVar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Service
public class BatchServiceImpl implements BatchService {

	private DBService dbService = new DBService();

	private Logger logger = LoggerFactory.getLogger(BatchServiceImpl.class);

	public String sendEmailMessages(EmailRequest emailRequest, String systemId, String ipAddress) {
		String batchId = GlobalVar.assignMessageId();
		logger.info("Received Batch Id generated: " + batchId + " " + emailRequest.getAttachmentList());
		emailRequest.setBatchId(batchId);
		EmailEntry entry = prepareEntry(emailRequest, emailRequest.getAttachmentList(), systemId, ipAddress);
		EmailProcessor processor = new EmailProcessor(entry);
		GlobalVar.processingMap.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>()).put(batchId,
				processor);
		return batchId;
	}

	public EmailProcessResponse editBulk(String systemId, String ipAddress, String batchId) {
		EmailEntry entry = null;
		Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
		int pendingCounter = 0;
		if (inner != null) {
			EmailProcessor processor = inner.remove(batchId);
			if (processor != null) {
				processor.stop(BatchStatus.PAUSED);
				entry = processor.getEntry();
				pendingCounter = entry.getPendingRecipientList().size();
			}
		}
		if (entry == null) {
			entry = dbService.getEntry(systemId, batchId);
			if (entry != null) {
				pendingCounter = dbService.countPendingRecipients(systemId, batchId);
			}
		}
		if (entry != null) {
			EmailProcessResponse response = prepareResponse(entry, true);
			response.setPendingCounter(pendingCounter);
			return response;
		}
		throw new InvalidRequestException("No Batch Found For batchId " + batchId);
	}

	public void pauseBulk(String systemId, String ipAddress, String batchId) {
		Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
		if (inner == null) {
			throw new InvalidRequestException("No Running Batch Found For batchId " + batchId);
		}
		EmailProcessor processor = inner.remove(batchId);
		if (processor == null) {
			throw new InvalidRequestException("No Running Batch Found For batchId " + batchId);
		}
		processor.stop(BatchStatus.PAUSED);
	}

	public void abortBulk(String systemId, String ipAddress, String batchId) {
		Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
		if (inner == null) {
			EmailEntry entry = dbService.getEntry(systemId, batchId);
			if (entry == null)
				throw new InvalidRequestException("No Batch Found For batchId " + batchId);
			if (entry.getBatchStatus() == BatchStatus.ABORTED)
				throw new InvalidRequestException("Batch already aborted");
			dbService.updateBatchStatus(systemId, batchId, BatchStatus.ABORTED.name());
			return;
		}
		EmailProcessor processor = inner.remove(batchId);
		if (processor == null)
			throw new InvalidRequestException("No Batch Found For batchId " + batchId);
		processor.stop(BatchStatus.ABORTED);
	}

	public void resumeBulk(String systemId, String ipAddress, String batchId) {
		EmailEntry entry = dbService.getEntry(systemId, batchId);
		if (entry == null) {
			throw new InvalidRequestException("No Batch Found For Requested batchId");
		}
		if (entry.getBatchStatus() != BatchStatus.PAUSED) {
			throw new InvalidRequestException("Requested batch not PAUSED");
		}
		List<RecipientsEntry> pendingRecipients = dbService.listPendingRecipients(systemId, batchId);
		if (pendingRecipients.isEmpty()) {
			throw new InvalidRequestException("No Recipients Found For Requested batchId");
		}
		entry.setPendingRecipientList(pendingRecipients);
		entry.setBatchStatus(BatchStatus.ACTIVE);
		dbService.updateBatchStatus(systemId, batchId, BatchStatus.ACTIVE.toString());
		EmailProcessor processor = new EmailProcessor(entry);
		GlobalVar.processingMap.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>()).put(batchId,
				processor);
	}

	public void resumeBulk(EmailRequest emailRequest, String systemId, String ipAddress) {
		String batchId = emailRequest.getBatchId();
		EmailEntry entry = dbService.getEntry(systemId, batchId);
		if (entry == null) {
			throw new InvalidRequestException("No Batch Found For batchId " + batchId);
		}
		if (entry.getBatchStatus() != BatchStatus.PAUSED) {
			throw new InvalidRequestException("Requested batch not PAUSED");
		}
		List<RecipientsEntry> pendingRecipients = dbService.listPendingRecipients(systemId, batchId);
		if (pendingRecipients.isEmpty()) {
			throw new InvalidRequestException("No Recipients Found For batchId " + batchId);
		}
		entry.setPendingRecipientList(pendingRecipients);
		// updatable fields from received request
		entry.setSubject(emailRequest.getSubject());
		entry.setBody(emailRequest.getBody());
		entry.setCcRecipients(emailRequest.getCcRecipients());
		entry.setBccRecipients(emailRequest.getBccRecipients());
		entry.setDelay(emailRequest.getDelay());
		entry.setSmtpId(emailRequest.getSmtpId());
		// End updatable fields from received request
		entry.setBatchStatus(BatchStatus.ACTIVE);
		EmailProcessor processor = new EmailProcessor(entry);
		GlobalVar.processingMap.computeIfAbsent(entry.getSystemId(), k -> new ConcurrentHashMap<>()).put(batchId,
				processor);
		dbService.updateBatch(entry);
	}

	public List<EmailProcessResponse> getAllBulk(String systemId, String ipAddress,
			BatchProcessFilterRequest batchProcessFilterRequest) {
		List<EmailProcessResponse> responseList = new ArrayList<EmailProcessResponse>();
		List<EmailEntry> list = dbService.listEntries(systemId, batchProcessFilterRequest);
		Map<String, EmailProcessor> inner = GlobalVar.processingMap.get(systemId);
		for (EmailEntry entry : list) {
			if (inner != null && inner.containsKey(entry.getBatchId())) {
				EmailProcessor processor = inner.get(entry.getBatchId());
				EmailEntry runningEntry = processor.getEntry();
				EmailProcessResponse response = prepareResponse(runningEntry, false);
				response.setPendingCounter(runningEntry.getPendingRecipientList().size());
				responseList.add(response);
				continue;
			}
			int pendingCounter = dbService.countPendingRecipients(systemId, entry.getBatchId());
			EmailProcessResponse response = prepareResponse(entry, false);
			response.setPendingCounter(pendingCounter);
			responseList.add(response);
		}
		return responseList;
	}

	private EmailEntry prepareEntry(EmailRequest request, List<MultipartFile> attachmentList, String systemId,
			String ipAddress) throws ProcessingException, InvalidRequestException {
		String batchId = request.getBatchId();
		logger.info(systemId + "[" + batchId + "]: Preparing EmailEntry.");
		EmailEntry entry = new EmailEntry(batchId, systemId, ipAddress, new Timestamp(System.currentTimeMillis()),
				BatchStatus.ACTIVE, BatchType.IMMEDIATE);
		BeanUtils.copyProperties(request, entry);
		JsonArray json = new JsonArray();
		if (attachmentList != null) {
			String attachmentDir = System.getProperty("user.dir") + File.separator + GlobalVar.ATTACHMENT_DIR
					+ File.separator + systemId.toLowerCase() + File.separator + batchId;
			System.out.println("attachmentDir: " + attachmentDir);
			File dir = new File(attachmentDir);
			if (!dir.exists()) {
				dir.mkdirs(); // create directories
			}
			for (MultipartFile file : attachmentList) {
				String filename = writeToFile(file, dir);
				if (filename != null) {
					json.add(filename); // add filename to JSON array
					logger.info(systemId + "[" + batchId + "]: " + filename + " Added As Attachment.");
				}
			}
			entry.setAttachments(json.toString()); // store JSON string
		}
		List<RecipientsEntry> recipients = parseRecipients(request.getRecipients());
		if (recipients.isEmpty()) {
			logger.error(systemId + "[" + batchId + "]: No Valid Recipient Found.");
			throw new InvalidRequestException("No Valid Recipient Found");
		}
		entry.setTotalRecipients(recipients.size());
		entry.setPendingRecipientList(recipients);
		if (!dbService.createBatchEntry(entry)) {
			logger.error(systemId + "[" + batchId + "]: Batch Entry Creation Failed.");
			throw new ProcessingException("Batch Entry Creation Failed");
		}
		logger.info(systemId + "[" + batchId + "]: Batch Entry Created.");
		if (!dbService.saveRecipientsEntry(recipients, systemId, batchId)) {
			logger.error(systemId + "[" + batchId + "]: Recipients Entry Creation Failed.");
			throw new ProcessingException("Recipients Entry Creation Failed");
		}
		logger.error(systemId + "[" + batchId + "] Recipients Entries:" + recipients.size());
		return entry;
	}

	private String writeToFile(MultipartFile file, File dir) {
		String originalName = file.getOriginalFilename();
		try {
			if (originalName == null || originalName.trim().isEmpty()) {
				originalName = "file_" + System.currentTimeMillis();
			}
			String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");

			File destination = new File(dir, safeName);

			file.transferTo(destination);

			return safeName; // only filename stored in DB

		} catch (Exception e) {
			logger.error(originalName, e);
			return null; // return null if file save fails
		}
	}

	private List<RecipientsEntry> parseRecipients(String jsonArrayStr) {
		List<RecipientsEntry> list = new ArrayList<>();
		String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
		if (jsonArrayStr == null || jsonArrayStr.trim().isEmpty()) {
			return list;
		}
		JSONArray array = new JSONArray(jsonArrayStr);
		for (int i = 0; i < array.length(); i++) {
			String email = array.getString(i).trim();

			if (!email.matches(EMAIL_REGEX)) {
				System.err.println("Invalid email skipped: " + email);
				continue;
			}

			list.add(new RecipientsEntry(GlobalVar.assignMessageId(), email, "F"));
		}

		return list;
	}

	private EmailProcessResponse prepareResponse(EmailEntry entry, boolean attachment) {
		EmailProcessResponse response = new EmailProcessResponse();
		BeanUtils.copyProperties(entry, response);
		response.setStatus(entry.getBatchStatus().toString());
		if (attachment) {
			List<MultipartFile> attachmentList = new ArrayList<MultipartFile>();
			if (entry.getAttachments() != null && !entry.getAttachments().isEmpty()) {
				JSONArray attachmentArray = new JSONArray(entry.getAttachments());
				String attachmentDir = GlobalVar.ATTACHMENT_DIR + File.separator + entry.getSystemId().toLowerCase()
						+ File.separator + entry.getBatchId();
				for (int i = 0; i < attachmentArray.length(); i++) {
					String filename = attachmentArray.getString(i).trim();
					File file = new File(attachmentDir, filename);
					if (file.exists()) {
						try {
							MultipartFile multipartFile = new DiskMultipartFile(file);
							attachmentList.add(multipartFile);
							logger.info(entry.getBatchId() + ": " + filename + " found as attachment.");
						} catch (IOException e) {
							logger.error(entry.getBatchId() + " " + filename, e);
						}
					} else {
						logger.warn(entry.getBatchId() + ": " + filename + " does not exist in attachment directory.");
					}
				}
			}
			response.setAttachmentList(attachmentList);
		}
		return response;
	}

}
