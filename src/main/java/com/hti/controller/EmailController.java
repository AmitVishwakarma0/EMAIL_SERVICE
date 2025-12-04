/**
 * SmsController.java
 *
 * Unified endpoint for Telegram-based message creation.
 * Supports both quick text input and bulk file uploads,
 * along with media attachments, location, contact, and poll messages.
 *
 * @author  Vinod Yogi
 * @since   2025-11-07
 * @version 1.2
 */

package com.hti.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hti.model.BatchProcessFilterRequest;
import com.hti.model.EmailProcessResponse;
import com.hti.model.EmailRequest;
import com.hti.service.BatchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@Tag(name = "Email Messaging API", description = "Unified endpoint to send Email messages from direct input or uploaded files.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/email-service")
public class EmailController {

	private final BatchService batchService;

	// ---------------------------------------------------------------------
	// 🚀 Unified Send Message API
	// ---------------------------------------------------------------------

	/**
	 * Unified API for sending Email messages.
	 *
	 * @param emailRequest The unified message request payload.
	 * @param ipAddress    IP address of the client making the request.
	 * @return ResponseEntity containing the batchId.
	 */
	@PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Send Email messages (bulk)", description = """
			Unified API for sending Email messages.

			🔹 **Supported Input Modes**
			- JSON fields inside `emailRequest`
			- Multiple attachments via `attachmentList`

