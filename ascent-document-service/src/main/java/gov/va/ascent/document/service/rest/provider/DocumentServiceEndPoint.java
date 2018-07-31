package gov.va.ascent.document.service.rest.provider;

import java.io.IOException;
import java.util.Map;

import javax.jms.TextMessage;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.transfer.model.UploadResult;

import gov.va.ascent.document.service.api.DocumentService;
import gov.va.ascent.document.service.api.transfer.GetDocumentTypesResponse;
import gov.va.ascent.document.service.api.transfer.SubmitPayloadRequest;
import gov.va.ascent.framework.log.LogUtil;
import gov.va.ascent.framework.swagger.SwaggerResponseMessages;
import gov.va.ascent.starter.aws.s3.services.S3Service;
import gov.va.ascent.starter.aws.sqs.services.SqsService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

@RestController
public class DocumentServiceEndPoint implements SwaggerResponseMessages {

	/** Constant for the logger for this class */
	private static final Logger LOGGER = LoggerFactory.getLogger(DocumentServiceEndPoint.class);

	private static final String SENDING_MESSAGE = "Sending message {}.";
	private static final String SAMPLE_TEST_MESSAGE = "Sample Test Message";

	@Autowired
	@Qualifier("IMPL")
	DocumentService documentService;

	@Autowired
	S3Service s3Services;

	@Autowired
	SqsService sqsServices;

	@Value("${ascent.s3.bucket}")
	private String bucketName;

	@Value("${ascent.s3.target.bucket}")
	private String targetBucketName;

	@Value("${ascent.s3.dlq.bucket}")
	private String dlqBucketName;

	public static final String URL_PREFIX = "/document/v1";

	@RequestMapping(value = URL_PREFIX + "/documentTypes", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<GetDocumentTypesResponse> getDocumentTypes() {
		final GetDocumentTypesResponse docResponse = documentService.getDocumentTypes();
		LOGGER.info("DOCUMENT SERVICE getDocumentTypes INVOKED");
		return new ResponseEntity<>(docResponse, HttpStatus.OK);
	}

	@PostMapping(value = URL_PREFIX + "/uploadDocumentSendMessage")
	@ApiOperation(value = "Upload a Document and Sends Message",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> uploadDocumentSendMessage(final @RequestHeader HttpHeaders headers,
			final @RequestBody MultipartFile documentOne) {
		final Map<String, String> propertyMap = documentService.getDocumentAttributes();
		final ResponseEntity<UploadResult> uploadResult = s3Services.uploadMultiPartFile(bucketName, documentOne, propertyMap);
		if (uploadResult.getBody() == null) {
			LogUtil.logErrorWithBanner(LOGGER, "Upload Failed", "Error during Upload. Some action needs to be taken in the service");
		}
		LOGGER.info(SENDING_MESSAGE, SAMPLE_TEST_MESSAGE);

		final String jsonMessage = documentService.getMessageAttributes(documentOne.getOriginalFilename());
		final TextMessage textMessage = sqsServices.createTextMessage(jsonMessage);

		final ResponseEntity<String> jmsID = sqsServices.sendMessage(textMessage);
		if (StringUtils.isEmpty(jmsID.getBody())) {
			LogUtil.logErrorWithBanner(LOGGER, "Message Failed",
					"Error during send message. Some action needs to be taken in the service");
		}
		return ResponseEntity.ok().build();
	}

	@PostMapping(value = URL_PREFIX + "/uploadDocumentWithByteArray")
	@ApiOperation(value = "Uploads a Document afetr converting that into a byte array",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> uploadDocumentWithByteArray(final @RequestHeader HttpHeaders headers,
			final @RequestBody MultipartFile documentOne) {
		final Map<String, String> propertyMap = documentService.getDocumentAttributes();
		try {
			s3Services.uploadByteArray(bucketName, documentOne.getBytes(), "participantid/" + documentOne.getOriginalFilename(),
					propertyMap);
		} catch (final IOException e) {
			LOGGER.error("Error reading bytes: {}", e);
		}
		LOGGER.info(SENDING_MESSAGE, SAMPLE_TEST_MESSAGE);

		return ResponseEntity.ok().build();
	}

	@PostMapping("/message")
	public ResponseEntity<Object> sendMessage(@RequestBody final String message) {
		LOGGER.info(SENDING_MESSAGE, message);
		final String jsonMessage = documentService.getMessageAttributes(message);
		final TextMessage textMessage = sqsServices.createTextMessage(jsonMessage);
		sqsServices.sendMessage(textMessage);
		return ResponseEntity.ok().build();
	}

	@RequestMapping(path = URL_PREFIX + "/submit", method = RequestMethod.POST,
			consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	@ApiOperation(value = "Submit a Binary Document and Data",
			consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<GetDocumentTypesResponse> submit(@RequestHeader final HttpHeaders headers,
			@ApiParam(value = "Document to upload", required = true) @RequestBody final byte[] byteFile,
			@ApiParam(value = "Document data",
					required = true) @NotNull @NotEmpty final SubmitPayloadRequest submitPayloadRequest) {

		if (LOGGER.isDebugEnabled()) {
			if (submitPayloadRequest != null) {
				LOGGER.debug("SubmitPayloadRequest: {}", submitPayloadRequest.toString());
			}
			if (byteFile != null) {
				LOGGER.debug("Bytes: {}", byteFile);
				LOGGER.debug("Byte Length: {}", byteFile.length);
			}
		}
		final GetDocumentTypesResponse docResponse = new GetDocumentTypesResponse();
		return new ResponseEntity<>(docResponse, HttpStatus.OK);
	}

	@RequestMapping(path = URL_PREFIX + "/submitForm", method = RequestMethod.POST,
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ApiOperation(value = "Submit a MultiPart Document and Data",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<GetDocumentTypesResponse> submitForm(@RequestHeader final HttpHeaders headers,
			@ApiParam(value = "Document to upload",
					required = true) @RequestParam("file") final MultipartFile file,
			@ApiParam(value = "Document data",
					required = true) @NotNull @NotEmpty final SubmitPayloadRequest submitPayloadRequest) {

		if (LOGGER.isDebugEnabled()) {
			if (submitPayloadRequest != null) {
				LOGGER.debug("SubmitPayloadRequest: {}", submitPayloadRequest.toString());
			}
			if (file != null) {
				LOGGER.debug("MultipartFile: {}", file);
				LOGGER.debug("MultipartFile Size: {}", file.getSize());
				LOGGER.debug("MultipartFile Content Type: {}", file.getContentType());
				LOGGER.debug("MultipartFile Name: {}", file.getName());
				LOGGER.debug("MultipartFile Original Name: {}", file.getOriginalFilename());
			}
			if (headers != null) {
				headers.forEach((k, v) -> LOGGER.debug("Key: " + k + " Value: " + v));
			}
		}
		final GetDocumentTypesResponse docResponse = new GetDocumentTypesResponse();
		return new ResponseEntity<>(docResponse, HttpStatus.OK);
	}

}