			🔹 **Usage Example**
			multipart/form-data:
			- emailRequest: { ... JSON ... }
			- attachmentList: file(s)
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = EmailRequest.class)))
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Messages processed successfully."),
			@ApiResponse(responseCode = "400", description = "Invalid input or missing required fields."),
			@ApiResponse(responseCode = "500", description = "Internal server error.") })
	public ResponseEntity<?> sendMessages(@ModelAttribute @Valid EmailRequest emailRequest,
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true)@RequestHeader String ipAddress) 
	{
		String batchId = batchService.sendEmailMessages(emailRequest, username, ipAddress);
		System.out.println("Returning Batch Id generated: " + batchId);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", batchId));
	}

	// ---------------------------------------------------------------------
	// Batch Management APIs
	// ---------------------------------------------------------------------

	/**
	 * Fetches details of a specific batch entry for editing or inspection. Used by
	 * clients to retrieve batch configuration or progress information.
	 *
	 * Example: GET /email-service/edit?batch_Id=251117105522321
	 *
	 * @param username  Authenticated username making the request
	 * @param ipAddress IP address of the client making the request.
	 * @param batchId   Unique batch ID to fetch
	 * @return Batch details as {@link EmailProcessResponse}
	 */
	@GetMapping("/edit")
	@Operation(summary = "Edit a Batch Entry", description = """
			Retrieve details of an existing batch entry for editing or inspection.

			🔹 **Usage Example**
			`GET /email-service/edit?batch_Id=251117105522321`
			""")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Batch entry retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or username", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> editBatch(
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Unique batch ID to edit", example = "2512041546538901001", required = true) @RequestParam(name = "batch_Id") String batchId) {
		return ResponseEntity.ok(batchService.editBulk(username, ipAddress, batchId));
	}

	/**
	 * Pauses an active batch temporarily. Useful for stopping message dispatch
	 * without losing progress.
	 *
	 * Example: GET /email-service/pause?batch_Id=251117105522321
	 *
	 * @param username  Authenticated username making the request
	 * @param ipAddress IP address of the client making the request.
	 * @param batchId   Unique batch ID to pause
	 * @return Confirmation message upon successful pause
	 */
	@GetMapping("/pause")
	@Operation(summary = "Pause Batch Processing", description = """
			Temporarily pause processing of a batch entry.

			🔹 **Usage Example**
			`GET /email-service/pause?batch_Id=251117105522321`
			""")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Batch paused successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or already paused", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> pauseBatch(
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Unique batch ID to pause", example = "2512041546538901001", required = true) @RequestParam(name = "batch_Id") String batchId) {
		batchService.pauseBulk(username, ipAddress, batchId);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", batchId));
	}

	/**
	 * Completely aborts a batch process and halts any ongoing message dispatch.
	 * Once aborted, the batch cannot be resumed.
	 *
	 * Example: DELETE /email-service/abort?batch_Id=251117105522321
	 *
	 * @param username  Authenticated username making the request
	 * @param ipAddress IP address of the client making the request.
	 * @param batchId   Unique batch ID to abort
	 * @return Confirmation message upon successful abort
	 */
	@DeleteMapping("/abort")
	@Operation(summary = "Abort Batch Entry", description = """
			Permanently stop processing of a batch entry. This action cannot be undone.

			🔹 **Usage Example**
			`DELETE /email-service/abort?batch_Id=251117105522321`
			""")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Batch aborted successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or already completed", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> abortBatch(
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Unique batch ID to abort", example = "2512041546538901001", required = true) @RequestParam(name = "batch_Id") String batchId) {
		batchService.abortBulk(username, ipAddress, batchId);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", batchId));
	}

	/**
	 * Pauses an active batch temporarily. Useful for stopping message dispatch
	 * without losing progress.
	 *
	 * Example: GET /email-service/pause?batch_Id=251117105522321
	 *
	 * @param username  Authenticated username making the request
	 * @param ipAddress IP address of the client making the request.
	 * @param batchId   Unique batch ID to pause
	 * @return Confirmation message upon successful pause
	 */
	@GetMapping("/resume")
	@Operation(summary = "Resume previously Paused Batch", description = """
			Resume processing of a batch entry.

			🔹 **Usage Example**
			`GET /email-service/resume?batch_Id=251117105522321`
			""")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Batch Resumed successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or already paused", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> resume(
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress,
			@Parameter(description = "Unique batch ID to pause", example = "2512041546538901001", required = true) @RequestParam(name = "batch_Id") String batchId) {
		batchService.resumeBulk(username, ipAddress, batchId);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", batchId));
	}

	/**
	 * Update and Resumes a previously paused batch entry. Continues message
	 * dispatch from the last known checkpoint.
	 *
	 * Example: GET /email-service/updateAndResume
	 * 
	 * @param emailRequest The Updated Email Request payload.
	 * @param ipAddress    Client IP address making the request
	 * @return Confirmation message upon successful resume
	 */
	@PostMapping(value = "/updateAndResume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Update And Resume Paused Batch Entry", description = """
			Update and Resume processing of a previously paused batch entry.

			🔹 **Usage Example**
			`GET /email-service/updateAndResume`
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = EmailRequest.class)))
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Batch updated and resumed successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "400", description = "Invalid batch ID or batch not paused", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json")) })
	public ResponseEntity<?> updateAndResume(@ModelAttribute @Valid EmailRequest emailRequest,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true)@RequestHeader String ipAddress, 
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username) {
		batchService.resumeBulk(emailRequest, username, ipAddress);
		return ResponseEntity.ok(Map.of("status", "success", "batchId", emailRequest.getBatchId()));
	}

	@GetMapping("/all-bulk")
	@Operation(summary = "Retrieve All Bulk Email Batches", description = """
			Fetch all Email batches created by the specified user.

			🔹 **Typical Use Cases**
			- View active, completed, or paused bulk campaigns.
			- Useful for dashboard summaries or campaign history.

			🔹 **Example Request**
			`GET /email-service/all-bulk`

			🔹 **Response**
			Returns batch summaries containing fields like batchId, status, totalCount, and createdAt.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Bulk batches retrieved successfully.", content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "400", description = "Invalid parameters or missing headers.", content = @Content(mediaType = "application/json", schema = @Schema())),
			@ApiResponse(responseCode = "500", description = "Internal server error.", content = @Content(mediaType = "application/json", schema = @Schema())) })
	public ResponseEntity<?> getAllBulk(@Valid @ModelAttribute BatchProcessFilterRequest batchProcessFilterRequest,
			@Parameter(description = "Authenticated username or requester", example = "testUser", required = true) @RequestHeader String username,
			@Parameter(description = "Client IP address making the request", example = "127.0.0.1", required = true) @RequestHeader @NotBlank(message = "IP address header is required") String ipAddress) {
		List<EmailProcessResponse> list = batchService.getAllBulk(username, ipAddress, batchProcessFilterRequest);
		System.out.println("Returning list size: " + list.size());
		return ResponseEntity.ok(list);
	}

}
